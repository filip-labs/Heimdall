package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
	"sync"
	"time"
)

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

func (a *VehicleAgent) logf(format string, args ...any) {
	log.Printf("[%s] %s", a.VIN, fmt.Sprintf(format, args...))
}

func shortID(id string) string {
	if len(id) <= 8 {
		return id
	}

	return id[:8]
}
