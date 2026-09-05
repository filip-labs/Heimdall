package main

import (
	"bytes"
	"context"
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
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	defaultBaseURL                = "http://localhost:8080"
	defaultFleetSize              = 10
	defaultInitialSoftwareVersion = "1.3.0"
	defaultHeartbeatInterval      = 5 * time.Second
	defaultDeploymentPollInterval = 3 * time.Second
	vehicleRequestTimeout         = 30 * time.Second
	installDelay                  = 2 * time.Second
	maxVINIndex                   = 99999999999999
)

type FleetConfig struct {
	BaseURL                string
	FleetSize              int
	InitialSoftwareVersion string
	HeartbeatInterval      time.Duration
	DeploymentPollInterval time.Duration
}

type VehicleAgent struct {
	ID                     string
	VIN                    string
	SoftwareVersion        string
	BaseURL                string
	Client                 *http.Client
	Downloader             *artifactDownloader
	HeartbeatInterval      time.Duration
	DeploymentPollInterval time.Duration
}

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

type vehiclePayload struct {
	VIN             string `json:"vin"`
	SoftwareVersion string `json:"softwareVersion"`
}

type vehicleResponse struct {
	ID              string `json:"id"`
	VIN             string `json:"vin"`
	SoftwareVersion string `json:"softwareVersion"`
}

type artifactDownloader struct {
	client      *http.Client
	wait        func(context.Context, time.Duration) error
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
	config, err := loadFleetConfigFromEnv()
	if err != nil {
		log.Fatal(err)
	}

	ctx, stop := signal.NotifyContext(
		context.Background(),
		syscall.SIGINT,
		syscall.SIGTERM,
	)
	defer stop()

	client := &http.Client{
		Timeout: vehicleRequestTimeout,
	}

	downloader := &artifactDownloader{
		client:      client,
		wait:        sleepWithContext,
		maxAttempts: 3,
		retryDelays: []time.Duration{
			1 * time.Second,
			2 * time.Second,
		},
	}

	log.Printf(
		"fleet simulator starting with %d vehicles",
		config.FleetSize,
	)

	agents, failed := bootstrapFleet(
		ctx,
		config,
		client,
		downloader,
	)

	log.Printf("fleet ready: %d/%d vehicles", len(agents), config.FleetSize)
	if failed > 0 {
		log.Printf("fleet bootstrap failures: %d", failed)
	}

	if len(agents) == 0 {
		log.Fatal("fleet bootstrap failed: no vehicles ready")
	}

	var wg sync.WaitGroup

	for _, agent := range agents {
		wg.Add(1)
		go agent.run(ctx, &wg)
	}

	<-ctx.Done()
	log.Printf("shutdown requested")

	wg.Wait()
	log.Printf("fleet simulator stopped")
}

func loadFleetConfigFromEnv() (FleetConfig, error) {
	fleetSize, err := parsePositiveIntEnv(
		"FLEET_SIZE",
		defaultFleetSize,
	)
	if err != nil {
		return FleetConfig{}, err
	}

	heartbeatInterval, err := parsePositiveDurationEnv(
		"HEARTBEAT_INTERVAL",
		defaultHeartbeatInterval,
	)
	if err != nil {
		return FleetConfig{}, err
	}

	deploymentPollInterval, err := parsePositiveDurationEnv(
		"DEPLOYMENT_POLL_INTERVAL",
		defaultDeploymentPollInterval,
	)
	if err != nil {
		return FleetConfig{}, err
	}

	initialSoftwareVersion := strings.TrimSpace(
		getEnv("INITIAL_SOFTWARE_VERSION", defaultInitialSoftwareVersion),
	)
	if initialSoftwareVersion == "" {
		return FleetConfig{}, fmt.Errorf(
			"INITIAL_SOFTWARE_VERSION must not be empty",
		)
	}

	return FleetConfig{
		BaseURL: strings.TrimRight(
			getEnv("HEIMDALL_URL", defaultBaseURL),
			"/",
		),
		FleetSize:              fleetSize,
		InitialSoftwareVersion: initialSoftwareVersion,
		HeartbeatInterval:      heartbeatInterval,
		DeploymentPollInterval: deploymentPollInterval,
	}, nil
}

func parsePositiveIntEnv(name string, fallback int) (int, error) {
	value := strings.TrimSpace(getEnv(name, strconv.Itoa(fallback)))

	parsed, err := strconv.Atoi(value)
	if err != nil {
		return 0, fmt.Errorf("invalid %s %q: %w", name, value, err)
	}

	if parsed <= 0 {
		return 0, fmt.Errorf("%s must be greater than 0", name)
	}

	return parsed, nil
}

func parsePositiveDurationEnv(
	name string,
	fallback time.Duration,
) (time.Duration, error) {
	value := strings.TrimSpace(getEnv(name, fallback.String()))

	parsed, err := time.ParseDuration(value)
	if err != nil {
		return 0, fmt.Errorf("invalid %s %q: %w", name, value, err)
	}

	if parsed <= 0 {
		return 0, fmt.Errorf("%s must be greater than 0", name)
	}

	return parsed, nil
}

func bootstrapFleet(
	ctx context.Context,
	config FleetConfig,
	client *http.Client,
	downloader *artifactDownloader,
) ([]*VehicleAgent, int) {
	agents := make([]*VehicleAgent, config.FleetSize)
	errorsByIndex := make([]error, config.FleetSize)

	var wg sync.WaitGroup

	for index := 1; index <= config.FleetSize; index++ {
		index := index

		wg.Add(1)
		go func() {
			defer wg.Done()

			vin, err := generateVIN(index)
			if err != nil {
				errorsByIndex[index-1] = err
				log.Printf("[bootstrap-%d] startup failed: %v", index, err)
				return
			}

			agent := &VehicleAgent{
				VIN:                    vin,
				SoftwareVersion:        config.InitialSoftwareVersion,
				BaseURL:                config.BaseURL,
				Client:                 client,
				Downloader:             downloader,
				HeartbeatInterval:      config.HeartbeatInterval,
				DeploymentPollInterval: config.DeploymentPollInterval,
			}

			if err := agent.registerOrResolve(ctx); err != nil {
				errorsByIndex[index-1] = err
				agent.logf("startup failed: %v", err)
				return
			}

			agents[index-1] = agent
		}()
	}

	wg.Wait()

	readyAgents := make([]*VehicleAgent, 0, config.FleetSize)
	failed := 0

	for index, agent := range agents {
		if agent == nil {
			failed++
			if errorsByIndex[index] == nil {
				log.Printf(
					"[bootstrap-%d] startup failed: unknown error",
					index+1,
				)
			}
			continue
		}

		readyAgents = append(readyAgents, agent)
	}

	return readyAgents, failed
}

func generateVIN(index int) (string, error) {
	if index <= 0 {
		return "", fmt.Errorf("vehicle index must be greater than 0")
	}

	if index > maxVINIndex {
		return "", fmt.Errorf(
			"vehicle index %d exceeds VIN capacity",
			index,
		)
	}

	vin := fmt.Sprintf("7FC%014d", index)

	if len(vin) != 17 {
		return "", fmt.Errorf(
			"generated VIN %q has invalid length %d",
			vin,
			len(vin),
		)
	}

	return vin, nil
}

func (a *VehicleAgent) run(ctx context.Context, wg *sync.WaitGroup) {
	defer wg.Done()

	var loops sync.WaitGroup

	loops.Add(2)

	go func() {
		defer loops.Done()
		a.heartbeatLoop(ctx)
	}()

	go func() {
		defer loops.Done()
		a.deploymentLoop(ctx)
	}()

	loops.Wait()
}

func (a *VehicleAgent) registerOrResolve(ctx context.Context) error {
	response, statusCode, err := a.postVehicleRegistration(ctx)
	if err != nil {
		return err
	}

	switch statusCode {
	case http.StatusCreated:
		a.ID = response.ID
		a.SoftwareVersion = response.SoftwareVersion
		a.logf("registered as %s", shortID(a.ID))
		return nil

	case http.StatusConflict:
		existingVehicle, err := a.getVehicleByVIN(ctx)
		if err != nil {
			return err
		}

		a.ID = existingVehicle.ID
		a.SoftwareVersion = existingVehicle.SoftwareVersion
		a.logf("reused existing vehicle %s", shortID(a.ID))
		return nil

	default:
		return fmt.Errorf(
			"vehicle registration returned HTTP %d",
			statusCode,
		)
	}
}

func (a *VehicleAgent) heartbeatLoop(ctx context.Context) {
	if err := a.sendHeartbeat(ctx); err != nil &&
		!errors.Is(err, context.Canceled) {
		a.logf("initial heartbeat failed: %v", err)
	}

	ticker := time.NewTicker(a.HeartbeatInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := a.sendHeartbeat(ctx); err != nil &&
				!errors.Is(err, context.Canceled) {
				a.logf("heartbeat failed: %v", err)
			}
		}
	}
}

func (a *VehicleAgent) deploymentLoop(ctx context.Context) {
	if err := a.pollDeployment(ctx); err != nil &&
		!errors.Is(err, context.Canceled) {
		a.logf("deployment poll failed: %v", err)
	}

	ticker := time.NewTicker(a.DeploymentPollInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := a.pollDeployment(ctx); err != nil &&
				!errors.Is(err, context.Canceled) {
				a.logf("deployment poll failed: %v", err)
			}
		}
	}
}

func (a *VehicleAgent) pollDeployment(ctx context.Context) error {
	deployment, err := a.getActiveDeployment(ctx)
	if err != nil || deployment == nil {
		return err
	}

	return a.processDeployment(ctx, *deployment)
}

func (a *VehicleAgent) sendHeartbeat(ctx context.Context) error {
	url := fmt.Sprintf(
		"%s/api/v1/vehicles/%s/heartbeat",
		a.BaseURL,
		a.ID,
	)

	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		url,
		nil,
	)
	if err != nil {
		return err
	}

	response, err := a.Client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()

	if response.StatusCode < 200 || response.StatusCode >= 300 {
		body, _ := io.ReadAll(response.Body)

		return fmt.Errorf(
			"heartbeat returned %d: %s",
			response.StatusCode,
			string(body),
		)
	}

	return nil
}

func (a *VehicleAgent) getActiveDeployment(
	ctx context.Context,
) (*Deployment, error) {
	url := fmt.Sprintf(
		"%s/api/v1/vehicles/%s/deployments/active",
		a.BaseURL,
		a.ID,
	)

	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodGet,
		url,
		nil,
	)
	if err != nil {
		return nil, err
	}

	response, err := a.Client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()

	if response.StatusCode == http.StatusNoContent {
		return nil, nil
	}

	if response.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(response.Body)

		return nil, fmt.Errorf(
			"deployment lookup returned %d: %s",
			response.StatusCode,
			string(body),
		)
	}

	var deployment Deployment

	if err := json.NewDecoder(response.Body).Decode(&deployment); err != nil {
		return nil, err
	}

	return &deployment, nil
}

func (a *VehicleAgent) processDeployment(
	ctx context.Context,
	deployment Deployment,
) error {
	a.logf(
		"OTA detected: %s -> %s",
		deployment.SourceSoftwareVersion,
		deployment.TargetSoftwareVersion,
	)

	if deployment.Status == "PENDING" {
		if err := a.updateDeploymentStatus(
			ctx,
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

		path, err := a.Downloader.downloadAndVerifyArtifact(
			ctx,
			deployment.ArtifactURL,
			deployment.Checksum,
			a.logf,
		)

		if err != nil {
			a.logf("artifact processing failed: %v", err)

			if failErr := a.updateDeploymentStatus(
				ctx,
				deployment.ID,
				"FAILED",
				err.Error(),
			); failErr != nil && !errors.Is(failErr, context.Canceled) {
				a.logf("failed to mark deployment FAILED: %v", failErr)
			}

			return err
		}

		artifactPath = path
		defer os.Remove(artifactPath)

		a.logf("artifact verified")
	}

	if deployment.Status == "DOWNLOADING" {
		if err := a.updateDeploymentStatus(
			ctx,
			deployment.ID,
			"DOWNLOADED",
			"",
		); err != nil {
			return err
		}

		deployment.Status = "DOWNLOADED"
	}

	if deployment.Status == "DOWNLOADED" {
		if err := a.updateDeploymentStatus(
			ctx,
			deployment.ID,
			"INSTALLING",
			"",
		); err != nil {
			return err
		}

		deployment.Status = "INSTALLING"
	}

	if deployment.Status == "INSTALLING" {
		if err := sleepWithContext(ctx, installDelay); err != nil {
			return err
		}

		if err := a.updateDeploymentStatus(
			ctx,
			deployment.ID,
			"INSTALLED",
			"",
		); err != nil {
			return err
		}

		a.SoftwareVersion = deployment.TargetSoftwareVersion
		a.logf("OTA installed: %s", deployment.TargetSoftwareVersion)
	}

	return nil
}

func (a *VehicleAgent) updateDeploymentStatus(
	ctx context.Context,
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
		a.BaseURL,
		deploymentID,
	)

	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodPatch,
		url,
		bytes.NewBuffer(body),
	)
	if err != nil {
		return err
	}

	request.Header.Set("Content-Type", "application/json")

	response, err := a.Client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()

	if response.StatusCode < 200 || response.StatusCode >= 300 {
		responseBody, _ := io.ReadAll(response.Body)

		return fmt.Errorf(
			"status update returned %d: %s",
			response.StatusCode,
			string(responseBody),
		)
	}

	return nil
}

func (a *VehicleAgent) postVehicleRegistration(
	ctx context.Context,
) (vehicleResponse, int, error) {
	payload := vehiclePayload{
		VIN:             a.VIN,
		SoftwareVersion: a.SoftwareVersion,
	}

	body, err := json.Marshal(payload)
	if err != nil {
		return vehicleResponse{}, 0, err
	}

	url := fmt.Sprintf("%s/api/v1/vehicles", a.BaseURL)

	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		url,
		bytes.NewBuffer(body),
	)
	if err != nil {
		return vehicleResponse{}, 0, err
	}

	request.Header.Set("Content-Type", "application/json")

	response, err := a.Client.Do(request)
	if err != nil {
		return vehicleResponse{}, 0, err
	}
	defer response.Body.Close()

	if response.StatusCode == http.StatusCreated {
		var vehicle vehicleResponse

		if err := json.NewDecoder(response.Body).Decode(&vehicle); err != nil {
			return vehicleResponse{}, 0, err
		}

		return vehicle, response.StatusCode, nil
	}

	if response.StatusCode == http.StatusConflict {
		return vehicleResponse{}, response.StatusCode, nil
	}

	responseBody, _ := io.ReadAll(response.Body)

	return vehicleResponse{}, response.StatusCode, fmt.Errorf(
		"vehicle registration returned %d: %s",
		response.StatusCode,
		string(responseBody),
	)
}

func (a *VehicleAgent) getVehicleByVIN(
	ctx context.Context,
) (vehicleResponse, error) {
	url := fmt.Sprintf(
		"%s/api/v1/vehicles/by-vin/%s",
		a.BaseURL,
		neturl.PathEscape(a.VIN),
	)

	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodGet,
		url,
		nil,
	)
	if err != nil {
		return vehicleResponse{}, err
	}

	response, err := a.Client.Do(request)
	if err != nil {
		return vehicleResponse{}, err
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(response.Body)

		return vehicleResponse{}, fmt.Errorf(
			"vehicle lookup returned %d: %s",
			response.StatusCode,
			string(body),
		)
	}

	var vehicle vehicleResponse

	if err := json.NewDecoder(response.Body).Decode(&vehicle); err != nil {
		return vehicleResponse{}, err
	}

	return vehicle, nil
}

func (a *VehicleAgent) logf(format string, args ...any) {
	log.Printf("[%s] %s", a.VIN, fmt.Sprintf(format, args...))
}

func (d *artifactDownloader) downloadAndVerifyArtifact(
	ctx context.Context,
	artifactURL string,
	expectedChecksum string,
	logf func(string, ...any),
) (string, error) {
	for attempt := 1; attempt <= d.maxAttempts; attempt++ {
		if logf != nil {
			logf("artifact download attempt %d/%d", attempt, d.maxAttempts)
		}

		path, err := d.downloadAndVerifyArtifactOnce(
			ctx,
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

		if logf != nil {
			logf("transient artifact download failure: %v", err)
			logf("retrying in %s", delay)
		}

		if err := d.wait(ctx, delay); err != nil {
			return "", err
		}
	}

	return "", fmt.Errorf(
		"artifact download failed after %d attempts",
		d.maxAttempts,
	)
}

func (d *artifactDownloader) downloadAndVerifyArtifactOnce(
	ctx context.Context,
	artifactURL string,
	expectedChecksum string,
) (string, error) {
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodGet,
		artifactURL,
		nil,
	)
	if err != nil {
		return "", err
	}

	response, err := d.client.Do(request)
	if err != nil {
		downloadErr := fmt.Errorf("artifact download failed: %w", err)

		if isRetryableRequestError(err) {
			return "", retryableArtifactError{err: downloadErr}
		}

		return "", downloadErr
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		statusErr := fmt.Errorf(
			"artifact download returned HTTP %d",
			response.StatusCode,
		)

		if isRetryableHTTPStatus(response.StatusCode) {
			return "", retryableArtifactError{err: statusErr}
		}

		return "", statusErr
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
		response.Body,
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

func (d *artifactDownloader) retryDelay(attempt int) time.Duration {
	if attempt <= 0 || attempt > len(d.retryDelays) {
		return 0
	}

	return d.retryDelays[attempt-1]
}

func sleepWithContext(ctx context.Context, delay time.Duration) error {
	timer := time.NewTimer(delay)
	defer timer.Stop()

	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
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

func shortID(id string) string {
	if len(id) <= 8 {
		return id
	}

	return id[:8]
}

func getEnv(key string, fallback string) string {
	value := os.Getenv(key)

	if value == "" {
		return fallback
	}

	return value
}
