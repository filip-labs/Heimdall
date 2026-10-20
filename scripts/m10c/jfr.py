#!/usr/bin/env python3
"""Bounded JDK-only JFR control; never starts a backend or workload."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from collect import APPLICATION_MAIN, select_java21, utc_now


def save(directory, status):
    temporary = directory / 'jfr-status.json.tmp'
    temporary.write_text(json.dumps(status, indent=2) + '\n')
    temporary.replace(directory / 'jfr-status.json')


def command(status, directory, log, *arguments):
    args = [status['jcmd_executable'], str(status['target_pid']), *arguments]
    evidence = {'command': args, 'started_at': utc_now(), 'timeout_seconds': 15}
    try:
        result = subprocess.run(args, text=True, capture_output=True, timeout=15)
        evidence.update(returncode=result.returncode, stdout=result.stdout, stderr=result.stderr)
    except (OSError, subprocess.TimeoutExpired) as error:
        evidence.update(error=f'{type(error).__name__}: {error}')
    evidence['finished_at'] = utc_now()
    with (directory / log).open('a') as output:
        output.write(json.dumps(evidence) + '\n')
    if 'error' in evidence or evidence['returncode'] != 0:
        raise RuntimeError(f'{arguments[0]} failed: {evidence}')
    return evidence['stdout'] + evidence['stderr']


def file_state(status):
    path = Path(status['filename'])
    status['file_exists'] = path.is_file()
    status['file_size_bytes'] = path.stat().st_size if path.is_file() else 0


def start(directory):
    directory = directory.resolve()
    status = {'recording_name': 'M10C5104', 'settings': 'profile', 'delay': '5m',
              'duration': '10m', 'disk': True,
              'filename': str(directory / 'm10c-5104-steady.jfr'),
              'scheduling_ok': False, 'scheduling_requested_at': utc_now()}
    try:
        if (directory / 'jfr-status.json').exists():
            raise RuntimeError('JFR control evidence already exists; use a fresh result directory')
        preflight = json.loads((directory / 'preflight.json').read_text())
        if not preflight['preflight']['ok'] or not preflight['tomcat_jmx']['ok']:
            raise RuntimeError('Successful JMX preflight is required')
        pid = preflight['tomcat_jmx']['selected_pid']
        if type(pid) is not int or pid <= 0:
            raise RuntimeError('Invalid preflight target PID')
        status['target_pid'] = pid
        jdk = select_java21(preflight['tomcat_jmx']['jdk']['java_home'])
        status['jdk'] = jdk
        status['java_feature_version'] = jdk['java_feature_version']
        status['jcmd_executable'] = str(Path(jdk['java_home']) / 'bin/jcmd')
        if not os.access(status['jcmd_executable'], os.X_OK):
            raise RuntimeError('Verified Java 21 JDK has no executable jcmd')
        if Path(status['filename']).exists():
            raise RuntimeError('JFR result file already exists; refusing to replace it')
        # Revalidate the preflight PID immediately before scheduling; no hardcoded PID.
        version = command(status, directory, 'jfr-start.log', 'VM.version')
        if not re.search(r'^JDK 21(?:[.\s]|$)', version, re.M):
            raise RuntimeError('Target JVM is not Java 21')
        identity = command(status, directory, 'jfr-start.log', 'VM.command_line')
        main = re.search(r'^java_command:\s*(\S+)', identity, re.M)
        if not main or main[1] != APPLICATION_MAIN:
            raise RuntimeError('Target PID is no longer the Heimdall application main')
        before = command(status, directory, 'jfr-check-before.log', 'JFR.check')
        if re.search(r'\bname=' + re.escape(status['recording_name']) + r'(?:\s|$)', before):
            raise RuntimeError('Recording M10C5104 already exists; refusing duplicate')
        status['start_command_at'] = utc_now()
        output = command(status, directory, 'jfr-start.log', 'JFR.start',
                         'name=M10C5104', 'settings=profile', 'delay=5m', 'duration=10m',
                         'disk=true', 'filename=' + json.dumps(status['filename']))
        status['start_command_returned_at'] = utc_now()
        status['start_response'] = output
        accepted = re.search(r'^Recording (\d+) scheduled to start in ', output, re.M)
        if not accepted:
            raise RuntimeError('JFR.start did not acknowledge a scheduled recording')
        status['recording_id'] = int(accepted[1])
        # jcmd may report a diagnostic error with a zero process exit code.
        check = command(status, directory, 'jfr-check-before.log', 'JFR.check', 'name=M10C5104')
        pattern = r'^Recording ' + accepted[1] + r': name=M10C5104\s.*\((?:delayed|running)\)'
        if not re.search(pattern, check, re.M):
            raise RuntimeError('JFR did not confirm the named scheduled recording')
        status['scheduling_ok'] = True
        status['scheduling_confirmed_at'] = utc_now()
        file_state(status)
        save(directory, status)
        return 0
    except Exception as error:
        status['error'] = f'{type(error).__name__}: {error}'
        file_state(status)
        # Never overwrite existing control evidence on repeated invocation.
        if not (directory / 'jfr-status.json').exists():
            save(directory, status)
        print('M10C JFR scheduling failed; do not start k6: ' + status['error'], file=sys.stderr)
        return 1


def update(directory, mode):
    status = json.loads((directory / 'jfr-status.json').read_text())
    if mode == 'launch':
        if not status['scheduling_ok']:
            raise RuntimeError('JFR scheduling did not succeed')
        status['k6_launch_requested_at'] = utc_now()
    else:
        status['final_check_at'] = utc_now()
        try:
            command(status, directory, 'jfr-check-after.log', 'JFR.check')
        except Exception as error:
            status['final_check_error'] = str(error)
        file_state(status)
        status['recording_file_ok'] = status['file_exists'] and status['file_size_bytes'] > 0
    save(directory, status)
    if mode == 'finish' and not status['recording_file_ok']:
        print('M10C JFR file missing/empty; see jfr-status.json. k6 status is preserved.', file=sys.stderr)
        return 1
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=('start', 'launch', 'finish'))
    parser.add_argument('--result-dir', type=Path, required=True)
    args = parser.parse_args()
    try:
        return start(args.result_dir) if args.mode == 'start' else update(args.result_dir.resolve(), args.mode)
    except Exception as error:
        print(f'M10C JFR {args.mode} failed: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
