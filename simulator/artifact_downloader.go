package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	neturl "net/url"
	"os"
	"strings"
	"time"
)

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
