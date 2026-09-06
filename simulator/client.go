package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	neturl "net/url"
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

type vehiclePayload struct {
	VIN             string `json:"vin"`
	SoftwareVersion string `json:"softwareVersion"`
}

type vehicleResponse struct {
	ID              string `json:"id"`
	VIN             string `json:"vin"`
	SoftwareVersion string `json:"softwareVersion"`
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
