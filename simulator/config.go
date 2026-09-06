package main

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	defaultBaseURL                = "http://localhost:8080"
	defaultFleetSize              = 10
	defaultInitialSoftwareVersion = "1.3.0"
	defaultHeartbeatInterval      = 5 * time.Second
	defaultDeploymentPollInterval = 3 * time.Second
	vehicleRequestTimeout         = 30 * time.Second
)

type FleetConfig struct {
	BaseURL                string
	FleetSize              int
	InitialSoftwareVersion string
	HeartbeatInterval      time.Duration
	DeploymentPollInterval time.Duration
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

func getEnv(key string, fallback string) string {
	value := os.Getenv(key)

	if value == "" {
		return fallback
	}

	return value
}
