import { sleep } from 'k6';
import { config, stages, broadThresholds } from './lib/config.js';
import { registerVehicle } from './lib/fleet.js';

const cfg = config();

export const options = {
  scenarios: {
    registration: {
      executor: 'ramping-vus',
      stages: stages(cfg),
      gracefulRampDown: '10s',
    },
  },
  thresholds: broadThresholds,
};

let registered = false;

export default function () {
  if (!registered) {
    registerVehicle(cfg, __VU);
    registered = true;
  }
  sleep(1);
}
