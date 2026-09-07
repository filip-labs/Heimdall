package main

import (
	"context"
	"errors"
	"os"
	"time"
)

const installDelay = 2 * time.Second
const simulatedInstallationFailureReason = "simulated installation failure"

func (a *VehicleAgent) processDeployment(
	ctx context.Context,
	deployment Deployment,
) error {
	if isTerminalDeploymentStatus(deployment.Status) {
		return nil
	}

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
		if err := a.waitForInstall(ctx); err != nil {
			return err
		}

		if a.SimulateInstallFailure {
			a.logf(
				"simulating OTA installation failure for deployment %s",
				deployment.ID,
			)

			return a.updateDeploymentStatus(
				ctx,
				deployment.ID,
				"FAILED",
				simulatedInstallationFailureReason,
			)
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

func isTerminalDeploymentStatus(status string) bool {
	return status == "FAILED" || status == "INSTALLED"
}
