#!/usr/bin/env python3
"""Read-only live checks for the M13 100-vehicle failed-canary scenario."""
import collections
import json
import sys
import urllib.request

base = (sys.argv[1] if len(sys.argv) > 1 else 'http://localhost:8081').rstrip('/')


def get(path):
    with urllib.request.urlopen(base + '/api/v1' + path, timeout=20) as response:
        return json.load(response)


fleet = get('/vehicles')
assert len(fleet) == 100, 'Expected the real 100-vehicle fixture'
assert len({v['id'] for v in fleet}) == 100
mix = collections.Counter(v['softwareVersion'] for v in fleet)
connectivity = collections.Counter(v['connectivityStatus'] for v in fleet)
assert sum(mix.values()) == 100
assert len(mix) >= 2, 'Expected multiple real installed software versions'
all_deployments = get('/deployments')
rollout = next(r for r in get('/rollouts') if r['targetSoftwareVersion'] == '2.1.0' and r['status'] == 'PAUSED')
stages = sorted(get('/rollouts/' + rollout['id'] + '/stages'), key=lambda s: s['stageIndex'])
assert [s['targetPercentage'] for s in stages] == [5, 25, 50, 100]
assert [s['targetVehicleCount'] for s in stages] == [5, 25, 50, 100]
assert stages[0]['status'] == 'FAILED'
assert all(s['status'] == 'PENDING' and s['deploymentCount'] == 0 for s in stages[1:])
linked = [d for d in all_deployments if d['rolloutId'] == rollout['id']]
assert len(linked) == 5
assert all(d['rolloutStageId'] == stages[0]['id'] for d in linked)
failed = next(d for d in linked if d['status'] == 'FAILED')
assert failed['vin'] == '7FC00000000000003'
assert failed['sourceSoftwareVersion'] == '1.0.0'
assert failed['targetSoftwareVersion'] == '2.1.0'
assert failed['failureReason'] == 'simulated installation failure'
events = get('/deployments/' + failed['id'] + '/events')
assert [e['toStatus'] for e in events] == ['PENDING', 'DOWNLOADING', 'DOWNLOADED', 'INSTALLING', 'FAILED']
assert [e['createdAt'] for e in events] == sorted(e['createdAt'] for e in events)
assert stages[0]['deploymentCount'] == 5 and stages[0]['installedCount'] == 4 and stages[0]['failedCount'] == 1
original_ids = {d['id'] for d in linked if d['status'] == 'INSTALLED'}
recovery = [d for d in all_deployments if d['rollbackOfDeploymentId'] in original_ids]
assert len(recovery) == 4 and all(d['status'] == 'INSTALLED' for d in recovery)
assert all(d['sourceSoftwareVersion'] == '2.1.0' and d['targetSoftwareVersion'] == '1.0.0' for d in recovery)
print(json.dumps({'vehicles': len(fleet), 'connectivity': dict(connectivity), 'softwareDistribution': dict(mix),
                  'canary': {'deployed': 5, 'installed': 4, 'failed': 1, 'failureRatePercent': 20},
                  'laterStagesPending': True, 'failedTimelineEvents': len(events),
                  'automaticRollbacksInstalled': len(recovery), 'result': 'PASS'}, indent=2))
