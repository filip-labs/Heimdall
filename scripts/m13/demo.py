#!/usr/bin/env python3
"""Repeatable real-API dashboard fixture. Run only against an empty disposable backend.

Requires the already-built simulator, backend signing public key/key ID in env,
and a running backend. Credentials remain exclusively in the simulator's memory.
This script never deletes backend records or changes database state directly.
"""
import argparse
import hashlib
import http.server
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import threading
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api', default='http://localhost:8081')
    parser.add_argument('--simulator', required=True)
    parser.add_argument('--scenario', action='store_true', help='Also launch the deterministic failed canary and await automatic recovery')
    parser.add_argument('--artifact-port', type=int, default=8099)
    args = parser.parse_args()
    api = args.api.rstrip('/')

    def request(path, body=None):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(api + '/api/v1' + path, data=data,
                                     headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=20) as response:
            return json.load(response)

    def wait_for(check, description, timeout=180):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            result = check()
            if result:
                return result
            time.sleep(1)
        raise RuntimeError('Timed out waiting for ' + description)

    if any(request('/' + resource) for resource in ['vehicles', 'releases', 'rollouts', 'deployments']):
        raise SystemExit('Refusing to seed a nonempty backend. Use a new disposable database; no records were deleted.')
    if not os.environ.get('HEIMDALL_OTA_SIGNING_PUBLIC_KEY'):
        raise SystemExit('Export the backend-matching OTA public key and key identifier first.')

    artifact = b'Heimdall M13 deterministic OTA artifact\n'

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != '/artifact.bin':
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header('Content-Type', 'application/octet-stream')
            self.send_header('Content-Length', str(len(artifact)))
            self.end_headers()
            self.wfile.write(artifact)

        def log_message(self, *_):
            pass

    server = http.server.ThreadingHTTPServer(('127.0.0.1', args.artifact_port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    catalog = {}
    for version in ['1.0.0', '2.0.0', '2.1.0', '2.2.0']:
        catalog[version] = request('/releases', {'version': version,
            'description': 'M13 disposable signed dashboard demo artifact',
            'artifactUrl': f'http://localhost:{args.artifact_port}/artifact.bin',
            'checksum': hashlib.sha256(artifact).hexdigest()})
    env = {**os.environ, 'HEIMDALL_URL': api, 'FLEET_SIZE': '100',
           'INITIAL_SOFTWARE_VERSION': '1.0.0', 'HEARTBEAT_INTERVAL': '5s',
           'DEPLOYMENT_POLL_INTERVAL': '3s',
           'SIMULATED_FAILURE_VINS': '7FC00000000000003'}
    # Do not pass the backend's private signing key or token secret to the simulator.
    env.pop('HEIMDALL_SECURITY_RELEASE_SIGNING_PRIVATE_KEY', None)
    env.pop('HEIMDALL_SECURITY_VEHICLE_TOKEN_SECRET', None)
    with tempfile.TemporaryDirectory(prefix='heimdall-m13-demo-') as temp:
        log = open(Path(temp) / 'simulator.log', 'w')
        simulator = subprocess.Popen([args.simulator], env=env, stdout=log, stderr=log)
        try:
            fleet = wait_for(lambda: (v if len(v := request('/vehicles')) == 100 and
                             all(x['connectivityStatus'] == 'ONLINE' for x in v) else None), '100 online vehicles')
            fleet.sort(key=lambda v: v['vin'])
            for v in fleet[30:65]:
                request('/deployments', {'vehicleId': v['id'], 'releaseId': catalog['2.0.0']['id']})
            wait_for(lambda: sum(v['softwareVersion'] == '2.0.0' for v in request('/vehicles')) == 35,
                     '35 real signed installations')
            summary = {'api': api, 'vehicles': 100, 'online': 100,
                       'distribution': {'1.0.0': 65, '2.0.0': 35},
                       'releases': {version: r['id'] for version, r in catalog.items()},
                       'failureVin': '7FC00000000000003'}
            if args.scenario:
                rollout = request('/rollouts', {'releaseId': catalog['2.1.0']['id'],
                    'stages': [5, 25, 50, 100], 'failureThresholdPercent': 5,
                    'automaticRollbackEnabled': True})
                wait_for(lambda: request('/rollouts/' + rollout['id'])['status'] == 'PAUSED', 'failed canary pause')
                def recovered():
                    items = request('/deployments')
                    originals = {d['id'] for d in items if d['rolloutId'] == rollout['id'] and d['status'] == 'INSTALLED'}
                    recoveries = [d for d in items if d['rollbackOfDeploymentId'] in originals]
                    return len(recoveries) == 4 and all(d['status'] == 'INSTALLED' for d in recoveries)
                wait_for(recovered, 'four automatic rollback installs')
                summary['failedRolloutId'] = rollout['id']
                summary['scenario'] = 'PAUSED at 20% failure, four automatic rollbacks installed'
            print(json.dumps(summary, indent=2), flush=True)
            print('READY: inspect 2.1.0 or, without --scenario, plan 2.1.0 with stages 5,25,50,100 / threshold 5 / automatic rollback enabled.', flush=True)
            print('VIN 003 fails deterministically; canary pauses at 20%; four successful installs roll back.', flush=True)
            print('Simulator and artifact server remain running. Ctrl-C stops both; backend data remains.', flush=True)
            signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
            while simulator.poll() is None:
                time.sleep(1)
            raise RuntimeError('Simulator exited before the demo was stopped')
        except KeyboardInterrupt:
            pass
        finally:
            simulator.send_signal(signal.SIGTERM)
            try:
                simulator.wait(timeout=15)
            except subprocess.TimeoutExpired:
                simulator.kill()
                simulator.wait()
            server.shutdown()
            log.close()


if __name__ == '__main__':
    main()
