#!/usr/bin/env python3
"""Collect low-cardinality runtime evidence during an M10C diagnostic run."""
import argparse
import datetime as dt
import json
import os
import re
import select
import signal
import sys
import tempfile
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


def output_text(value):
    if value is None:
        return ''
    return value.decode('utf-8', errors='replace') if isinstance(value, bytes) else str(value)


def run_command(args, timeout=10):
    evidence = {'command': args, 'timeout_seconds': timeout}
    try:
        result = subprocess.run(args, text=True, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        evidence.update(ok=False, timed_out=True, error_type=type(error).__name__,
                        error=f'command timed out after {timeout} seconds',
                        stdout=output_text(error.stdout).strip(),
                        stderr=output_text(error.stderr).strip())
        return evidence
    except OSError as error:
        evidence.update(ok=False, timed_out=False, error_type=type(error).__name__,
                        error=str(error), stdout='', stderr='')
        return evidence

    evidence.update(ok=result.returncode == 0, timed_out=False,
                    returncode=result.returncode, stdout=result.stdout.strip(),
                    stderr=result.stderr.strip())
    if result.returncode:
        evidence['error'] = f'command exited with status {result.returncode}'
    return evidence


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


# Names verified against Tomcat 11.0.24's NioEndpoint MBean descriptor; the
# helper still discovers MBeanInfo on each sample and never fabricates defaults.
JMX_FIELDS = {
    'tomcat_jmx_connection_count': 'connectionCount',
    'tomcat_jmx_max_connections': 'maxConnections',
    'tomcat_jmx_keep_alive_count': 'keepAliveCount',
    'tomcat_jmx_current_threads': 'currentThreadCount',
    'tomcat_jmx_busy_threads': 'currentThreadsBusy',
    'tomcat_jmx_max_threads': 'maxThreads',
}
APPLICATION_MAIN = 'dev.heimdall.HeimdallApplication'


def jmx_unavailable(error, **metadata):
    return {**dict.fromkeys(JMX_FIELDS), 'tomcat_jmx': {
        **metadata, 'ok': False, 'error': error,
    }}


def backend_candidates(output):
    candidates = []
    for line in output.splitlines():
        # ps does not quote argv: only accept an exact Java executable suffix and
        # a standalone main class, never a Maven -Dspring-boot.run.main-class value.
        match = re.match(r'^\s*(\d+)\s+(.*?\bjava)\s+(.+)$', line)
        if not match or Path(match[2]).name != 'java':
            continue
        tokens = match[3].split()
        if APPLICATION_MAIN in tokens and not any(
                token in tokens for token in ('-jar', 'org.codehaus.plexus.classworlds.launcher.Launcher')):
            candidates.append({'pid': int(match[1]), 'main': APPLICATION_MAIN})
    return candidates


def normalize_jmx(payload, port=8080):
    data = json.loads(payload)
    if not isinstance(data, dict) or type(data.get('ok')) is not bool:
        raise ValueError('Malformed JMX response envelope')
    if not data['ok']:
        return jmx_unavailable(data.get('error', 'JMX helper failed'), helper=data)
    beans = data.get('mbeans')
    if not isinstance(beans, list) or any(not isinstance(b, dict) or
            not isinstance(b.get('attributes'), dict) or not isinstance(b.get('keys'), dict)
            or not isinstance(b.get('object_name'), str) for b in beans):
        raise ValueError('Malformed MBean list')
    # Each ThreadPool is one endpoint. Avoid joining distinct connectors by port
    # (two interfaces/protocols can share a port). Keep all candidates as evidence.
    candidates = []
    for bean in beans:
        if bean.get('type') != 'ThreadPool':
            continue
        attributes = bean['attributes']
        local_port = attributes.get('localPort')
        source = 'localPort'
        if local_port is None:
            local_port = attributes.get('port')
            source = 'port'
        if local_port is None:
            name = bean['keys'].get('name', '')
            match = re.fullmatch(r'(?:https?|ajp)-.+-(\d+)', name)
            local_port = int(match[1]) if match else None
            source = 'ObjectName.name suffix'
        candidates.append({**bean, 'selected_port': local_port, 'port_source': source})
    matching = [b for b in candidates if str(b['selected_port']) == str(port)]
    metadata = {'helper': data, 'candidates': candidates, 'requested_port': port}
    if len(matching) != 1:
        return jmx_unavailable('Ambiguous Tomcat connector' if matching else
                               'No matching Tomcat ThreadPool MBean', **metadata)
    selected = matching[0]
    result = dict.fromkeys(JMX_FIELDS)
    unavailable = []
    for field, attribute in JMX_FIELDS.items():
        value = selected['attributes'].get(attribute)
        # Negative sentinel values (e.g. external executor/unlimited) are not counts.
        if type(value) is int and value >= 0:
            result[field] = value
        else:
            unavailable.append(attribute)
    if len(unavailable) == len(JMX_FIELDS):
        return jmx_unavailable('No readable Tomcat metrics', selected=selected, **metadata)
    result['tomcat_jmx'] = {**metadata, 'ok': True, 'selected': selected,
                            'unavailable_attributes': unavailable}
    return result


class JmxProbe:
    """One source-launched JDK helper/connection; hard pipe deadlines on macOS/Linux."""
    def __init__(self, port=8080, startup_timeout=15, sample_timeout=3, retry_seconds=60):
        self.port = port
        self.startup_timeout = startup_timeout
        self.sample_timeout = sample_timeout
        self.retry_seconds = retry_seconds
        self.process = None
        self.stderr = None
        self.pid = None
        self.next_retry = 0
        self.last_error = None
        self.starts = 0
        self.buffer = b''

    def close(self):
        if self.process is not None:
            process, self.process = self.process, None
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=1)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=1)
            process.stdin.close()
            process.stdout.close()
        if self.stderr is not None:
            self.stderr.close()
            self.stderr = None
        self.buffer = b''

    def start(self, pid):
        self.pid = pid
        java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
        self.stderr = tempfile.TemporaryFile()
        self.process = subprocess.Popen([
            java, '--add-modules', 'jdk.attach',
            str(Path(__file__).with_name('TomcatJmxProbe.java')), str(pid), APPLICATION_MAIN,
        ], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.stderr, bufsize=0)
        os.set_blocking(self.process.stdout.fileno(), False)
        self.starts += 1

    def exchange(self, timeout):
        self.process.stdin.write(b'sample\n')
        deadline = time.monotonic() + timeout
        while b'\n' not in self.buffer:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not select.select([self.process.stdout], [], [], remaining)[0]:
                raise TimeoutError(f'JMX helper timed out after {timeout}s')
            chunk = os.read(self.process.stdout.fileno(), 65536)
            if not chunk:
                code = self.process.wait(timeout=1)
                raise RuntimeError(f'JMX helper exited with status {code}')
            self.buffer += chunk
            if len(self.buffer) > 1024 * 1024:
                raise ValueError('JMX helper response exceeds 1 MiB')
        line, self.buffer = self.buffer.split(b'\n', 1)
        return line.decode('utf-8')

    def sample(self):
        started = time.monotonic()
        metadata = {'sampled_at': utc_now(), 'requested_port': self.port,
                    'startup_timeout_seconds': self.startup_timeout,
                    'sample_timeout_seconds': self.sample_timeout}
        try:
            if started < self.next_retry:
                return jmx_unavailable(self.last_error, retry_in_seconds=round(self.next_retry - started, 3),
                                       selected_pid=self.pid, helper_starts=self.starts, **metadata)
            discovery = run_command(['ps', '-ww', '-Ao', 'pid=,args='], timeout=3)
            if not discovery['ok']:
                raise RuntimeError('PID discovery failed: ' + discovery['error'])
            candidates = backend_candidates(discovery['stdout'])
            metadata['pid_candidates'] = candidates
            if len(candidates) != 1:
                raise RuntimeError('Heimdall JVM unavailable' if not candidates else 'Ambiguous Heimdall JVMs')
            pid = candidates[0]['pid']
            metadata['selected_pid'] = pid
            if self.process is not None and (self.pid != pid or self.process.poll() is not None):
                self.close()
            fresh = self.process is None
            if fresh:
                self.start(pid)
            result = normalize_jmx(self.exchange(self.startup_timeout if fresh else self.sample_timeout), self.port)
            if result['tomcat_jmx']['ok'] and result['tomcat_jmx']['helper'].get('pid') != pid:
                raise ValueError('JMX helper PID mismatch')
            if not result['tomcat_jmx']['ok'] and not result['tomcat_jmx'].get('helper', {}).get('ok'):
                self.last_error = result['tomcat_jmx']['error']
                self.next_retry = time.monotonic() + self.retry_seconds
                self.close()
        except Exception as error:
            self.last_error = f'{type(error).__name__}: {error}'
            if self.stderr is not None:
                self.stderr.seek(0)
                metadata['helper_stderr'] = self.stderr.read(8192).decode('utf-8', errors='replace')
            self.close()
            self.next_retry = time.monotonic() + self.retry_seconds
            result = jmx_unavailable(self.last_error)
        result['tomcat_jmx'].update(metadata, helper_starts=self.starts,
                                    duration_seconds=round(time.monotonic() - started, 4))
        if not result['tomcat_jmx']['ok']:
            print('Tomcat JMX: ' + result['tomcat_jmx']['error'], file=sys.stderr, flush=True)
        return result


def sample(probe=None):
    entry = {'timestamp': utc_now()}
    try:
        entry.update(probe.sample() if probe is not None else jmx_unavailable('JMX probe not configured'))
    except Exception as error:
        # Even an unexpected probe/cleanup failure must not suppress other evidence.
        entry.update(jmx_unavailable(f'{type(error).__name__}: {error}'))
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
    processes = run_command(['ps', '-Ao', 'pid,ppid,%cpu,%mem,rss,command'])
    processes['stdout'] = relevant_processes(processes['stdout'])
    entry['processes'] = processes
    return entry


def safe_sample(probe=None):
    try:
        return sample(probe) if probe is not None else sample()
    except Exception as error:  # one unexpected observation must not end the collector
        return {**jmx_unavailable('Unexpected collector failure'),
                'timestamp': utc_now(), 'collector_error_type': type(error).__name__,
                'collector_error': str(error)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    parser.add_argument('--jmx-once', action='store_true', help='Read JMX only; no workload')
    parser.add_argument('--jmx-port', type=int, default=8080)
    parser.add_argument('--interval', type=float, default=5)
    args = parser.parse_args()
    if args.interval <= 0:
        parser.error('--interval must be greater than zero')
    if not 1 <= args.jmx_port <= 65535:
        parser.error('--jmx-port must be between 1 and 65535')
    probe = JmxProbe(port=args.jmx_port)
    if args.jmx_once:
        try:
            result = probe.sample()
            print(json.dumps(result, indent=2))
            return 0 if result['tomcat_jmx']['ok'] and all(
                result[f] is not None for f in JMX_FIELDS if f != 'tomcat_jmx_keep_alive_count') else 1
        finally:
            probe.close()
    if args.output is None:
        parser.error('--output is required unless --jmx-once is used')
    def stop(signum, _frame):
        raise SystemExit(128 + signum)
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        with args.output.open('a', buffering=1) as output:
            while True:
                output.write(json.dumps(safe_sample(probe), separators=(',', ':')) + '\n')
                time.sleep(args.interval)
    finally:
        probe.close()


if __name__ == '__main__':
    sys.exit(main())
