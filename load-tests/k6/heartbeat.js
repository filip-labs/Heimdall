import http from 'k6/http';
import { check, sleep } from 'k6';
import { config, stages, broadThresholds } from './lib/config.js';
import { bootstrapFleet } from './lib/fleet.js';

const cfg = config();

export const options = {
  scenarios: { heartbeat: { executor: 'ramping-vus', stages: stages(cfg), gracefulRampDown: '10s' } },
  thresholds: broadThresholds,
};

export function setup() {
  return bootstrapFleet(cfg);
}

export default function (vehicles) {
  const vehicle = vehicles[(__VU - 1) % vehicles.length];
  const response = http.post(`${cfg.baseUrl}/api/v1/vehicles/${vehicle.id}/heartbeat`, null, {
    tags: { operation: 'heartbeat' },
  });
  check(response, { 'heartbeat accepted': (res) => res.status === 200 });
  sleep(cfg.heartbeatInterval);
}
