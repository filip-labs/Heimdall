import http from 'k6/http';
import { check } from 'k6';
import { fleetIndexFor } from './config.js';

export function vinFor(runId, index) {
  const numericRunId = String(runId).replace(/\D/g, '').slice(-4).padStart(4, '0');
  const numericIndex = String(index).slice(-10).padStart(10, '0');
  return `7K6${numericRunId}${numericIndex}`;
}

export function registerVehicle(cfg, index) {
  const response = http.post(
    `${cfg.baseUrl}/api/v1/vehicles`,
    JSON.stringify({ vin: vinFor(cfg.runId, index), softwareVersion: cfg.softwareVersion }),
    { headers: { 'Content-Type': 'application/json' }, tags: { operation: 'registration' } },
  );
  check(response, { 'vehicle registered': (res) => res.status === 201 });
  return response;
}

export function bootstrapFleet(cfg) {
  for (let offset = 1; offset <= cfg.fleetSize; offset += 100) {
    const batch = [];
    const upper = Math.min(offset + 99, cfg.fleetSize);
    for (let index = offset; index <= upper; index += 1) {
      batch.push({
        method: 'POST',
        url: `${cfg.baseUrl}/api/v1/vehicles`,
        body: JSON.stringify({
          vin: vinFor(cfg.runId, index),
          softwareVersion: cfg.softwareVersion,
        }),
        params: {
          headers: { 'Content-Type': 'application/json' },
          tags: { operation: 'bootstrap' },
        },
      });
    }

    for (const response of http.batch(batch)) {
      if (response.status !== 201) {
        throw new Error(`fleet bootstrap failed with HTTP ${response.status}; use a fresh RUN_ID`);
      }
    }
  }
  return { fleetSize: cfg.fleetSize, runId: cfg.runId };
}

export function resolveVehicleId(cfg, vuId) {
  const fleetIndex = fleetIndexFor(vuId, cfg.fleetSize);
  const vin = vinFor(cfg.runId, fleetIndex);
  const response = http.get(`${cfg.baseUrl}/api/v1/vehicles/by-vin/${vin}`, {
    tags: { operation: 'vehicle_resolve' },
  });
  const resolved = check(response, { 'vehicle resolved': (res) => res.status === 200 });
  if (!resolved) {
    throw new Error(`could not resolve fleet vehicle ${fleetIndex} (${vin}): HTTP ${response.status}`);
  }
  return response.json().id;
}
