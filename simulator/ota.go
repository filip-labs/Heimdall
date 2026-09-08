package main

import (
	"context"
	"errors"
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
		if err := a.markDeploymentDownloading(ctx, &deployment); err != nil {
			return err
		}
	}

	artifactPath, err := a.downloadDeploymentArtifact(ctx, deployment)
	if err != nil {
		return a.failDeploymentAfterArtifactError(ctx, deployment, err)
	}
	if artifactPath != "" {
		defer a.removeTemporaryArtifact(artifactPath)
	}

	if deployment.Status == "DOWNLOADING" {
		if err := a.markDeploymentDownloaded(ctx, &deployment); err != nil {
			return err
		}
	}

	if deployment.Status == "DOWNLOADED" {
		if err := a.markDeploymentInstalling(ctx, &deployment); err != nil {
			return err
		}
	}

	if deployment.Status == "INSTALLING" {
		return a.completeDeploymentInstallation(ctx, deployment)
	}

	return nil
}

func (a *VehicleAgent) markDeploymentDownloading(
	ctx context.Context,
	deployment *Deployment,
) error {
	if err := a.updateDeploymentStatus(
		ctx,
		deployment.ID,
		"DOWNLOADING",
		"",
	); err != nil {
		return err
	}

	deployment.Status = "DOWNLOADING"

	return nil
}

func (a *VehicleAgent) downloadDeploymentArtifact(
	ctx context.Context,
	deployment Deployment,
) (string, error) {
	if deployment.Status != "DOWNLOADING" &&
		deployment.Status != "DOWNLOADED" &&
		deployment.Status != "INSTALLING" {
		return "", nil
	}

	artifactPath, err := a.Downloader.downloadAndVerifyArtifact(
		ctx,
		deployment.ArtifactURL,
		deployment.Checksum,
		a.logf,
	)
	if err != nil {
		return "", err
	}

	a.logf("artifact verified")

	return artifactPath, nil
}

func (a *VehicleAgent) failDeploymentAfterArtifactError(
	ctx context.Context,
	deployment Deployment,
	err error,
) error {
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

func (a *VehicleAgent) removeTemporaryArtifact(path string) {
	if err := removeTemporaryArtifact(path); err != nil {
		a.logf("%v", err)
	}
}

func (a *VehicleAgent) markDeploymentDownloaded(
	ctx context.Context,
	deployment *Deployment,
) error {
	if err := a.updateDeploymentStatus(
		ctx,
		deployment.ID,
		"DOWNLOADED",
		"",
	); err != nil {
		return err
	}

	deployment.Status = "DOWNLOADED"

	return nil
}

func (a *VehicleAgent) markDeploymentInstalling(
	ctx context.Context,
	deployment *Deployment,
) error {
	if err := a.updateDeploymentStatus(
		ctx,
		deployment.ID,
		"INSTALLING",
		"",
	); err != nil {
		return err
	}

	deployment.Status = "INSTALLING"

	return nil
}

func (a *VehicleAgent) completeDeploymentInstallation(
	ctx context.Context,
	deployment Deployment,
) error {
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

	return nil
}

func isTerminalDeploymentStatus(status string) bool {
	return status == "FAILED" || status == "INSTALLED"
}
