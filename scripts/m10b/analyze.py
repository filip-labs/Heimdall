#!/usr/bin/env python3
"""Offline operation-aware k6 analysis; no third-party dependencies."""
import argparse
import datetime as dt
import json
import math
from pathlib import Path


def timestamp(value):
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp()


def percentile(values, fraction):
    if not values:
        return None
    values = sorted(values)
    index = (len(values) - 1) * fraction
    lo, hi = math.floor(index), math.ceil(index)
    return values[lo] + (values[hi] - values[lo]) * (index - lo)


def summarize(points):
    latencies = [p[1] for p in points]
    failures = sum(p[2] for p in points)
    return dict(requests=len(points), failures=failures,
                failure_rate=failures / len(points) if points else None,
                **{name: percentile(latencies, q) for name, q in
                   [('p50_ms', .5), ('p95_ms', .95), ('p99_ms', .99), ('max_ms', 1)]})


def analyze(path, config):
    operations = {name: [] for name in ['bootstrap', 'vehicle_resolve', 'registration',
                                       'heartbeat', 'deployment_poll']}
    origins = {}
    latest = None
    warnings = []
    diagnostics = {}
    malformed = 0
    if not Path(path).exists():
        warnings.append('Sample file missing')
        lines = []
    else:
        lines = Path(path).open()
    try:
        for line in lines:
            try:
                sample = json.loads(line)
                if sample.get('type') != 'Point':
                    continue
                data, metric = sample['data'], sample['metric']
                time = timestamp(data['time'])
                value = float(data['value'])
                if not math.isfinite(value):
                    raise ValueError('non-finite metric')
                latest = max(latest or time, time)
                if metric == 'm10_scenario_start_ms':
                    name = data.get('tags', {}).get('operation')
                    if name in origins and origins[name] != value / 1000:
                        raise ValueError('Conflicting scenario origins')
                    origins[name] = value / 1000
                if metric in ('vus', 'vus_max', 'dropped_iterations', 'checks'):
                    entry = diagnostics.setdefault(metric, {'samples': 0, 'max': 0, 'sum': 0})
                    entry['samples'] += 1
                    entry['max'] = max(entry['max'], value)
                    entry['sum'] += value
                # Exactly one duration Point per request; status also detects failed
                # domain checks (e.g. registration 200 instead of 201).
                if metric != 'http_req_duration':
                    continue
                tags = data.get('tags', {})
                operation = tags.get('operation')
                if not operation:
                    warnings.append('HTTP duration sample has no operation tag')
                    continue
                status = int(tags.get('status', 0))
                expected = {'registration': (201,), 'bootstrap': (201,),
                            'vehicle_resolve': (200,), 'heartbeat': (200,),
                            'deployment_poll': (200, 204)}
                failed = status not in expected.get(operation, range(200, 400))
                operations.setdefault(operation, []).append((time, value, failed))
            except (ValueError, KeyError, TypeError, AttributeError):
                malformed += 1
    finally:
        if hasattr(lines, 'close'):
            lines.close()
    if malformed:
        warnings.append(f'{malformed} malformed sample lines; evidence incomplete')
    result = {'operations': {}, 'warnings': warnings, 'diagnostics': diagnostics,
              'classification': 'INCONCLUSIVE', 'review_status': 'REVIEW_REQUIRED'}
    recurring = {'heartbeat': 'HEARTBEAT_INTERVAL', 'deployment_poll': 'POLL_INTERVAL'}
    selected = {'heartbeat': ['heartbeat'], 'polling': ['deployment_poll'],
                'mixed': list(recurring), 'registration': ['registration']}[config['scenario']]
    for name, points in operations.items():
        entry = {'all': summarize(points)}
        if name in recurring and name in selected:
            expected = int(config['profile']) / config['seconds'][recurring[name]]
            entry['expected_rps'] = expected
            if name not in origins:
                warnings.append(f'No scenario start marker for {name}: steady-state unavailable (no guessed origin)')
            else:
                start = origins[name] + config['seconds']['RAMP_UP']
                end = start + config['seconds']['STEADY_DURATION']
                steady = summarize([p for p in points if start <= p[0] < end])
                entry['steady'] = steady
                entry['steady_window'] = [start, end]
                entry['window_complete'] = latest is not None and latest >= end
                entry['observed_rps'] = steady['requests'] / config['seconds']['STEADY_DURATION']
                entry['deviation_percent'] = 100 * (entry['observed_rps'] / expected - 1)
                if not entry['window_complete']:
                    warnings.append(f'{name}: incomplete steady window; RPS is partial evidence')
        result['operations'][name] = entry
    if config['scenario'] == 'registration':
        reg = result['operations']['registration']['all']
        result['registration'] = {'expected': int(config['profile']),
                                  'successful': reg['requests'] - reg['failures'],
                                  'failed': reg['failures']}
    if not any(operations.values()):
        warnings.append('No operation request samples')
    for name in selected:
        if not operations[name]:
            warnings.append(f'No {name} request samples')
    result['warnings'] = sorted(set(warnings))
    return result


def write_report(directory, config):
    directory = Path(directory)
    result = analyze(directory / 'k6-samples.json', config)
    result['k6_exit_code'] = config.get('k6_exit_code')
    result['database'] = config.get('database', {'status': 'NOT_COLLECTED'})
    result['run_error'] = config.get('error')
    result['resources'] = config.get('resource_peaks', {})
    result['missing_metrics'] = config.get('missing_metrics', [])
    result['monitor_errors'] = config.get('monitor_errors', [])
    (directory / 'analysis.json').write_text(json.dumps(result, indent=2) + '\n')
    lines = [f"# {config['phase'].upper()} / {config['profile']} / {config['scenario']} / {config['label']}",
             '', f"Git SHA: {config.get('git_sha', 'unknown')}; RUN_ID: {config['run_id']}",
             '', 'Latencies are milliseconds, linearly interpolated percentiles. '
             'Recurring steady windows use k6 execution.scenario.startTime plus recorded ramp-up; '
             'request timestamps are completion times. Setup traffic is separate.', '',
             '| Operation / window | Requests | Failures | Failure rate | p50 | p95 | p99 | max |',
             '|---|---:|---:|---:|---:|---:|---:|---:|']
    for name, entry in result['operations'].items():
        for window in ('all', 'steady'):
            if window in entry:
                s = entry[window]
                vals = [s[k] for k in ['requests', 'failures', 'failure_rate', 'p50_ms', 'p95_ms', 'p99_ms', 'max_ms']]
                lines.append(f"| {name} / {window} | " + ' | '.join('N/A' if v is None else f'{v:.4g}' for v in vals) + ' |')
        if 'expected_rps' in entry:
            lines.extend(['', f"{name}: expected {entry['expected_rps']:.4f} req/s; "
                          f"observed {entry.get('observed_rps', 'N/A')}; deviation "
                          f"{entry.get('deviation_percent', 'N/A')}%. Cadence requires engineering review.", ''])
    if 'registration' in result:
        lines.extend(['', f"Registrations: {result['registration']}. No sustained registration throughput is reported."])
    lines.extend(['', '## Evidence and quality', '',
                  f"k6 completion/threshold exit: {config.get('k6_exit_code', 'NOT_RUN')} "
                  '(0 = completed with thresholds passing; inspect summary and log for other exits).',
                  f"Database: {result['database']}", f"Resource peaks: {result['resources']}",
                  f"Missing metrics: {result['missing_metrics']}",
                  f"Monitoring errors: {result['monitor_errors']}",
                  f"k6 VUs / dropped iterations / checks: {result['diagnostics']}",
                  f"Run error: {result['run_error']}",
                  'Load-generator pressure: REVIEW_REQUIRED; CPU alone is not a saturation verdict.',
                  '', 'Classification: **INCONCLUSIVE** — **REVIEW_REQUIRED**.',
                  'Correlate raw resource, PostgreSQL, JVM/GC, Hikari and app evidence before choosing '
                  'HEALTHY, DEGRADED, BACKEND SATURATED, DATABASE SATURATED, CONNECTION POOL SATURATED, '
                  'LOAD GENERATOR SATURATED or INCONCLUSIVE.', '', *result['warnings']])
    (directory / 'report.md').write_text('\n'.join(lines) + '\n')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    write_report(args.directory, json.loads((args.directory / 'metadata.json').read_text()))
