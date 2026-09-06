package main

import (
	"context"
	"log"
	"net/http"
	"sync"
)

func bootstrapFleet(
	ctx context.Context,
	config FleetConfig,
	client *http.Client,
	downloader *artifactDownloader,
) ([]*VehicleAgent, int) {
	agents := make([]*VehicleAgent, config.FleetSize)
	errorsByIndex := make([]error, config.FleetSize)

	var wg sync.WaitGroup

	for index := 1; index <= config.FleetSize; index++ {
		index := index

		wg.Add(1)
		go func() {
			defer wg.Done()

			vin, err := generateVIN(index)
			if err != nil {
				errorsByIndex[index-1] = err
				log.Printf("[bootstrap-%d] startup failed: %v", index, err)
				return
			}

			agent := &VehicleAgent{
				VIN:                    vin,
				SoftwareVersion:        config.InitialSoftwareVersion,
				BaseURL:                config.BaseURL,
				Client:                 client,
				Downloader:             downloader,
				HeartbeatInterval:      config.HeartbeatInterval,
				DeploymentPollInterval: config.DeploymentPollInterval,
			}

			if err := agent.registerOrResolve(ctx); err != nil {
				errorsByIndex[index-1] = err
				agent.logf("startup failed: %v", err)
				return
			}

			agents[index-1] = agent
		}()
	}

	wg.Wait()

	readyAgents := make([]*VehicleAgent, 0, config.FleetSize)
	failed := 0

	for index, agent := range agents {
		if agent == nil {
			failed++
			if errorsByIndex[index] == nil {
				log.Printf(
					"[bootstrap-%d] startup failed: unknown error",
					index+1,
				)
			}
			continue
		}

		readyAgents = append(readyAgents, agent)
	}

	return readyAgents, failed
}
