import http from 'k6/http';
import { check, sleep } from 'k6';
import { config, stages, broadThresholds } from './lib/config.js';
import { bootstrapFleet, resolveVehicleId } from './lib/fleet.js';

const cfg = config();

export const options = {
  scenarios: { heartbeat: { executor: 'ramping-vus', stages: stages(cfg), gracefulRampDown: '10s' } },
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
  const response = http.post(`${cfg.baseUrl}/api/v1/vehicles/${vehicleId}/heartbeat`, null, {
    tags: { operation: 'heartbeat' },
  });
  check(response, { 'heartbeat accepted': (res) => res.status === 200 });
  sleep(cfg.heartbeatIntervalSeconds);
}
