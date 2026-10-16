const PROFILES = {
  100: { fleetSize: 100, rampUp: '30s', steady: '2m', rampDown: '15s', setupTimeout: '2m' },
  1000: { fleetSize: 1000, rampUp: '2m', steady: '5m', rampDown: '30s', setupTimeout: '5m' },
  5000: { fleetSize: 5000, rampUp: '5m', steady: '10m', rampDown: '1m', setupTimeout: '10m' },
};

const DURATION_FACTORS = { ms: 0.001, s: 1, m: 60, h: 3600 };

export function parseDurationSeconds(value, name) {
  const match = String(value).trim().match(/^(\d+(?:\.\d+)?)(ms|s|m|h)$/);
  if (!match) {
    throw new Error(`${name} must be a positive duration such as 500ms, 5s, 1m, or 1h`);
  }

  const seconds = Number(match[1]) * DURATION_FACTORS[match[2]];
  if (!Number.isFinite(seconds) || seconds <= 0) {
    throw new Error(`${name} must be greater than zero`);
  }
  return seconds;
}

export function fleetIndexFor(vuId, fleetSize) {
  if (!Number.isInteger(vuId) || vuId < 1 || !Number.isInteger(fleetSize) || fleetSize < 1) {
    throw new Error('VU id and fleet size must be positive integers');
  }
  return ((vuId - 1) % fleetSize) + 1;
}

export function config() {
  const profileName = __ENV.PROFILE || '100';
  const profile = PROFILES[profileName];
  if (!profile) {
    throw new Error(`PROFILE must be one of ${Object.keys(PROFILES).join(', ')}`);
  }
  const runId = __ENV.RUN_ID || '0001';
  if (!/^\d{1,4}$/.test(runId)) {
    throw new Error('RUN_ID must contain between one and four digits');
  }
  const setupTimeout = __ENV.SETUP_TIMEOUT || profile.setupTimeout;
  parseDurationSeconds(setupTimeout, 'SETUP_TIMEOUT');

  return {
    baseUrl: (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, ''),
    runId,
    softwareVersion: __ENV.SOFTWARE_VERSION || '1.0.0',
    ...profile,
    heartbeatIntervalSeconds: parseDurationSeconds(
      __ENV.HEARTBEAT_INTERVAL || '15s',
      'HEARTBEAT_INTERVAL',
    ),
    pollIntervalSeconds: parseDurationSeconds(__ENV.POLL_INTERVAL || '10s', 'POLL_INTERVAL'),
    rampUp: __ENV.RAMP_UP || profile.rampUp,
    steady: __ENV.STEADY_DURATION || profile.steady,
    rampDown: __ENV.RAMP_DOWN || profile.rampDown,
    setupTimeout,
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
