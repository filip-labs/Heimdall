const PROFILES = {
  100: { fleetSize: 100, rampUp: '30s', steady: '2m', rampDown: '15s' },
  1000: { fleetSize: 1000, rampUp: '2m', steady: '5m', rampDown: '30s' },
  5000: { fleetSize: 5000, rampUp: '5m', steady: '10m', rampDown: '1m' },
};

export function config() {
  const profileName = __ENV.PROFILE || '100';
  const profile = PROFILES[profileName];
  if (!profile) {
    throw new Error(`PROFILE must be one of ${Object.keys(PROFILES).join(', ')}`);
  }

  return {
    baseUrl: (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, ''),
    runId: __ENV.RUN_ID || '0001',
    softwareVersion: __ENV.SOFTWARE_VERSION || '1.0.0',
    heartbeatInterval: __ENV.HEARTBEAT_INTERVAL || '15s',
    pollInterval: __ENV.POLL_INTERVAL || '10s',
    ...profile,
    rampUp: __ENV.RAMP_UP || profile.rampUp,
    steady: __ENV.STEADY_DURATION || profile.steady,
    rampDown: __ENV.RAMP_DOWN || profile.rampDown,
  };
}

export function stages(cfg) {
  return [
    { duration: cfg.rampUp, target: cfg.fleetSize },
    { duration: cfg.steady, target: cfg.fleetSize },
    { duration: cfg.rampDown, target: 0 },
  ];
}

export const broadThresholds = {
  http_req_failed: ['rate<0.02'],
  http_req_duration: ['p(99)<5000'],
};
