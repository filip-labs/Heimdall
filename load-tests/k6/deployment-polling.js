import http from 'k6/http';
import { check, sleep } from 'k6';
import { config, stages, broadThresholds } from './lib/config.js';
import { bootstrapFleet, resolveVehicleId } from './lib/fleet.js';
import { isSuccessfulDeploymentPoll } from './lib/status.js';

const cfg = config();

export const options = {
  scenarios: { polling: { executor: 'ramping-vus', stages: stages(cfg), gracefulRampDown: '10s' } },
  thresholds: broadThresholds,
  setupTimeout: cfg.setupTimeout,
};

export function setup() {
  return bootstrapFleet(cfg);
}

let vehicleId;

export default function () {
  if (!vehicleId) {
    vehicleId = resolveVehicleId(cfg, __VU);
  }
  const response = http.get(`${cfg.baseUrl}/api/v1/vehicles/${vehicleId}/deployments/active`, {
    tags: { operation: 'deployment_poll' },
  });
  check(response, {
    'poll accepted': (res) => isSuccessfulDeploymentPoll(res.status),
  });
  sleep(cfg.pollIntervalSeconds);
}
