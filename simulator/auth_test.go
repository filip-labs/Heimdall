package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestVehicleTokenCachingRefreshAndProtectedHeaders(t *testing.T) {
	var authentications atomic.Int32
	now := time.Unix(1000, 0)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/vehicle-auth/token" {
			var payload map[string]string
			if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
				t.Error(err)
			}
			if payload["vehicleId"] != "vehicle-1" || payload["credential"] != "private-credential" || r.Header.Get("Authorization") != "" {
				t.Error("incorrect authentication request")
			}
			n := authentications.Add(1)
			_ = json.NewEncoder(w).Encode(vehicleTokenResponse{fmt.Sprintf("access-%d", n), "Bearer", 10})
			return
		}
		expected := fmt.Sprintf("Bearer access-%d", authentications.Load())
		if r.Header.Get("Authorization") != expected || strings.Contains(r.Header.Get("Authorization"), "credential") {
			t.Error("wrong protected bearer header")
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	a := newTestAgent(server.URL, "7FC00000000000001", "1.0.0")
	a.ID, a.credential = "vehicle-1", "private-credential"
	a.now = func() time.Time { return now }
	if err := a.sendHeartbeat(context.Background()); err != nil {
		t.Fatal(err)
	}
	if _, err := a.getActiveDeployment(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := a.updateDeploymentStatus(context.Background(), "deployment-1", "DOWNLOADING", ""); err != nil {
		t.Fatal(err)
	}
	if authentications.Load() != 1 {
		t.Fatal("valid token was not cached")
	}
	now = now.Add(7 * time.Second) // 20% reserve plus one second for JWT precision
	if err := a.sendHeartbeat(context.Background()); err != nil {
		t.Fatal(err)
	}
	if authentications.Load() != 2 {
		t.Fatal("near-expiry token was not refreshed")
	}
	now = now.Add(11 * time.Second)
	if err := a.sendHeartbeat(context.Background()); err != nil {
		t.Fatal(err)
	}
	if authentications.Load() != 3 {
		t.Fatal("expired token was not refreshed")
	}
}

func TestOneSecondTTLDoesNotImmediatelyRefresh(t *testing.T) {
	var count atomic.Int32
	now := time.Unix(1000, 0)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		count.Add(1)
		_ = json.NewEncoder(w).Encode(vehicleTokenResponse{"short-token", "Bearer", 1})
	}))
	defer server.Close()
	a := newTestAgent(server.URL, "test", "1")
	a.ID, a.credential, a.now = "id", "secret", func() time.Time { return now }
	for range 5 {
		if _, _, err := a.accessToken(context.Background()); err != nil {
			t.Fatal(err)
		}
	}
	if count.Load() != 1 {
		t.Fatal("short TTL caused immediate refresh loop")
	}
	now = now.Add(200 * time.Millisecond)
	if _, _, err := a.accessToken(context.Background()); err != nil {
		t.Fatal(err)
	}
	if count.Load() != 2 {
		t.Fatal("short TTL not refreshed")
	}
}

func TestVehicle401RetriesOnceReplaysStatusBodyAnd403NeverReauthenticates(t *testing.T) {
	for _, status := range []int{http.StatusUnauthorized, http.StatusForbidden, http.StatusNoContent} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			var auth, requests atomic.Int32
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/api/v1/vehicle-auth/token" {
					n := auth.Add(1)
					_ = json.NewEncoder(w).Encode(vehicleTokenResponse{fmt.Sprint("token-", n), "Bearer", 300})
					return
				}
				n := requests.Add(1)
				var body map[string]string
				if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
					t.Error("retry lost request body")
				}
				if body["status"] != "DOWNLOADING" {
					t.Error("wrong replayed status")
				}
				if n == 1 {
					w.WriteHeader(http.StatusUnauthorized)
				} else {
					w.WriteHeader(status)
				}
			}))
			defer server.Close()
			a := newTestAgent(server.URL, "test", "1")
			a.ID, a.credential = "id", "secret"
			err := a.updateDeploymentStatus(context.Background(), "deployment", "DOWNLOADING", "")
			if (err == nil) != (status == http.StatusNoContent) {
				t.Fatalf("unexpected status handling: %v", err)
			}
			if auth.Load() != 2 || requests.Load() != 2 {
				t.Fatal("401 retry was not bounded to once")
			}
		})
	}
	// A first-response 403 must not refresh at all.
	var auth atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/vehicle-auth/token" {
			auth.Add(1)
			_ = json.NewEncoder(w).Encode(vehicleTokenResponse{"token", "Bearer", 300})
			return
		}
		w.WriteHeader(http.StatusForbidden)
	}))
	defer server.Close()
	a := newTestAgent(server.URL, "test", "1")
	a.ID, a.credential = "id", "secret"
	if err := a.sendHeartbeat(context.Background()); err == nil {
		t.Fatal("expected forbidden error")
	}
	if auth.Load() != 1 {
		t.Fatal("403 triggered reauthentication")
	}
}

func TestConcurrentVehicleCallersShareOneRefresh(t *testing.T) {
	var auth atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/vehicle-auth/token" {
			auth.Add(1)
			_ = json.NewEncoder(w).Encode(vehicleTokenResponse{"shared-token", "Bearer", 300})
			return
		}
		if r.Header.Get("Authorization") != "Bearer shared-token" {
			t.Error("missing token")
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	a := newTestAgent(server.URL, "test", "1")
	a.ID, a.credential = "id", "secret"
	var wg sync.WaitGroup
	for i := 0; i < 40; i++ {
		wg.Add(1)
		go func(poll bool) {
			defer wg.Done()
			var err error
			if poll {
				_, err = a.getActiveDeployment(context.Background())
			} else {
				err = a.sendHeartbeat(context.Background())
			}
			if err != nil {
				t.Error(err)
			}
		}(i%2 == 0)
	}
	wg.Wait()
	if auth.Load() != 1 {
		t.Fatalf("concurrent refresh requests: %d", auth.Load())
	}
}

func TestDelayed401SharesNewTokenInsteadOfTriggeringAnotherRefresh(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	var auth atomic.Int32
	pollEntered, releasePoll := make(chan struct{}), make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/vehicle-auth/token" {
			n := auth.Add(1)
			_ = json.NewEncoder(w).Encode(vehicleTokenResponse{fmt.Sprint("token-", n), "Bearer", 300})
			return
		}
		if r.Header.Get("Authorization") == "Bearer token-1" {
			if strings.HasSuffix(r.URL.Path, "/active") {
				close(pollEntered)
				select {
				case <-releasePoll:
				case <-r.Context().Done():
					return
				}
			}
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	a := newTestAgent(server.URL, "test", "1")
	a.ID, a.credential = "id", "secret"
	if _, _, err := a.accessToken(ctx); err != nil {
		t.Fatal(err)
	}
	polled := make(chan error, 1)
	go func() { _, err := a.getActiveDeployment(ctx); polled <- err }()
	select {
	case <-pollEntered:
	case <-ctx.Done():
		t.Fatal("poll did not start")
	}
	heartbeatErr := a.sendHeartbeat(ctx)
	close(releasePoll)
	if heartbeatErr != nil {
		t.Fatal(heartbeatErr)
	}
	if err := <-polled; err != nil {
		t.Fatal(err)
	}
	if auth.Load() != 2 {
		t.Fatal("delayed 401 invalidated a newer refresh")
	}
}

func TestVehicleErrorsDoNotEchoCredentialsOrTokens(t *testing.T) {
	for _, authFailure := range []bool{true, false} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == "/api/v1/vehicle-auth/token" && !authFailure {
				_ = json.NewEncoder(w).Encode(vehicleTokenResponse{"private-token", "Bearer", 300})
				return
			}
			w.WriteHeader(http.StatusBadGateway)
			_, _ = w.Write([]byte("private-credential private-token"))
		}))
		a := newTestAgent(server.URL, "test", "1")
		a.ID, a.credential = "id", "private-credential"
		err := a.sendHeartbeat(context.Background())
		if err == nil || strings.Contains(err.Error(), "private-") {
			t.Fatalf("unsafe error: %v", err)
		}
		server.Close()
	}
}

func TestVehicleRefreshWaitHonorsContextCancellation(t *testing.T) {
	started, release := make(chan struct{}), make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(started)
		<-release
		_ = json.NewEncoder(w).Encode(vehicleTokenResponse{"token", "Bearer", 300})
	}))
	defer server.Close()
	a := newTestAgent(server.URL, "test", "1")
	a.ID, a.credential = "id", "secret"
	first := make(chan error, 1)
	go func() { _, _, err := a.accessToken(context.Background()); first <- err }()
	<-started
	ctx, cancel := context.WithCancel(context.Background())
	second := make(chan error, 1)
	go func() { _, _, err := a.accessToken(ctx); second <- err }()
	cancel()
	if err := <-second; err != context.Canceled {
		t.Fatalf("cancellation lost: %v", err)
	}
	close(release)
	if err := <-first; err != nil {
		t.Fatal(err)
	}
}
