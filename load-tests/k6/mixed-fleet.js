import http from 'k6/http';
import { check, sleep } from 'k6';
import { config, stages, broadThresholds } from './lib/config.js';
import { bootstrapFleet, resolveVehicleId } from './lib/fleet.js';
import { isSuccessfulDeploymentPoll } from './lib/status.js';

const cfg = config();

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

let heartbeatVehicleId;
let pollingVehicleId;

export function heartbeat() {
  if (!heartbeatVehicleId) {
    heartbeatVehicleId = resolveVehicleId(cfg, __VU);
  }
  const response = http.post(`${cfg.baseUrl}/api/v1/vehicles/${heartbeatVehicleId}/heartbeat`, null, {
    tags: { operation: 'heartbeat' },
  });
  check(response, { 'heartbeat accepted': (res) => res.status === 200 });
  sleep(cfg.heartbeatIntervalSeconds);
}

export function poll() {
  if (!pollingVehicleId) {
    pollingVehicleId = resolveVehicleId(cfg, __VU);
  }
  const response = http.get(`${cfg.baseUrl}/api/v1/vehicles/${pollingVehicleId}/deployments/active`, {
    tags: { operation: 'deployment_poll' },
  });
  check(response, {
    'poll accepted': (res) => isSuccessfulDeploymentPoll(res.status),
  });
  sleep(cfg.pollIntervalSeconds);
}
