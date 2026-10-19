import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { config, fleetIndexFor, stages, broadThresholds } from './lib/config.js';
import { bootstrapFleet, vinFor } from './lib/fleet.js';
import { isSuccessfulDeploymentPoll } from './lib/status.js';
import {
  beginResolveAttempt,
  createResolveState,
  diagnosticResolveConfig,
  recordResolveFailure,
  recordResolveSuccess,
} from './lib/diagnostic-resolve.js';

const cfg = config();
const resolveCfg = diagnosticResolveConfig(__ENV);

const resolveAttempts = new Counter('m10c_vehicle_resolve_attempts');
const resolveFailures = new Counter('m10c_vehicle_resolve_failures');
const resolveRetries = new Counter('m10c_vehicle_resolve_retries');
const resolveExhausted = new Counter('m10c_vehicle_resolve_exhausted');

export const options = {
  scenarios: {
    heartbeats: { executor: 'ramping-vus', exec: 'heartbeat', stages: stages(cfg) },
    polling: { executor: 'ramping-vus', exec: 'poll', stages: stages(cfg) },
  },
  thresholds: broadThresholds,
  setupTimeout: cfg.setupTimeout,
};

export function setup() {
  return bootstrapFleet(cfg);
}

const heartbeatResolve = createResolveState();
const pollingResolve = createResolveState();

function resolveOnce(state, scenario, normalCadenceSeconds) {
  if (state.vehicleId) return state.vehicleId;
  if (state.exhausted) {
    sleep(normalCadenceSeconds);
    return null;
  }

  const attempt = beginResolveAttempt(state, resolveCfg.maxAttempts);
  if (!attempt.attempted) {
    sleep(normalCadenceSeconds);
    return null;
  }

  const tags = { scenario };
  resolveAttempts.add(1, tags);
  if (attempt.retry) resolveRetries.add(1, tags);

  const fleetIndex = fleetIndexFor(__VU, cfg.fleetSize);
  const vin = vinFor(cfg.runId, fleetIndex);
  const response = http.get(`${cfg.baseUrl}/api/v1/vehicles/by-vin/${vin}`, {
    tags: { operation: 'vehicle_resolve_diagnostic', scenario },
  });
  const resolved = check(response, {
    'diagnostic vehicle resolved': (res) => res.status === 200,
  }, tags);

  if (resolved) {
    recordResolveSuccess(state, response.json().id);
    return state.vehicleId;
  }

  resolveFailures.add(1, tags);
  recordResolveFailure(state, resolveCfg.maxAttempts);
  if (state.exhausted) resolveExhausted.add(1, tags);
  sleep(resolveCfg.backoffSeconds);
  return null;
}

export function heartbeat() {
  const vehicleId = resolveOnce(heartbeatResolve, 'heartbeat', cfg.heartbeatIntervalSeconds);
  if (!vehicleId) return;

  const response = http.post(`${cfg.baseUrl}/api/v1/vehicles/${vehicleId}/heartbeat`, null, {
    tags: { operation: 'heartbeat' },
  });
  check(response, { 'heartbeat accepted': (res) => res.status === 200 });
  sleep(cfg.heartbeatIntervalSeconds);
}

export function poll() {
  const vehicleId = resolveOnce(pollingResolve, 'polling', cfg.pollIntervalSeconds);
  if (!vehicleId) return;

  const response = http.get(`${cfg.baseUrl}/api/v1/vehicles/${vehicleId}/deployments/active`, {
    tags: { operation: 'deployment_poll' },
  });
  check(response, { 'poll accepted': (res) => isSuccessfulDeploymentPoll(res.status) });
  sleep(cfg.pollIntervalSeconds);
}
