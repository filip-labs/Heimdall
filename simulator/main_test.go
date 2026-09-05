package main

import (
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

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
		client:      server.Client(),
		sleep:       func(delay time.Duration) { delays = append(delays, delay) },
		maxAttempts: 3,
		retryDelays: []time.Duration{time.Second, 2 * time.Second},
	}

	path, err := downloader.downloadAndVerifyArtifact(
		server.URL,
		expectedChecksum,
	)
	if err != nil {
		t.Fatalf("expected retry flow to succeed, got %v", err)
	}
	defer os.Remove(path)

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
		client:      server.Client(),
		sleep:       func(delay time.Duration) { delays = append(delays, delay) },
		maxAttempts: 3,
		retryDelays: []time.Duration{time.Second, 2 * time.Second},
	}

	_, err := downloader.downloadAndVerifyArtifact(server.URL, "unused")
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
		client:      server.Client(),
		sleep:       func(delay time.Duration) { delays = append(delays, delay) },
		maxAttempts: 3,
		retryDelays: []time.Duration{time.Second, 2 * time.Second},
	}

	_, err := downloader.downloadAndVerifyArtifact(server.URL, "deadbeef")
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
		client:      server.Client(),
		sleep:       func(delay time.Duration) { delays = append(delays, delay) },
		maxAttempts: 3,
		retryDelays: []time.Duration{time.Second, 2 * time.Second},
	}

	_, err := downloader.downloadAndVerifyArtifact(server.URL, "unused")
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

func checksumFor(payload []byte) string {
	sum := sha256.Sum256(payload)

	return hex.EncodeToString(sum[:])
}
