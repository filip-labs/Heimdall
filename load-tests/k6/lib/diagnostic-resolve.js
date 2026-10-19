import { parseDurationSeconds } from './config.js';

export function diagnosticResolveConfig(env) {
  const maxAttempts = Number(env.DIAGNOSTIC_RESOLVE_MAX_ATTEMPTS || '3');
  if (!Number.isInteger(maxAttempts) || maxAttempts < 1 || maxAttempts > 10) {
    throw new Error('DIAGNOSTIC_RESOLVE_MAX_ATTEMPTS must be an integer between 1 and 10');
  }

  return {
    maxAttempts,
    backoffSeconds: parseDurationSeconds(
      env.DIAGNOSTIC_RESOLVE_BACKOFF || '5s',
      'DIAGNOSTIC_RESOLVE_BACKOFF',
    ),
  };
}

export function createResolveState() {
  return { vehicleId: null, attempts: 0, failures: 0, retries: 0, exhausted: false };
}

export function beginResolveAttempt(state, maxAttempts) {
  if (state.vehicleId || state.exhausted || state.attempts >= maxAttempts) {
    return { attempted: false, retry: false };
  }
  const retry = state.attempts > 0;
  state.attempts += 1;
  if (retry) state.retries += 1;
  return { attempted: true, retry };
}

export function recordResolveSuccess(state, vehicleId) {
  if (!vehicleId) throw new Error('resolved vehicle id is required');
  state.vehicleId = vehicleId;
}

export function recordResolveFailure(state, maxAttempts) {
  state.failures += 1;
  if (state.attempts >= maxAttempts) state.exhausted = true;
}
