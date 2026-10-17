#!/usr/bin/env python3
"""One local disposable k6 run, or warmup/run1/run2 for exactly one profile."""
import argparse
import csv
import datetime as dt
import fcntl
import json
import os
from pathlib import Path
import platform
import re
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
import uuid

from analyze import write_report

ROOT = Path(__file__).resolve().parents[2]
BASELINE = '6699f04d6ea7d933cb4c161fd62b4e2745cd8caf'
PATHS = ['src', 'simulator', 'load-tests/k6', 'pom.xml', 'compose.yaml']
SCRIPTS = {'registration': 'registration.js', 'heartbeat': 'heartbeat.js',
           'polling': 'deployment-polling.js', 'mixed': 'mixed-fleet.js'}
COMPOSE = ['docker', 'compose', '-p', 'heimdall', '--project-directory', str(ROOT), '-f', str(ROOT / 'compose.yaml')]
SQL = ['exec', '-T', 'postgres', 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'heimdall', '-d', 'heimdall']
ACTIVITY = "SELECT state, wait_event_type, wait_event, count(*) FROM pg_stat_activity WHERE datname = 'heimdall' GROUP BY 1,2,3 ORDER BY 4 DESC;"
TABLES = 'SELECT relname, n_live_tup, n_dead_tup, seq_scan, seq_tup_read, idx_scan, idx_tup_fetch FROM pg_stat_user_tables ORDER BY n_live_tup DESC;'
METRICS = ['hikaricp_connections_' + x for x in ['active', 'idle', 'pending', 'max', 'min']] + [
    'process_cpu_usage', 'system_cpu_usage', 'jvm_memory_used_bytes', 'jvm_memory_max_bytes',
    'jvm_threads_live_threads', 'jvm_gc_pause_seconds_count', 'jvm_gc_pause_seconds_sum',
    'heimdall_outbox_pending', 'heimdall_outbox_published_total',
    'heimdall_outbox_publish_failures_total', 'heimdall_kafka_consumer_failures_total',
    'heimdall_deployments_created_total', 'heimdall_rollout_evaluation_duration_seconds_count']


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def stop(process):
    """Terminate the owned session, including Maven's Java child and command children."""
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass
    # The session leader may already have exited while a child remains.
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait()


def command(args, *, timeout=30, check=True, env=None):
    process = subprocess.Popen(args, cwd=ROOT, env=env, text=True, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, start_new_session=True)
    try:
        output, _ = process.communicate(timeout=timeout)
    except BaseException:
        stop(process)
        process.stdout.close()
        raise
    if check and process.returncode:
        raise RuntimeError(f'{shlex.join(args)} failed ({process.returncode}): {output[-4000:]}')
    return output.strip()


def seconds(value):
    match = re.fullmatch(r'(\d+(?:\.\d+)?)(ms|s|m|h)', value)
    if not match or float(match[1]) <= 0:
        raise ValueError(f'Invalid positive duration: {value}')
    return float(match[1]) * {'ms': .001, 's': 1, 'm': 60, 'h': 3600}[match[2]]


def workload(profile, run_id):
    """Read the small existing config literal; fail closed if its structure changes."""
    source = (ROOT / 'load-tests/k6/lib/config.js').read_text()
    match = re.search(r'^\s*' + profile + r':\s*\{([^}]+)\}', source, re.M)
    if not match:
        raise ValueError('Cannot read profile from existing k6 config')
    defaults = dict(re.findall(r"(\w+):\s*'([^']+)'", match[1]))
    env = {'PROFILE': profile, 'RUN_ID': run_id, 'BASE_URL': 'http://localhost:8080'}
    for key, field in [('RAMP_UP', 'rampUp'), ('STEADY_DURATION', 'steady'),
                       ('RAMP_DOWN', 'rampDown'), ('SETUP_TIMEOUT', 'setupTimeout')]:
        env[key] = os.environ.get(key, defaults[field])
    for key in ['HEARTBEAT_INTERVAL', 'POLL_INTERVAL', 'SOFTWARE_VERSION']:
        match = re.search(r'__ENV\.' + key + r"\s*\|\|\s*'([^']+)'", source)
        if not match:
            raise ValueError(f'Cannot read {key} default from k6 config')
        env[key] = os.environ.get(key, match[1])
    if os.environ.get('BASE_URL', env['BASE_URL']).rstrip('/') != env['BASE_URL']:
        raise ValueError('Local runner requires BASE_URL=http://localhost:8080')
    durations = {k: seconds(v) for k, v in env.items()
                 if k in ['RAMP_UP', 'STEADY_DURATION', 'RAMP_DOWN', 'SETUP_TIMEOUT',
                          'HEARTBEAT_INTERVAL', 'POLL_INTERVAL']}
    return env, durations


def baseline(phase):
    command(['git', 'cat-file', '-e', BASELINE + '^{commit}'])
    committed = command(['git', 'diff', '--name-status', BASELINE + '..HEAD', '--', *PATHS])
    local = command(['git', 'status', '--porcelain', '--untracked-files=all', '--', *PATHS])
    if phase == 'm10b' and (committed or local):
        raise RuntimeError('M10B executable baseline differs (including local/untracked changes):\n' + committed + '\n' + local)
    return {'baseline': BASELINE, 'executable_committed_diff': committed, 'executable_local_state': local}


def preflight(config):
    for tool in ['git', 'curl', 'docker', 'k6', 'go', 'ps']:
        if not shutil.which(tool):
            raise RuntimeError(f'Missing required tool: {tool}')
    if platform.system() == 'Darwin':
        os.environ['JAVA_HOME'] = command(['/usr/libexec/java_home', '-v', '21'])
    if os.environ.get('JAVA_HOME'):
        os.environ['PATH'] = str(Path(os.environ['JAVA_HOME']) / 'bin') + os.pathsep + os.environ['PATH']
    if not shutil.which('java'):
        raise RuntimeError('Missing Java 21; set JAVA_HOME or PATH')
    java = command(['java', '-version'])
    if not re.search(r'version "21(?:\.|\")', java):
        raise RuntimeError('Java 21 is required; selected Java: ' + java)
    config.update(java=java, java_home=os.environ.get('JAVA_HOME'),
                  docker=command(['docker', 'version']), compose=command(COMPOSE + ['version']),
                  k6=command(['k6', 'version']), go=command(['go', 'version']))
    if not os.access(ROOT / 'mvnw', os.X_OK):
        raise RuntimeError('Maven wrapper is not executable')
    # Never reset a remote Docker daemon or let Compose environment redirect this run.
    context = command(['docker', 'context', 'show'])
    details = json.loads(command(['docker', 'context', 'inspect', context]))
    host = os.environ.get('DOCKER_HOST') or details[0]['Endpoints']['docker']['Host']
    if not host.startswith('unix://'):
        raise RuntimeError('Disposable local runner requires a unix:// Docker endpoint')
    config['docker_context'] = details
    overrides = [key for key in os.environ if key.startswith('K6_')]
    if overrides:
        raise RuntimeError('Unset k6 option overrides before running: ' + ', '.join(sorted(overrides)))
    for variable in ['COMPOSE_FILE', 'COMPOSE_PROJECT_NAME', 'COMPOSE_PROFILES', 'DOCKER_CONTEXT', 'DOCKER_HOST']:
        if os.environ.get(variable):
            raise RuntimeError(f'Unset {variable} before using the local runner')
    config['compose_resolved'] = command(COMPOSE + ['config'])
    if platform.system() == 'Darwin':
        config['cpu'] = command(['sysctl', '-n', 'machdep.cpu.brand_string'])
        config['ram'] = command(['sysctl', '-n', 'hw.memsize'])
    else:
        config['cpu'] = Path('/proc/cpuinfo').read_text() if Path('/proc/cpuinfo').exists() else platform.processor()
        config['ram'] = Path('/proc/meminfo').read_text() if Path('/proc/meminfo').exists() else 'unavailable'
    config['cpu_count'] = os.cpu_count()
    with socket.socket() as probe:
        if probe.connect_ex(('127.0.0.1', 8080)) == 0:
            raise RuntimeError('Port 8080 already in use; stop the existing backend before resetting DB')


def wait_ready(description, probe, timeout, backend=None):
    deadline = time.monotonic() + timeout
    last = ''
    while time.monotonic() < deadline:
        if backend is not None and backend.poll() is not None:
            raise RuntimeError('Backend exited during startup; inspect backend.log')
        try:
            if probe():
                return
        except (RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
            last = str(error)
        time.sleep(1)
    raise RuntimeError(f'Timed out waiting for {description}: {last}; inspect lifecycle.log/backend.log')


def backend_pid(group):
    rows = command(['ps', '-axo', 'pid=,pgid=,args=']).splitlines()
    for row in rows:
        fields = row.strip().split(None, 2)
        if len(fields) == 3 and fields[1] == str(group) and 'dev.heimdall.HeimdallApplication' in fields[2]:
            return int(fields[0])
    raise RuntimeError('Cannot identify owned Spring Boot JVM; refusing to sample Maven as backend')


class Monitor:
    def __init__(self, directory, config, backend, k6):
        self.directory, self.config = directory, config
        self.pids = {'backend': backend, 'k6': k6}
        self.done = threading.Event()
        self.thread = threading.Thread(target=self.run)
        self.errors, self.seen, self.peaks = set(), set(), {}

    def capture(self, args):
        try:
            return command(args, timeout=10)
        except Exception as error:
            self.errors.add(str(error))
            return ''

    def peak(self, name, value):
        self.peaks[name] = max(self.peaks.get(name, value), value)

    def run(self):
        try:
            self.sample_loop()
        except Exception as error:
            self.errors.add(f'Monitor stopped unexpectedly: {error}')

    def sample_loop(self):
        with (self.directory / 'resources.csv').open('w') as resource, (self.directory / 'metrics.csv').open('w') as metrics:
            rw, mw = csv.writer(resource), csv.writer(metrics)
            rw.writerow(['timestamp', 'source', 'pid', 'cpu_percent', 'memory_percent', 'rss_kib', 'docker_memory'])
            mw.writerow(['timestamp', 'series', 'value'])
            while not self.done.is_set():
                stamp = now()
                for name, pid in self.pids.items():
                    fields = self.capture(['ps', '-p', str(pid), '-o', '%cpu=,%mem=,rss=']).split()
                    if len(fields) == 3:
                        rw.writerow([stamp, name, pid, *fields, ''])
                        self.peak(name + '_cpu_percent', float(fields[0]))
                        self.peak(name + '_rss_kib', float(fields[2]))
                stats = self.capture(['docker', 'stats', '--no-stream', '--format', '{{json .}}',
                                      *['heimdall-' + x for x in ['postgres', 'kafka', 'prometheus', 'grafana']]])
                for line in stats.splitlines():
                    data = json.loads(line)
                    rw.writerow([stamp, data['Name'], '', data['CPUPerc'], data['MemPerc'], '', data['MemUsage']])
                    self.peak(data['Name'] + '_cpu_percent', float(data['CPUPerc'].rstrip('%')))
                raw = self.capture(['curl', '-fsS', '--max-time', '5', 'http://localhost:8080/actuator/prometheus'])
                with (self.directory / 'prometheus.txt').open('a') as handle:
                    handle.write(f'\n# sampled_at {stamp}\n{raw}\n')
                for line in raw.splitlines():
                    if not line or line.startswith('#'):
                        continue
                    match = re.match(r'([^ {]+)(\{.*\})?\s+(\S+)', line)
                    if match:
                        name, labels, value = match.groups()
                        self.seen.add(name)
                        if name.startswith(('hikaricp_', 'process_', 'system_', 'jvm_', 'heimdall_')):
                            mw.writerow([stamp, name + (labels or ''), value])
                        if name == 'hikaricp_connections_pending':
                            self.peak(name, float(value))
                for filename, query in [('postgres-activity.txt', ACTIVITY), ('postgres-tables.txt', TABLES)]:
                    if self.done.is_set():
                        break
                    output = self.capture(COMPOSE + SQL + ['-c', query])
                    with (self.directory / filename).open('a') as handle:
                        handle.write(f'\n{stamp}\n{output}\n')
                resource.flush()
                metrics.flush()
                self.done.wait(self.config['sample_interval'])

    def finish(self):
        self.done.set()
        self.thread.join()
        self.config.update(missing_metrics=sorted(set(METRICS) - self.seen),
                           monitor_errors=sorted(self.errors), resource_peaks=self.peaks)


def entry_source(config):
    """Instrumentation only: delegate unchanged functions/options to the existing script."""
    original = ROOT / 'load-tests/k6' / SCRIPTS[config['scenario']]
    source = ("import * as original from " + json.dumps(str(original)) + ";\n"
              "import execution from 'k6/execution';\n"
              "import { Gauge } from 'k6/metrics';\n"
              "const origin = new Gauge('m10_scenario_start_ms');\n"
              "const recorded = {};\n"
              "export const options = original.options;\n"
              "function mark(operation) {\n"
              "  if (!recorded[operation]) {\n"
              "    origin.add(execution.scenario.startTime, { operation });\n"
              "    recorded[operation] = true;\n"
              "  }\n"
              "}\n")
    scenario = config['scenario']
    if scenario != 'registration':
        source += "export function setup() { return original.setup(); }\n"
    if scenario == 'mixed':
        source += "export function heartbeat() { mark('heartbeat'); return original.heartbeat(); }\n"
        source += "export function poll() { mark('deployment_poll'); return original.poll(); }\n"
    else:
        operation = 'deployment_poll' if scenario == 'polling' else scenario
        source += f"export default function () {{ mark('{operation}'); return original.default(); }}\n"
    return source


def k6_command(config, directory):
    return ['k6', 'run', '--config', str(directory / 'k6-config.json'), '--summary-export', str(directory / 'k6-summary.json'),
            '--out', 'json=' + str(directory / 'k6-samples.json'),
            str(directory / 'entry.js')]


def run(args, label, run_id):
    env, durations = workload(args.profile, run_id)
    config = dict(phase=args.phase, profile=args.profile, scenario=args.scenario, label=label,
                  run_id=run_id, timestamp=now(), repository=str(ROOT), os=platform.platform(),
                  git_sha=command(['git', 'rev-parse', 'HEAD']),
                  git_status=command(['git', 'status', '--porcelain', '--untracked-files=all']),
                  workload=env, seconds=durations, sample_interval=args.sample_interval,
                  backend_command='./mvnw spring-boot:run')
    config.update(baseline(args.phase))
    parent = ROOT / 'load-tests/results' / args.phase
    directory = parent / (dt.datetime.now(dt.timezone.utc).strftime('%Y-%m-%dT%H%M%S') +
                          f'-{args.profile}-{args.scenario}-{label}-{uuid.uuid4().hex[:8]}')
    cmd = k6_command(config, directory)
    config['k6_command'] = shlex.join(['env', *[f'{k}={v}' for k, v in env.items()], *cmd])
    print(f"\n{args.phase.upper()} {args.profile}/{args.scenario}/{label}; RUN_ID={run_id}\nArtifacts: {directory}", flush=True)
    print('WARNING: disposable local Compose PostgreSQL volume WILL BE DELETED before this run.', flush=True)
    if args.dry_run:
        print('DRY RUN: no files, services, volumes or data changed. Runtime tool/Java checks deferred.')
        print(shlex.join(COMPOSE + ['down', '-v']))
        print(shlex.join(COMPOSE + ['up', '-d']))
        print('Wait: postgres pg_isready; start ./mvnw spring-boot:run; wait /actuator/health UP')
        print('Generate observation-only entry.js importing ' + SCRIPTS[config['scenario']] + ' (same options and workload functions)')
        print(config['k6_command'])
        print('Monitor owned backend/k6 PIDs, Docker resources, Prometheus/JVM/Hikari/app, PostgreSQL every', args.sample_interval, 'seconds')
        print('Validate vehicle/VIN counts; analyze; stop owned process groups; retain Compose and evidence.')
        return directory
    if not args.reset_disposable_db:
        raise RuntimeError('Reset not acknowledged. Add --reset-disposable-db to authorize deletion of the local Compose test volume.')
    directory.mkdir(parents=True, exist_ok=False)
    backend = load = monitor = None
    exit_code = 1
    try:
        preflight(config)
        (directory / 'entry.js').write_text(entry_source(config))
        (directory / 'k6-config.json').write_text('{}\n')
        config['k6_inspect'] = command(['k6', 'inspect', '--execution-requirements', str(directory / 'entry.js')], env={**os.environ, **env})
        # Atomic namespace reservation, shared by M10B and M10D; leading zero aliases collide.
        reservations = ROOT / 'load-tests/results/.m10-run-ids'
        reservations.mkdir(exist_ok=True)
        try:
            (reservations / str(int(run_id))).mkdir()
        except FileExistsError:
            raise RuntimeError(f'RUN_ID {run_id} was already reserved by automation; choose a fresh ID')
        (reservations / str(int(run_id)) / 'artifact.txt').write_text(str(directory) + '\n')
        with (directory / 'lifecycle.log').open('w') as log:
            for action in [['down', '-v'], ['up', '-d']]:
                log.write(command(COMPOSE + action, timeout=180) + '\n')
                log.flush()
        wait_ready('PostgreSQL', lambda: 'accepting connections' in command(
            COMPOSE + ['exec', '-T', 'postgres', 'pg_isready', '-U', 'heimdall', '-d', 'heimdall'], timeout=10), args.startup_timeout)
        with (directory / 'backend.log').open('w') as log:
            backend = subprocess.Popen(['./mvnw', 'spring-boot:run'], cwd=ROOT, stdout=log,
                                       stderr=subprocess.STDOUT, start_new_session=True)
        wait_ready('backend UP', lambda: json.loads(command(['curl', '-fsS', '--max-time', '3',
                   'http://localhost:8080/actuator/health']))['status'] == 'UP', args.startup_timeout, backend)
        pid = backend_pid(backend.pid)
        config.update(backend_launcher_pid=backend.pid, backend_jvm_pid=pid)
        # Readiness of all observation endpoints, never fixed startup sleeps.
        for url in ['http://localhost:8080/actuator/prometheus', 'http://localhost:9090/-/ready',
                    'http://localhost:3000/api/health']:
            wait_ready(url, lambda url=url: bool(command(['curl', '-fsS', '--max-time', '3', url])), args.startup_timeout, backend)
        with (directory / 'k6.log').open('w') as log:
            load = subprocess.Popen(cmd, cwd=ROOT, env={**os.environ, **env}, stdout=log,
                                    stderr=subprocess.STDOUT, start_new_session=True)
        config['k6_pid'] = load.pid
        monitor = Monitor(directory, config, pid, load.pid)
        monitor.thread.start()
        config['workload_started_at'] = now()
        (directory / 'metadata.json').write_text(json.dumps(config, indent=2) + '\n')
        deadline = time.monotonic() + sum(durations[k] for k in ['SETUP_TIMEOUT', 'RAMP_UP', 'STEADY_DURATION', 'RAMP_DOWN']) + 120
        while load.poll() is None:
            if backend.poll() is not None:
                raise RuntimeError('Backend exited during workload')
            if time.monotonic() >= deadline:
                raise RuntimeError('k6 exceeded configured workload duration plus 120s grace')
            time.sleep(.25)
        config['k6_exit_code'] = load.returncode
        monitor.finish()
        monitor = None
        counts = command(COMPOSE + SQL + ['-At', '-c', 'SELECT count(*), count(DISTINCT vin) FROM vehicle;'])
        actual = [int(value) for value in counts.split('|')]
        config['database'] = dict(vehicle_count=actual[0], unique_vins=actual[1], expected=int(args.profile),
                                  status='PASS' if actual == [int(args.profile)] * 2 else 'FAIL')
        (directory / 'database.txt').write_text(counts + '\n')
        (directory / 'kafka-consumer-groups.txt').write_text(command(COMPOSE + [
            'exec', '-T', 'kafka', '/opt/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server',
            'localhost:9092', '--all-groups', '--describe'], check=False))
        exit_code = 0 if load.returncode == 0 and config['database']['status'] == 'PASS' else 1
    except BaseException as error:
        config['error'] = f'{type(error).__name__}: {error}'
        print(config['error'], file=sys.stderr)
    finally:
        # A second Ctrl+C must not interrupt reaping owned children.
        handlers = {sig: signal.signal(sig, signal.SIG_IGN) for sig in (signal.SIGINT, signal.SIGTERM)}
        if load is not None:
            stop(load)
        if monitor is not None:
            monitor.finish()
        if backend is not None:
            stop(backend)
        config['finished_at'] = now()
        config['runner_exit_code'] = exit_code
        (directory / 'metadata.json').write_text(json.dumps(config, indent=2) + '\n')
        write_report(directory, config)
        print(f'Evidence retained: {directory}', flush=True)
        for sig, handler in handlers.items():
            signal.signal(sig, handler)
    if exit_code:
        raise RuntimeError('Run failed; suite stopped. Inspect retained report and logs.')
    return directory


def comparison(paths, output):
    lines = ['# Scenario comparison', '', 'Warmup is excluded from measured variation.', '',
             '| Run | Operation | Steady RPS | p95 ms | p99 ms | Failures (all) |',
             '|---|---|---:|---:|---:|---:|']
    measured = {}
    for path in paths:
        result = json.loads((path / 'analysis.json').read_text())
        config = json.loads((path / 'metadata.json').read_text())
        for name, entry in result['operations'].items():
            if name not in ('registration', 'heartbeat', 'deployment_poll') or not entry['all']['requests']:
                continue
            stats = entry.get('steady', entry['all'])
            rps = entry.get('observed_rps')
            lines.append(f"| [{config['label']}]({path.name}/report.md) | {name} | {rps if rps is not None else 'N/A'} | {stats['p95_ms']} | {stats['p99_ms']} | {entry['all']['failures']} |")
            if config['label'] != 'warmup':
                measured.setdefault(name, []).append({'rps': rps, 'p95': stats['p95_ms'], 'p99': stats['p99_ms']})
    lines.extend(['', 'Measured variation: absolute difference / mean × 100 (two measured runs).'])
    for name, rows in measured.items():
        for metric in ['rps', 'p95', 'p99']:
            values = [row[metric] for row in rows if row[metric] is not None]
            variation = 100 * abs(values[1] - values[0]) / (sum(values) / 2) if len(values) == 2 and sum(values) else None
            lines.append(f'- {name} {metric}: {variation if variation is not None else "N/A"}%')
    lines.extend(['', 'Review status: REVIEW_REQUIRED. Classification: INCONCLUSIVE.',
                  'Stop here; review evidence before explicitly selecting another fleet profile.'])
    output.write_text('\n'.join(lines) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__, epilog='Real runs require --reset-disposable-db. Dry runs never start Docker or workloads.')
    parser.add_argument('--profile', required=True, choices=['100', '1000', '5000'])
    parser.add_argument('--scenario', required=True, choices=SCRIPTS)
    parser.add_argument('--phase', choices=['m10b', 'm10d'], default='m10b')
    parser.add_argument('--label', default='run1')
    parser.add_argument('--run-id')
    parser.add_argument('--run-id-base')
    parser.add_argument('--suite', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--reset-disposable-db', action='store_true')
    parser.add_argument('--sample-interval', type=float, default=15)
    parser.add_argument('--startup-timeout', type=float, default=180)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]+', args.label):
        parser.error('label must contain only letters, digits, underscores or hyphens')
    if not 0 < args.sample_interval <= 3600 or not 0 < args.startup_timeout <= 3600:
        parser.error('sample interval and startup timeout must be >0 and <=3600 seconds')
    value = args.run_id_base if args.suite else args.run_id
    if not value or not re.fullmatch(r'\d{1,4}', value):
        parser.error('provide --run-id-base (suite) or --run-id with 1–4 digits')
    if (args.suite and args.run_id) or (not args.suite and args.run_id_base):
        parser.error('use only the run ID option applicable to this command')
    if args.suite and int(value) > 9997:
        parser.error('suite run-id-base must leave room for two more IDs (<=9997)')
    # Validate all workload overrides before creating anything.
    workload(args.profile, value)
    def interrupted(signum, frame):
        raise KeyboardInterrupt(f'signal {signum}')
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    lock = None
    try:
        if not args.dry_run:
            lock_path = ROOT / 'load-tests/results/.m10-runner.lock'
            lock = lock_path.open('a')
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise RuntimeError('Another local runner is active; refusing concurrent Compose reset')
        labels = ['warmup', 'run1', 'run2'] if args.suite else [args.label]
        if not args.dry_run:
            for i in range(len(labels)):
                reserved = ROOT / 'load-tests/results/.m10-run-ids' / str(int(value) + i)
                if reserved.exists():
                    raise RuntimeError(f'RUN_ID {int(value) + i} already reserved; choose fresh IDs before starting')
        paths = [run(args, label, str(int(value) + i)) for i, label in enumerate(labels)]
        if args.suite and not args.dry_run:
            output = paths[0].parent / ('scenario-' + paths[0].name + '.md')
            comparison(paths, output)
            print(f'Comparison: {output}')
    finally:
        if lock:
            lock.close()


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, OSError, KeyboardInterrupt) as error:
        print(f'ERROR: {error}', file=sys.stderr)
        sys.exit(1)
