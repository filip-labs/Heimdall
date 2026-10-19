#!/usr/bin/env python3
"""Collect low-cardinality runtime evidence during an M10C diagnostic run."""
import argparse
import datetime as dt
import json
from pathlib import Path
import subprocess
import time
import urllib.request


PROMETHEUS_URL = 'http://localhost:8080/actuator/prometheus'
TOMCAT_PREFIXES = (
    'tomcat_threads_busy_threads',
    'tomcat_threads_current_threads',
    'tomcat_threads_config_max_threads',
    'tomcat_connections_current_connections',
    'tomcat_connections_max_connections',
)
METRIC_PREFIXES = TOMCAT_PREFIXES + (
    'process_cpu_usage',
    'jvm_threads_live_threads',
    'hikaricp_connections_active',
    'hikaricp_connections_idle',
    'hikaricp_connections_pending',
    'hikaricp_connections_max',
    'http_server_requests_seconds_count',
    'http_server_requests_seconds_sum',
    'http_server_requests_seconds_max',
)
PG_ACTIVITY = (
    "SELECT state, coalesce(wait_event_type, ''), coalesce(wait_event, ''), count(*) "
    "FROM pg_stat_activity WHERE datname = 'heimdall' GROUP BY 1,2,3 ORDER BY 4 DESC;"
)


def utc_now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def select_metrics(payload):
    result = []
    for line in payload.splitlines():
        if line and not line.startswith('#') and line.startswith(METRIC_PREFIXES):
            result.append(line)
    return result


def run_command(args):
    result = subprocess.run(args, text=True, capture_output=True, timeout=10)
    return {'command': args, 'returncode': result.returncode,
            'stdout': result.stdout.strip(), 'stderr': result.stderr.strip()}


def relevant_processes(output):
    lines = output.splitlines()
    if not lines:
        return ''
    selected = [line for line in lines[1:]
                if 'HeimdallApplication' in line or 'k6 run' in line]
    return '\n'.join([lines[0], *selected])


def scrape():
    with urllib.request.urlopen(PROMETHEUS_URL, timeout=5) as response:
        payload = response.read().decode('utf-8')
    return payload, select_metrics(payload)


def sample():
    entry = {'timestamp': utc_now()}
    try:
        payload, metrics = scrape()
        entry['metrics'] = metrics
        entry['tomcat_metrics_exposed'] = sorted({
            prefix for prefix in TOMCAT_PREFIXES if any(line.startswith(prefix) for line in payload.splitlines())
        })
    except Exception as error:  # diagnostic evidence must retain scrape failures
        entry['prometheus_error'] = f'{type(error).__name__}: {error}'

    entry['postgres_activity'] = run_command([
        'docker', 'compose', '-p', 'heimdall', 'exec', '-T', 'postgres',
        'psql', '-X', '-U', 'heimdall', '-d', 'heimdall', '-At', '-F', '|', '-c', PG_ACTIVITY,
    ])
    entry['containers'] = run_command([
        'docker', 'stats', '--no-stream', '--format', '{{json .}}',
        'heimdall-postgres', 'heimdall-kafka', 'heimdall-prometheus', 'heimdall-grafana',
    ])
    processes = run_command([
        'ps', '-Ao', 'pid,ppid,%cpu,%mem,rss,nlwp,command',
    ])
    processes['stdout'] = relevant_processes(processes['stdout'])
    entry['processes'] = processes
    return entry


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--interval', type=float, default=5)
    args = parser.parse_args()
    if args.interval <= 0:
        parser.error('--interval must be greater than zero')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('a', buffering=1) as output:
        while True:
            output.write(json.dumps(sample(), separators=(',', ':')) + '\n')
            time.sleep(args.interval)


if __name__ == '__main__':
    main()
