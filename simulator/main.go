package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"time"
)

type Deployment struct {
	ID                     string `json:"id"`
	VehicleID              string `json:"vehicleId"`
	VIN                    string `json:"vin"`
	CurrentSoftwareVersion string `json:"currentSoftwareVersion"`
	TargetSoftwareVersion  string `json:"targetSoftwareVersion"`
	Status                 string `json:"status"`
}

var (
	baseURL   string
	vehicleID string

	client = &http.Client{
		Timeout: 5 * time.Second,
	}
)

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
				log.Printf(
					"deployment %s failed: %v",
					deployment.ID,
					err,
				)
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
		deployment.CurrentSoftwareVersion,
		deployment.TargetSoftwareVersion,
	)

	states := []string{
		"DOWNLOADING",
		"DOWNLOADED",
		"INSTALLING",
		"INSTALLED",
	}

	startIndex := 0

	switch deployment.Status {
	case "PENDING":
		startIndex = 0
	case "DOWNLOADING":
		startIndex = 1
	case "DOWNLOADED":
		startIndex = 2
	case "INSTALLING":
		startIndex = 3
	default:
		return nil
	}

	for i := startIndex; i < len(states); i++ {
		state := states[i]

		log.Printf("deployment %s -> %s", deployment.ID, state)

		if err := updateDeploymentStatus(deployment.ID, state); err != nil {
			return err
		}

		switch state {
		case "DOWNLOADING":
			time.Sleep(4 * time.Second)
		case "DOWNLOADED":
			time.Sleep(2 * time.Second)
		case "INSTALLING":
			time.Sleep(4 * time.Second)
		}
	}

	log.Printf(
		"OTA installation completed: %s",
		deployment.TargetSoftwareVersion,
	)

	return nil
}

func updateDeploymentStatus(deploymentID string, status string) error {
	payload := map[string]string{
		"status": status,
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
