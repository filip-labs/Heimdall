package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestGenerateVINStartsAtOne(t *testing.T) {
	t.Parallel()

	vin, err := generateVIN(1)
	if err != nil {
		t.Fatalf("expected VIN generation to succeed, got %v", err)
	}

	if vin != "7FC00000000000001" {
		t.Fatalf("unexpected VIN %q", vin)
	}
}

func TestGenerateVINHasExpectedLength(t *testing.T) {
	t.Parallel()

	vin, err := generateVIN(42)
	if err != nil {
		t.Fatalf("expected VIN generation to succeed, got %v", err)
	}

	if len(vin) != 17 {
		t.Fatalf("expected VIN length 17, got %d", len(vin))
	}
}

func TestGenerateVINsAreUnique(t *testing.T) {
	t.Parallel()

	seen := make(map[string]struct{})

	for index := 1; index <= 100; index++ {
		vin, err := generateVIN(index)
		if err != nil {
			t.Fatalf("unexpected VIN generation error at %d: %v", index, err)
		}

		if _, exists := seen[vin]; exists {
			t.Fatalf("duplicate VIN generated: %s", vin)
		}

		seen[vin] = struct{}{}
	}
}

func TestParseSimulatedFailureVINs(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name      string
		value     string
		expected  []string
		wantError bool
	}{
		{
			name:     "empty",
			value:    "",
			expected: []string{},
		},
		{
			name:     "one VIN",
			value:    "7FC00000000000003",
			expected: []string{"7FC00000000000003"},
		},
		{
			name:  "multiple VINs",
			value: "7FC00000000000003,7FC00000000000017",
			expected: []string{
				"7FC00000000000003",
				"7FC00000000000017",
			},
		},
		{
			name:  "trims whitespace",
			value: " 7FC00000000000003, 7FC00000000000017 ",
			expected: []string{
				"7FC00000000000003",
				"7FC00000000000017",
			},
		},
		{
			name:     "deduplicates and ignores empty entries",
			value:    "7FC00000000000003,, 7FC00000000000003, ",
			expected: []string{"7FC00000000000003"},
		},
		{
			name:      "invalid VIN",
			value:     "not-a-vin",
			wantError: true,
		},
	}

	for _, test := range tests {
		test := test

		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			configuredVINs, err := parseSimulatedFailureVINs(test.value)

			if test.wantError {
				if err == nil {
					t.Fatal("expected invalid VIN to be rejected")
				}

				if !strings.Contains(err.Error(), "SIMULATED_FAILURE_VINS") {
					t.Fatalf("unexpected error: %v", err)
				}

				return
			}

			if err != nil {
				t.Fatalf("expected parsing to succeed, got %v", err)
			}

			if len(configuredVINs) != len(test.expected) {
				t.Fatalf(
					"expected %d VINs, got %d",
					len(test.expected),
					len(configuredVINs),
				)
			}

			for _, vin := range test.expected {
				if _, exists := configuredVINs[vin]; !exists {
					t.Fatalf("expected VIN %s to be configured", vin)
				}
			}
		})
	}
}

func TestRegisterOrResolveCreatesVehicle(t *testing.T) {
	t.Parallel()

	server := httptest.NewServer(http.HandlerFunc(func(
		writer http.ResponseWriter,
		request *http.Request,
	) {
		if request.Method != http.MethodPost ||
			request.URL.Path != "/api/v1/vehicles" {
			t.Fatalf("unexpected request %s %s", request.Method, request.URL.Path)
		}

		var payload vehiclePayload
		if err := json.NewDecoder(request.Body).Decode(&payload); err != nil {
			t.Fatalf("failed to decode request: %v", err)
		}

		writer.Header().Set("Content-Type", "application/json")
		writer.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(writer).Encode(vehicleResponse{
			ID:              "vehicle-1",
			VIN:             payload.VIN,
			SoftwareVersion: payload.SoftwareVersion,
		})
	}))
	defer server.Close()

	agent := newTestAgent(server.URL, "7FC00000000000001", "1.3.0")

	if err := agent.registerOrResolve(context.Background()); err != nil {
		t.Fatalf("expected registration to succeed, got %v", err)
	}

	if agent.ID != "vehicle-1" {
		t.Fatalf("unexpected vehicle ID %q", agent.ID)
	}
}

func TestRegisterOrResolveReusesVehicleOnConflict(t *testing.T) {
	t.Parallel()

	var requests []string
	var mu sync.Mutex

	server := httptest.NewServer(http.HandlerFunc(func(
		writer http.ResponseWriter,
		request *http.Request,
	) {
		mu.Lock()
		requests = append(requests, request.Method+" "+request.URL.Path)
		mu.Unlock()

		switch {
		case request.Method == http.MethodPost &&
			request.URL.Path == "/api/v1/vehicles":
			writer.WriteHeader(http.StatusConflict)
		case request.Method == http.MethodGet &&
			request.URL.Path == "/api/v1/vehicles/by-vin/7FC00000000000001":
			writer.Header().Set("Content-Type", "application/json")
			writer.WriteHeader(http.StatusOK)
			_ = json.NewEncoder(writer).Encode(vehicleResponse{
				ID:              "existing-vehicle",
				VIN:             "7FC00000000000001",
				SoftwareVersion: "1.4.0",
			})
		default:
			t.Fatalf("unexpected request %s %s", request.Method, request.URL.Path)
		}
	}))
	defer server.Close()

	agent := newTestAgent(server.URL, "7FC00000000000001", "1.3.0")

	if err := agent.registerOrResolve(context.Background()); err != nil {
		t.Fatalf("expected conflict resolution to succeed, got %v", err)
	}

	if agent.ID != "existing-vehicle" {
		t.Fatalf("unexpected vehicle ID %q", agent.ID)
	}

	if agent.SoftwareVersion != "1.4.0" {
		t.Fatalf(
			"expected software version from existing vehicle, got %q",
			agent.SoftwareVersion,
		)
	}

	if len(requests) != 2 {
		t.Fatalf("expected 2 requests, got %d", len(requests))
	}
}

func TestRegisterOrResolveReturnsErrorOnUnexpectedStatus(t *testing.T) {
	t.Parallel()

	server := httptest.NewServer(http.HandlerFunc(func(
		writer http.ResponseWriter,
		request *http.Request,
	) {
		writer.WriteHeader(http.StatusBadGateway)
		_, _ = writer.Write([]byte("backend unavailable"))
	}))
	defer server.Close()

	agent := newTestAgent(server.URL, "7FC00000000000001", "1.3.0")

	err := agent.registerOrResolve(context.Background())
	if err == nil {
		t.Fatal("expected registration to fail")
	}

	if !strings.Contains(err.Error(), "vehicle registration returned 502") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestBootstrapFleetInitializesDistinctVehicles(t *testing.T) {
	t.Parallel()

	var (
		mu       sync.Mutex
		seenVINs = make(map[string]struct{})
	)

	server := httptest.NewServer(http.HandlerFunc(func(
		writer http.ResponseWriter,
		request *http.Request,
	) {
		if request.Method != http.MethodPost ||
			request.URL.Path != "/api/v1/vehicles" {
			t.Fatalf("unexpected request %s %s", request.Method, request.URL.Path)
		}

		var payload vehiclePayload
		if err := json.NewDecoder(request.Body).Decode(&payload); err != nil {
			t.Fatalf("failed to decode request: %v", err)
		}

		mu.Lock()
		if _, exists := seenVINs[payload.VIN]; exists {
			mu.Unlock()
			t.Fatalf("duplicate VIN registered: %s", payload.VIN)
		}
		seenVINs[payload.VIN] = struct{}{}
		mu.Unlock()

		writer.Header().Set("Content-Type", "application/json")
		writer.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(writer).Encode(vehicleResponse{
			ID:              fmt.Sprintf("vehicle-%s", payload.VIN),
			VIN:             payload.VIN,
			SoftwareVersion: payload.SoftwareVersion,
		})
	}))
	defer server.Close()

	client := server.Client()
	downloader := &artifactDownloader{
		client:      client,
		wait:        func(context.Context, time.Duration) error { return nil },
		maxAttempts: 3,
		retryDelays: []time.Duration{time.Second, 2 * time.Second},
	}

	agents, failed := bootstrapFleet(
		context.Background(),
		FleetConfig{
			BaseURL:                server.URL,
			FleetSize:              5,
			InitialSoftwareVersion: "1.3.0",
			HeartbeatInterval:      time.Second,
			DeploymentPollInterval: time.Second,
			SimulatedFailureVINs: map[string]struct{}{
				"7FC00000000000003": {},
			},
		},
		client,
		downloader,
	)

	if failed != 0 {
		t.Fatalf("expected 0 startup failures, got %d", failed)
	}

	if len(agents) != 5 {
		t.Fatalf("expected 5 agents, got %d", len(agents))
	}

	seenIDs := make(map[string]struct{})

	for _, agent := range agents {
		if len(agent.VIN) != 17 {
			t.Fatalf("unexpected VIN length for %s", agent.VIN)
		}

		if _, exists := seenIDs[agent.ID]; exists {
			t.Fatalf("duplicate vehicle ID %s", agent.ID)
		}

		seenIDs[agent.ID] = struct{}{}

		if agent.VIN == "7FC00000000000003" &&
			!agent.SimulateInstallFailure {
			t.Fatalf("expected %s to simulate install failure", agent.VIN)
		}

		if agent.VIN != "7FC00000000000003" &&
			agent.SimulateInstallFailure {
			t.Fatalf("expected %s to install normally", agent.VIN)
		}
	}
}

func TestDownloadAndVerifyArtifactRetriesTransientFailures(t *testing.T) {
	t.Parallel()

	payload := []byte("artifact payload")
	expectedChecksum := checksumFor(payload)
	attempts := 0

	server := httptest.NewServer(http.HandlerFunc(func(
		writer http.ResponseWriter,
		request *http.Request,
	) {
		attempts++

		if attempts <= 2 {
			writer.WriteHeader(http.StatusServiceUnavailable)
			return
		}

		writer.WriteHeader(http.StatusOK)
		_, _ = writer.Write(payload)
	}))
	defer server.Close()

	var delays []time.Duration

	downloader := artifactDownloader{
		client: server.Client(),
		wait: func(_ context.Context, delay time.Duration) error {
			delays = append(delays, delay)
			return nil
		},
		maxAttempts: 3,
		retryDelays: []time.Duration{time.Second, 2 * time.Second},
	}

	path, err := downloader.downloadAndVerifyArtifact(
		context.Background(),
		server.URL,
		expectedChecksum,
		nil,
	)
	if err != nil {
		t.Fatalf("expected retry flow to succeed, got %v", err)
	}
	removeTestArtifact(t, path)

	if attempts != 3 {
		t.Fatalf("expected 3 attempts, got %d", attempts)
	}

	if len(delays) != 2 ||
		delays[0] != time.Second ||
		delays[1] != 2*time.Second {
		t.Fatalf("unexpected retry delays: %#v", delays)
	}
}

func TestDownloadAndVerifyArtifactDoesNotRetryNotFound(t *testing.T) {
	t.Parallel()

	attempts := 0

	server := httptest.NewServer(http.HandlerFunc(func(
		writer http.ResponseWriter,
		request *http.Request,
	) {
		attempts++
		writer.WriteHeader(http.StatusNotFound)
	}))
	defer server.Close()

	var delays []time.Duration

	downloader := artifactDownloader{
		client: server.Client(),
		wait: func(_ context.Context, delay time.Duration) error {
			delays = append(delays, delay)
			return nil
		},
		maxAttempts: 3,
		retryDelays: []time.Duration{time.Second, 2 * time.Second},
	}

	_, err := downloader.downloadAndVerifyArtifact(
		context.Background(),
		server.URL,
		"unused",
		nil,
	)
	if err == nil {
		t.Fatal("expected 404 to fail")
	}

	if !strings.Contains(err.Error(), "HTTP 404") {
		t.Fatalf("expected HTTP 404 error, got %v", err)
	}

	if attempts != 1 {
		t.Fatalf("expected 1 attempt, got %d", attempts)
	}

	if len(delays) != 0 {
		t.Fatalf("expected no retry delays, got %#v", delays)
	}
}

func TestDownloadAndVerifyArtifactDoesNotRetryChecksumMismatch(t *testing.T) {
	t.Parallel()

	payload := []byte("artifact payload")
	attempts := 0

	server := httptest.NewServer(http.HandlerFunc(func(
		writer http.ResponseWriter,
		request *http.Request,
	) {
		attempts++
		writer.WriteHeader(http.StatusOK)
		_, _ = writer.Write(payload)
	}))
	defer server.Close()

	var delays []time.Duration

	downloader := artifactDownloader{
		client: server.Client(),
		wait: func(_ context.Context, delay time.Duration) error {
			delays = append(delays, delay)
			return nil
		},
		maxAttempts: 3,
		retryDelays: []time.Duration{time.Second, 2 * time.Second},
	}

	_, err := downloader.downloadAndVerifyArtifact(
		context.Background(),
		server.URL,
		"deadbeef",
		nil,
	)
	if err == nil {
		t.Fatal("expected checksum mismatch to fail")
	}

	if !strings.Contains(err.Error(), "checksum mismatch") {
		t.Fatalf("expected checksum mismatch error, got %v", err)
	}

	if attempts != 1 {
		t.Fatalf("expected 1 attempt, got %d", attempts)
	}

	if len(delays) != 0 {
		t.Fatalf("expected no retry delays, got %#v", delays)
	}
}

func TestDownloadAndVerifyArtifactStopsAfterThreeAttempts(t *testing.T) {
	t.Parallel()

	attempts := 0

	server := httptest.NewServer(http.HandlerFunc(func(
		writer http.ResponseWriter,
		request *http.Request,
	) {
		attempts++
		writer.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer server.Close()

	var delays []time.Duration

	downloader := artifactDownloader{
		client: server.Client(),
		wait: func(_ context.Context, delay time.Duration) error {
			delays = append(delays, delay)
			return nil
		},
		maxAttempts: 3,
		retryDelays: []time.Duration{time.Second, 2 * time.Second},
	}

	_, err := downloader.downloadAndVerifyArtifact(
		context.Background(),
		server.URL,
		"unused",
		nil,
	)
	if err == nil {
		t.Fatal("expected repeated 503 responses to fail")
	}

	if !strings.Contains(err.Error(), "failed after 3 attempts") {
		t.Fatalf("expected exhaustion error, got %v", err)
	}

	if attempts != 3 {
		t.Fatalf("expected 3 attempts, got %d", attempts)
	}

	if len(delays) != 2 ||
		delays[0] != time.Second ||
		delays[1] != 2*time.Second {
		t.Fatalf("unexpected retry delays: %#v", delays)
	}
}

func TestProcessDeploymentInstallsNormally(t *testing.T) {
	t.Parallel()

	payload := []byte("normal artifact")
	var updates []deploymentStatusUpdate
	var mu sync.Mutex

	server := newOTATestServer(t, payload, &updates, &mu)
	defer server.Close()

	agent := newOTATestAgent(
		server,
		"7FC00000000000001",
		"2.0.0",
		false,
	)

	err := agent.processDeployment(context.Background(), Deployment{
		ID:                    "deployment-1",
		SourceSoftwareVersion: "2.0.0",
		TargetSoftwareVersion: "2.1.0",
		ArtifactURL:           server.URL + "/artifact.bin",
		Checksum:              checksumFor(payload),
		Status:                "PENDING",
	})
	if err != nil {
		t.Fatalf("expected OTA to succeed, got %v", err)
	}

	assertDeploymentStatuses(t, updates, []string{
		"DOWNLOADING",
		"DOWNLOADED",
		"INSTALLING",
		"INSTALLED",
	})

	if agent.SoftwareVersion != "2.1.0" {
		t.Fatalf("expected software version 2.1.0, got %q", agent.SoftwareVersion)
	}
}

func TestProcessDeploymentSimulatesInstallationFailure(t *testing.T) {
	t.Parallel()

	payload := []byte("failure artifact")
	var updates []deploymentStatusUpdate
	var mu sync.Mutex

	server := newOTATestServer(t, payload, &updates, &mu)
	defer server.Close()

	agent := newOTATestAgent(
		server,
		"7FC00000000000003",
		"2.0.0",
		true,
	)

	err := agent.processDeployment(context.Background(), Deployment{
		ID:                    "deployment-1",
		SourceSoftwareVersion: "2.0.0",
		TargetSoftwareVersion: "2.1.0",
		ArtifactURL:           server.URL + "/artifact.bin",
		Checksum:              checksumFor(payload),
		Status:                "PENDING",
	})
	if err != nil {
		t.Fatalf("expected simulated failure to be reported, got %v", err)
	}

	assertDeploymentStatuses(t, updates, []string{
		"DOWNLOADING",
		"DOWNLOADED",
		"INSTALLING",
		"FAILED",
	})

	lastUpdate := updates[len(updates)-1]
	if lastUpdate.FailureReason != simulatedInstallationFailureReason {
		t.Fatalf(
			"expected failure reason %q, got %q",
			simulatedInstallationFailureReason,
			lastUpdate.FailureReason,
		)
	}

	if agent.SoftwareVersion != "2.0.0" {
		t.Fatalf("expected software version to remain 2.0.0, got %q", agent.SoftwareVersion)
	}
}

func TestProcessDeploymentDoesNotReprocessTerminalDeployments(t *testing.T) {
	t.Parallel()

	requests := 0
	var mu sync.Mutex

	server := httptest.NewServer(http.HandlerFunc(func(
		writer http.ResponseWriter,
		request *http.Request,
	) {
		mu.Lock()
		requests++
		mu.Unlock()

		t.Fatalf("unexpected request for terminal deployment: %s %s", request.Method, request.URL.Path)
	}))
	defer server.Close()

	agent := newOTATestAgent(
		server,
		"7FC00000000000003",
		"2.0.0",
		true,
	)

	for _, status := range []string{"FAILED", "INSTALLED"} {
		for index := 0; index < 2; index++ {
			err := agent.processDeployment(context.Background(), Deployment{
				ID:                    "deployment-1",
				SourceSoftwareVersion: "2.0.0",
				TargetSoftwareVersion: "2.1.0",
				ArtifactURL:           server.URL + "/artifact.bin",
				Checksum:              "unused",
				Status:                status,
			})
			if err != nil {
				t.Fatalf(
					"expected terminal %s deployment to be ignored, got %v",
					status,
					err,
				)
			}
		}
	}

	restartedAgent := newOTATestAgent(
		server,
		"7FC00000000000003",
		"2.0.0",
		true,
	)

	err := restartedAgent.processDeployment(context.Background(), Deployment{
		ID:                    "deployment-1",
		SourceSoftwareVersion: "2.0.0",
		TargetSoftwareVersion: "2.1.0",
		ArtifactURL:           server.URL + "/artifact.bin",
		Checksum:              "unused",
		Status:                "FAILED",
	})
	if err != nil {
		t.Fatalf("expected restarted agent to ignore terminal deployment, got %v", err)
	}

	mu.Lock()
	defer mu.Unlock()
	if requests != 0 {
		t.Fatalf("expected no backend calls, got %d", requests)
	}
}

func newTestAgent(
	baseURL string,
	vin string,
	softwareVersion string,
) *VehicleAgent {
	client := &http.Client{Timeout: vehicleRequestTimeout}

	return &VehicleAgent{
		VIN:             vin,
		SoftwareVersion: softwareVersion,
		BaseURL:         baseURL,
		Client:          client,
		Downloader: &artifactDownloader{
			client:      client,
			wait:        func(context.Context, time.Duration) error { return nil },
			maxAttempts: 3,
			retryDelays: []time.Duration{time.Second, 2 * time.Second},
		},
		HeartbeatInterval:      time.Second,
		DeploymentPollInterval: time.Second,
		InstallWait:            func(context.Context, time.Duration) error { return nil },
	}
}

func removeTestArtifact(t *testing.T, path string) {
	t.Helper()

	t.Cleanup(func() {
		if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("failed to remove test artifact %s: %v", path, err)
		}
	})
}

type deploymentStatusUpdate struct {
	Status        string `json:"status"`
	FailureReason string `json:"failureReason"`
}

func newOTATestAgent(
	server *httptest.Server,
	vin string,
	softwareVersion string,
	simulateInstallFailure bool,
) *VehicleAgent {
	agent := newTestAgent(server.URL, vin, softwareVersion)
	agent.Client = server.Client()
	agent.Downloader.client = server.Client()
	agent.SimulateInstallFailure = simulateInstallFailure

	return agent
}

func newOTATestServer(
	t *testing.T,
	payload []byte,
	updates *[]deploymentStatusUpdate,
	mu *sync.Mutex,
) *httptest.Server {
	t.Helper()

	return httptest.NewServer(http.HandlerFunc(func(
		writer http.ResponseWriter,
		request *http.Request,
	) {
		switch {
		case request.Method == http.MethodGet &&
			request.URL.Path == "/artifact.bin":
			writer.WriteHeader(http.StatusOK)
			_, _ = writer.Write(payload)

		case request.Method == http.MethodPatch &&
			request.URL.Path == "/api/v1/deployments/deployment-1/status":
			var update deploymentStatusUpdate
			if err := json.NewDecoder(request.Body).Decode(&update); err != nil {
				t.Fatalf("failed to decode status update: %v", err)
			}

			mu.Lock()
			*updates = append(*updates, update)
			mu.Unlock()

			writer.WriteHeader(http.StatusNoContent)

		default:
			t.Fatalf("unexpected request %s %s", request.Method, request.URL.Path)
		}
	}))
}

func assertDeploymentStatuses(
	t *testing.T,
	updates []deploymentStatusUpdate,
	expected []string,
) {
	t.Helper()

	statuses := make([]string, 0, len(updates))
	for _, update := range updates {
		statuses = append(statuses, update.Status)
	}

	if !slices.Equal(statuses, expected) {
		t.Fatalf("expected status sequence %v, got %v", expected, statuses)
	}
}

func checksumFor(payload []byte) string {
	sum := sha256.Sum256(payload)

	return hex.EncodeToString(sum[:])
}
