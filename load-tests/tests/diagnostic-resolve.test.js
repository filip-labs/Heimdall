import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

import {
  beginResolveAttempt,
  createResolveState,
  diagnosticResolveConfig,
  recordResolveFailure,
  recordResolveSuccess,
} from '../k6/lib/diagnostic-resolve.js';

test('original mixed baseline remains independent from diagnostic retry behavior', () => {
  const baseline = readFileSync(new URL('../k6/mixed-fleet.js', import.meta.url), 'utf8');
  assert.match(baseline, /resolveVehicleId/);
  assert.doesNotMatch(baseline, /diagnostic-resolve|DIAGNOSTIC_RESOLVE/);
});

test('diagnostic retry attempts are bounded and retry counts are exact', () => {
  const state = createResolveState();
  for (let expectedAttempt = 1; expectedAttempt <= 3; expectedAttempt += 1) {
    const result = beginResolveAttempt(state, 3);
    assert.equal(result.attempted, true);
    assert.equal(result.retry, expectedAttempt > 1);
    recordResolveFailure(state, 3);
  }
  assert.deepEqual(state, {
    vehicleId: null, attempts: 3, failures: 3, retries: 2, exhausted: true,
  });
  assert.deepEqual(beginResolveAttempt(state, 3), { attempted: false, retry: false });
});

test('successful resolution is cached and prevents more attempts', () => {
  const state = createResolveState();
  assert.equal(beginResolveAttempt(state, 3).attempted, true);
  recordResolveSuccess(state, 'vehicle-id');
  assert.equal(state.vehicleId, 'vehicle-id');
  assert.deepEqual(beginResolveAttempt(state, 3), { attempted: false, retry: false });
});

test('diagnostic retry configuration requires bounded attempts and positive backoff', () => {
  assert.deepEqual(diagnosticResolveConfig({}), { maxAttempts: 3, backoffSeconds: 5 });
  assert.deepEqual(diagnosticResolveConfig({
    DIAGNOSTIC_RESOLVE_MAX_ATTEMPTS: '4', DIAGNOSTIC_RESOLVE_BACKOFF: '500ms',
  }), { maxAttempts: 4, backoffSeconds: 0.5 });
  for (const value of ['0', '11', '1.5', 'many']) {
    assert.throws(() => diagnosticResolveConfig({ DIAGNOSTIC_RESOLVE_MAX_ATTEMPTS: value }));
  }
  assert.throws(() => diagnosticResolveConfig({ DIAGNOSTIC_RESOLVE_BACKOFF: '0s' }));
});
