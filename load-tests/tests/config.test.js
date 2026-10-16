import assert from 'node:assert/strict';
import test from 'node:test';

import { fleetIndexFor, parseDurationSeconds } from '../k6/lib/config.js';
import { isSuccessfulDeploymentPoll } from '../k6/lib/status.js';

test('parses human-friendly sleep durations into seconds', () => {
  assert.equal(parseDurationSeconds('500ms', 'INTERVAL'), 0.5);
  assert.equal(parseDurationSeconds('5s', 'INTERVAL'), 5);
  assert.equal(parseDurationSeconds('1.5m', 'INTERVAL'), 90);
  assert.equal(parseDurationSeconds('1h', 'INTERVAL'), 3600);
});

test('rejects invalid and non-positive sleep durations', () => {
  for (const value of ['', '15', '0s', '-1s', 'tomorrow']) {
    assert.throws(() => parseDurationSeconds(value, 'INTERVAL'));
  }
});

test('maps global VU ids onto one logical fleet', () => {
  assert.equal(fleetIndexFor(1, 100), 1);
  assert.equal(fleetIndexFor(100, 100), 100);
  assert.equal(fleetIndexFor(101, 100), 1);
  assert.equal(fleetIndexFor(250, 100), 50);
});

test('accepts active and idle deployment polling responses', () => {
  assert.equal(isSuccessfulDeploymentPoll(200), true);
  assert.equal(isSuccessfulDeploymentPoll(204), true);
  assert.equal(isSuccessfulDeploymentPoll(404), false);
  assert.equal(isSuccessfulDeploymentPoll(500), false);
});
