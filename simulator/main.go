package main

import (
	"context"
	"log"
	"net/http"
	"os/signal"
	"sync"
	"syscall"
	"time"
)

func main() {
	config, err := loadFleetConfigFromEnv()
	if err != nil {
		log.Fatal(err)
	}

	ctx, stop := signal.NotifyContext(
		context.Background(),
		syscall.SIGINT,
		syscall.SIGTERM,
	)
	defer stop()

	client := &http.Client{
		Timeout: vehicleRequestTimeout,
	}

	downloader := &artifactDownloader{
		client:      client,
		wait:        sleepWithContext,
		maxAttempts: 3,
		retryDelays: []time.Duration{
			1 * time.Second,
			2 * time.Second,
		},
	}

	log.Printf(
		"fleet simulator starting with %d vehicles",
		config.FleetSize,
	)
	if len(config.SimulatedFailureVINs) > 0 {
		log.Printf(
			"deterministic OTA failure injection enabled for %d vehicle(s)",
			len(config.SimulatedFailureVINs),
		)
	}

	agents, failed := bootstrapFleet(
		ctx,
		config,
		client,
		downloader,
	)

	log.Printf("fleet ready: %d/%d vehicles", len(agents), config.FleetSize)
	if failed > 0 {
		log.Printf("fleet bootstrap failures: %d", failed)
	}

	if len(agents) == 0 {
		log.Fatal("fleet bootstrap failed: no vehicles ready")
	}

	var wg sync.WaitGroup

	for _, agent := range agents {
		wg.Add(1)
		go agent.run(ctx, &wg)
	}

	<-ctx.Done()
	log.Printf("shutdown requested")

	wg.Wait()
	log.Printf("fleet simulator stopped")
}
