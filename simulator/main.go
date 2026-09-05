package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	neturl "net/url"
	"os"
	"strings"
	"time"
)

type Deployment struct {
	ID                    string `json:"id"`
	VehicleID             string `json:"vehicleId"`
	VIN                   string `json:"vin"`
	SourceSoftwareVersion string `json:"sourceSoftwareVersion"`
	TargetSoftwareVersion string `json:"targetSoftwareVersion"`
	ArtifactURL           string `json:"artifactUrl"`
	Checksum              string `json:"checksum"`
	Status                string `json:"status"`
}

var (
	baseURL   string
	vehicleID string

	client = &http.Client{
		Timeout: 30 * time.Second,
	}

	downloader = artifactDownloader{
		client:      client,
		sleep:       time.Sleep,
		maxAttempts: 3,
		retryDelays: []time.Duration{
			1 * time.Second,
			2 * time.Second,
		},
	}
)

type artifactDownloader struct {
	client      *http.Client
	sleep       func(time.Duration)
	maxAttempts int
	retryDelays []time.Duration
}

type retryableArtifactError struct {
	err error
}

func (e retryableArtifactError) Error() string {
	return e.err.Error()
}

func (e retryableArtifactError) Unwrap() error {
	return e.err
}

func main() {
	baseURL = getEnv("HEIMDALL_URL", "http://localhost:8080")
	vehicleID = os.Getenv("VEHICLE_ID")

	if vehicleID == "" {
		log.Fatal("VEHICLE_ID environment variable is required")
	}

	log.Println("Heimdall Vehicle Agent starting")
	log.Printf("Vehicle ID: %s", vehicleID)
	log.Printf("Control plane: %s", baseURL)

	if err := sendHeartbeat(); err != nil {
		log.Printf("initial heartbeat failed: %v", err)
	}

	go heartbeatLoop()

	for {
		deployment, err := getActiveDeployment()

		if err != nil {
			log.Printf("failed to check deployment: %v", err)
			time.Sleep(3 * time.Second)
			continue
		}

		if deployment != nil {
			if err := processDeployment(*deployment); err != nil {
				log.Printf("deployment %s failed: %v", deployment.ID, err)
			}
		}

		time.Sleep(3 * time.Second)
	}
}

func heartbeatLoop() {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		if err := sendHeartbeat(); err != nil {
			log.Printf("heartbeat failed: %v", err)
		}
	}
}

func sendHeartbeat() error {
	url := fmt.Sprintf(
		"%s/api/v1/vehicles/%s/heartbeat",
		baseURL,
		vehicleID,
	)

	req, err := http.NewRequest(http.MethodPost, url, nil)
	if err != nil {
		return err
	}

	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		body, _ := io.ReadAll(resp.Body)

		return fmt.Errorf(
			"heartbeat returned %d: %s",
			resp.StatusCode,
			string(body),
		)
	}

	return nil
}

func getActiveDeployment() (*Deployment, error) {
	url := fmt.Sprintf(
		"%s/api/v1/vehicles/%s/deployments/active",
		baseURL,
		vehicleID,
	)

	resp, err := client.Get(url)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNoContent {
		return nil, nil
	}

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)

		return nil, fmt.Errorf(
			"deployment lookup returned %d: %s",
			resp.StatusCode,
			string(body),
		)
	}

	var deployment Deployment

	if err := json.NewDecoder(resp.Body).Decode(&deployment); err != nil {
		return nil, err
	}

	return &deployment, nil
}

func processDeployment(deployment Deployment) error {
	log.Printf(
		"OTA deployment detected: %s -> %s",
		deployment.SourceSoftwareVersion,
		deployment.TargetSoftwareVersion,
	)

	if deployment.Status == "PENDING" {
		if err := updateDeploymentStatus(
			deployment.ID,
			"DOWNLOADING",
			"",
		); err != nil {
			return err
		}

		deployment.Status = "DOWNLOADING"
	}

	var artifactPath string

	if deployment.Status == "DOWNLOADING" ||
		deployment.Status == "DOWNLOADED" ||
		deployment.Status == "INSTALLING" {

		log.Printf("downloading artifact: %s", deployment.ArtifactURL)

		path, err := downloadAndVerifyArtifact(
			deployment.ArtifactURL,
			deployment.Checksum,
		)

		if err != nil {
			log.Printf("artifact verification failed: %v", err)

			if failErr := updateDeploymentStatus(
				deployment.ID,
				"FAILED",
				err.Error(),
			); failErr != nil {
				log.Printf(
					"failed to mark deployment FAILED: %v",
					failErr,
				)
			}

			return err
		}

		artifactPath = path
		defer os.Remove(artifactPath)

		log.Printf("artifact verified successfully: %s", artifactPath)
	}

	if deployment.Status == "DOWNLOADING" {
		if err := updateDeploymentStatus(
			deployment.ID,
			"DOWNLOADED",
			"",
		); err != nil {
			return err
		}

		deployment.Status = "DOWNLOADED"
	}

	if deployment.Status == "DOWNLOADED" {
		if err := updateDeploymentStatus(
			deployment.ID,
			"INSTALLING",
			"",
		); err != nil {
			return err
		}

		deployment.Status = "INSTALLING"
	}

	if deployment.Status == "INSTALLING" {
		log.Printf(
			"installing verified artifact for version %s",
			deployment.TargetSoftwareVersion,
		)

		time.Sleep(2 * time.Second)

		if err := updateDeploymentStatus(
			deployment.ID,
			"INSTALLED",
			"",
		); err != nil {
			return err
		}

		log.Printf(
			"OTA installation completed: %s",
			deployment.TargetSoftwareVersion,
		)
	}

	return nil
}

func downloadAndVerifyArtifact(
	artifactURL string,
	expectedChecksum string,
) (string, error) {
	return downloader.downloadAndVerifyArtifact(
		artifactURL,
		expectedChecksum,
	)
}

func (d artifactDownloader) downloadAndVerifyArtifact(
	artifactURL string,
	expectedChecksum string,
) (string, error) {
	for attempt := 1; attempt <= d.maxAttempts; attempt++ {
		log.Printf(
			"artifact download attempt %d/%d",
			attempt,
			d.maxAttempts,
		)

		path, err := d.downloadAndVerifyArtifactOnce(
			artifactURL,
			expectedChecksum,
		)
		if err == nil {
			return path, nil
		}

		if !isRetryableArtifactError(err) {
			return "", err
		}

		if attempt == d.maxAttempts {
			return "", fmt.Errorf(
				"artifact download failed after %d attempts: %w",
				d.maxAttempts,
				err,
			)
		}

		delay := d.retryDelay(attempt)

		log.Printf("transient artifact download failure: %v", err)
		log.Printf("retrying in %s", delay)

		d.sleep(delay)
	}

	return "", fmt.Errorf(
		"artifact download failed after %d attempts",
		d.maxAttempts,
	)
}

func (d artifactDownloader) downloadAndVerifyArtifactOnce(
	artifactURL string,
	expectedChecksum string,
) (string, error) {
	resp, err := d.client.Get(artifactURL)
	if err != nil {
		downloadErr := fmt.Errorf("artifact download failed: %w", err)

		if isRetryableRequestError(err) {
			return "", retryableArtifactError{err: downloadErr}
		}

		return "", downloadErr
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		err = fmt.Errorf(
			"artifact download returned HTTP %d",
			resp.StatusCode,
		)

		if isRetryableHTTPStatus(resp.StatusCode) {
			return "", retryableArtifactError{err: err}
		}

		return "", err
	}

	file, err := os.CreateTemp("", "heimdall-ota-*.bin")
	if err != nil {
		return "", fmt.Errorf(
			"failed to create temporary artifact file: %w",
			err,
		)
	}

	path := file.Name()

	hash := sha256.New()

	if _, err := io.Copy(
		io.MultiWriter(file, hash),
		resp.Body,
	); err != nil {
		file.Close()
		os.Remove(path)

		return "", fmt.Errorf(
			"failed to store artifact: %w",
			err,
		)
	}

	if err := file.Close(); err != nil {
		os.Remove(path)

		return "", fmt.Errorf(
			"failed to close artifact file: %w",
			err,
		)
	}

	actualChecksum := hex.EncodeToString(hash.Sum(nil))

	log.Printf("expected SHA-256: %s", expectedChecksum)
	log.Printf("actual SHA-256:   %s", actualChecksum)

	if !strings.EqualFold(actualChecksum, expectedChecksum) {
		os.Remove(path)

		return "", fmt.Errorf(
			"checksum mismatch: expected %s but got %s",
			expectedChecksum,
			actualChecksum,
		)
	}

	return path, nil
}

func (d artifactDownloader) retryDelay(attempt int) time.Duration {
	if attempt <= 0 || attempt > len(d.retryDelays) {
		return 0
	}

	return d.retryDelays[attempt-1]
}

func isRetryableArtifactError(err error) bool {
	var retryableErr retryableArtifactError

	return errors.As(err, &retryableErr)
}

func isRetryableRequestError(err error) bool {
	var netErr net.Error

	if errors.As(err, &netErr) {
		return true
	}

	var urlErr *neturl.Error

	return errors.As(err, &urlErr) && urlErr.Op != "parse"
}

func isRetryableHTTPStatus(statusCode int) bool {
	switch statusCode {
	case http.StatusInternalServerError,
		http.StatusBadGateway,
		http.StatusServiceUnavailable,
		http.StatusGatewayTimeout:
		return true
	default:
		return false
	}
}

func updateDeploymentStatus(
	deploymentID string,
	status string,
	failureReason string,
) error {

	payload := map[string]string{
		"status": status,
	}

	if failureReason != "" {
		payload["failureReason"] = failureReason
	}

	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}

	url := fmt.Sprintf(
		"%s/api/v1/deployments/%s/status",
		baseURL,
		deploymentID,
	)

	req, err := http.NewRequest(
		http.MethodPatch,
		url,
		bytes.NewBuffer(body),
	)
	if err != nil {
		return err
	}

	req.Header.Set("Content-Type", "application/json")

	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		responseBody, _ := io.ReadAll(resp.Body)

		return fmt.Errorf(
			"status update returned %d: %s",
			resp.StatusCode,
			string(responseBody),
		)
	}

	return nil
}

func getEnv(key string, fallback string) string {
	value := os.Getenv(key)

	if value == "" {
		return fallback
	}

	return value
}
