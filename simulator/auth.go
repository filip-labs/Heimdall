package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"
)

type vehicleTokenResponse struct {
	AccessToken string `json:"accessToken"`
	TokenType   string `json:"tokenType"`
	ExpiresIn   int64  `json:"expiresIn"`
}

func (a *VehicleAgent) authNow() time.Time {
	if a.now != nil {
		return a.now()
	}
	return time.Now()
}

// Refresh is shared by heartbeat and deployment loops. Waiting callers remain cancellable.
func (a *VehicleAgent) accessToken(ctx context.Context) (string, uint64, error) {
	for {
		if err := ctx.Err(); err != nil {
			return "", 0, err
		}
		a.authMu.Lock()
		if a.token != "" && a.authNow().Before(a.refreshAt) {
			token, generation := a.token, a.tokenGeneration
			a.authMu.Unlock()
			return token, generation, nil
		}
		if pending := a.refreshing; pending != nil {
			a.authMu.Unlock()
			select {
			case <-ctx.Done():
				return "", 0, ctx.Err()
			case <-pending:
				continue
			}
		}
		pending := make(chan struct{})
		a.refreshing = pending
		a.authMu.Unlock()

		// Anchor expiry before the network call, so latency cannot extend token validity.
		started := a.authNow()
		response, err := a.authenticate(ctx)
		a.authMu.Lock()
		if err == nil {
			lifetime := time.Duration(response.ExpiresIn) * time.Second
			// JWT NumericDate truncates to seconds, so expiresIn can overstate
			// remaining validity by nearly one second. Keep a bounded reserve.
			margin := lifetime/5 + time.Second
			if maximum := lifetime * 4 / 5; margin > maximum {
				margin = maximum
			}
			a.token = response.AccessToken
			a.refreshAt = started.Add(lifetime - margin)
			a.tokenGeneration++
		}
		a.refreshing = nil
		close(pending)
		token, generation := a.token, a.tokenGeneration
		a.authMu.Unlock()
		return token, generation, err
	}
}

func (a *VehicleAgent) authenticate(ctx context.Context) (vehicleTokenResponse, error) {
	if a.ID == "" || a.credential == "" {
		return vehicleTokenResponse{}, errors.New("vehicle authentication requires a provisioning credential")
	}
	body, _ := json.Marshal(map[string]string{"vehicleId": a.ID, "credential": a.credential})
	request, err := http.NewRequestWithContext(ctx, http.MethodPost,
		a.BaseURL+"/api/v1/vehicle-auth/token", bytes.NewReader(body))
	if err != nil {
		return vehicleTokenResponse{}, errors.New("cannot construct vehicle authentication request")
	}
	request.Header.Set("Content-Type", "application/json")
	response, err := a.Client.Do(request)
	if err != nil {
		return vehicleTokenResponse{}, safeVehicleRequestError(ctx)
	}
	defer a.closeResponseBody(response.Body)
	if response.StatusCode != http.StatusOK {
		return vehicleTokenResponse{}, fmt.Errorf("vehicle authentication returned HTTP %d", response.StatusCode)
	}
	var token vehicleTokenResponse
	if err := json.NewDecoder(io.LimitReader(response.Body, 16384)).Decode(&token); err != nil {
		return vehicleTokenResponse{}, errors.New("invalid vehicle authentication response")
	}
	if token.AccessToken == "" || token.TokenType != "Bearer" || token.ExpiresIn < 1 || token.ExpiresIn > 900 {
		return vehicleTokenResponse{}, errors.New("invalid vehicle authentication response")
	}
	return token, nil
}

func safeVehicleRequestError(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	return errors.New("vehicle API request failed")
}

// Retry exactly once on 401; never reauthenticate on 403. Rebuild PATCH bodies too.
func (a *VehicleAgent) doVehicleRequest(request *http.Request) (*http.Response, error) {
	for attempt := 0; attempt < 2; attempt++ {
		token, generation, err := a.accessToken(request.Context())
		if err != nil {
			return nil, err
		}
		authenticated := request.Clone(request.Context())
		if attempt > 0 && request.GetBody != nil {
			authenticated.Body, err = request.GetBody()
			if err != nil {
				return nil, errors.New("cannot replay vehicle request")
			}
		}
		authenticated.Header.Set("Authorization", "Bearer "+token)
		response, err := a.Client.Do(authenticated)
		if err != nil {
			return nil, safeVehicleRequestError(request.Context())
		}
		if response.StatusCode != http.StatusUnauthorized {
			return response, nil
		}
		a.invalidateToken(generation)
		if attempt == 1 {
			return response, nil
		}
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
		a.closeResponseBody(response.Body)
	}
	panic("unreachable")
}

func (a *VehicleAgent) invalidateToken(generation uint64) {
	a.authMu.Lock()
	defer a.authMu.Unlock()
	// A delayed 401 cannot invalidate a newer refresh, even when JWT text is identical.
	if a.tokenGeneration == generation {
		a.token = ""
	}
}
