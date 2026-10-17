import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import analyze
import runner


def point(metric, time, value, operation=None, status='200'):
    if metric == 'origin':
        metric, value, operation = 'm10_scenario_start_ms', (1767225600 + time) * 1000, operation or 'heartbeat'
    return json.dumps({'type': 'Point', 'metric': metric, 'data': {
        'time': f'2026-01-01T00:{time // 60:02d}:{time % 60:02d}Z', 'value': value,
        'tags': {'operation': operation, 'status': status}}})


class AnalysisTests(unittest.TestCase):
    def analyze(self, rows, scenario='heartbeat'):
        config = {'profile': '100', 'scenario': scenario, 'seconds': {
            'RAMP_UP': 30, 'STEADY_DURATION': 120, 'HEARTBEAT_INTERVAL': 15, 'POLL_INTERVAL': 10}}
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'samples.json'
            path.write_text('\n'.join(rows))
            return analyze.analyze(path, config)

    def test_percentiles(self):
        self.assertEqual(analyze.percentile([40, 10, 30, 20], .5), 25)
        self.assertAlmostEqual(analyze.percentile([10, 20, 30, 40], .95), 38.5)
        self.assertAlmostEqual(analyze.percentile([10, 20, 30, 40], .99), 39.7)
        self.assertIsNone(analyze.percentile([], .5))

    def test_operation_filtering_and_steady_boundaries(self):
        result = self.analyze([point('origin', 20, 20000),
                               point('http_req_duration', 10, 900, 'bootstrap', '201'),
                               point('http_req_duration', 49, 700, 'vehicle_resolve'),
                               point('http_req_duration', 49, 100, 'heartbeat'),
                               point('http_req_duration', 50, 10, 'heartbeat'),
                               point('http_req_duration', 169, 20, 'heartbeat'),
                               point('http_req_duration', 170, 300, 'heartbeat')])
        entry = result['operations']['heartbeat']
        self.assertEqual(entry['steady']['requests'], 2)
        self.assertEqual(entry['steady']['p50_ms'], 15)
        self.assertEqual(entry['all']['requests'], 4)
        self.assertTrue(entry['window_complete'])
        self.assertAlmostEqual(entry['expected_rps'], 100 / 15)
        self.assertAlmostEqual(entry['observed_rps'], 2 / 120)

    def test_polling_and_failures(self):
        rows = [point('origin', 0, 0, 'deployment_poll'), point('vus', 160, 0)]
        rows += [point('http_req_duration', 40, 5, 'deployment_poll', status) for status in ['200', '204', '500', '0']]
        # A paired failure metric must not double-count a request/failure.
        rows += [point('http_req_failed', 40, 1, 'deployment_poll', '500')]
        entry = self.analyze(rows, 'polling')['operations']['deployment_poll']
        self.assertEqual(entry['expected_rps'], 10)
        self.assertEqual(entry['steady']['requests'], 4)
        self.assertEqual(entry['steady']['failures'], 2)
        self.assertEqual(entry['steady']['failure_rate'], .5)

    def test_registration_not_sustained(self):
        result = self.analyze([point('http_req_duration', 1, 10, 'registration', '201'),
                               point('http_req_duration', 2, 20, 'registration', '200')], 'registration')
        self.assertEqual(result['registration'], {'expected': 100, 'successful': 1, 'failed': 1})
        self.assertNotIn('observed_rps', result['operations']['registration'])

    def test_mixed_separates_distributions(self):
        result = self.analyze([point('origin', 0, 0), point('origin', 0, 0, 'deployment_poll'), point('vus', 160, 0),
                               point('http_req_duration', 40, 10, 'heartbeat'),
                               point('http_req_duration', 40, 90, 'deployment_poll', '204')], 'mixed')
        self.assertEqual(result['operations']['heartbeat']['steady']['p95_ms'], 10)
        self.assertEqual(result['operations']['deployment_poll']['steady']['p95_ms'], 90)

    def test_missing_setup_never_guesses(self):
        result = self.analyze([point('http_req_duration', 40, 10, 'heartbeat')])
        self.assertNotIn('observed_rps', result['operations']['heartbeat'])
        self.assertTrue(result['warnings'])

    def test_partial_window(self):
        result = self.analyze([point('origin', 0, 0), point('http_req_duration', 40, 10, 'heartbeat')])
        self.assertFalse(result['operations']['heartbeat']['window_complete'])
        self.assertTrue(result['warnings'])

    def test_empty_malformed_missing(self):
        for rows in [[], ['not json'], ['{}', '{"type":"Point","data":null}']]:
            result = self.analyze(rows)
            self.assertTrue(result['warnings'])
            self.assertEqual(result['classification'], 'INCONCLUSIVE')
        result = analyze.analyze('/nonexistent/m10b-samples.json', {'profile': '100', 'scenario': 'registration'})
        self.assertIn('Sample file missing', result['warnings'])

    def test_report_failed_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = analyze.write_report(tmp, dict(phase='m10b', profile='100', scenario='registration',
                                                    label='warmup', run_id='1', error='startup failed'))
            self.assertEqual(result['run_error'], 'startup failed')
            self.assertIn('REVIEW_REQUIRED', (Path(tmp) / 'report.md').read_text())


class RunnerTests(unittest.TestCase):
    def test_profiles_use_source_of_truth(self):
        with patch.dict(os.environ, {}, clear=True):
            for profile, ramp, setup in [('100', 30, 120), ('1000', 120, 300), ('5000', 300, 600)]:
                env, seconds = runner.workload(profile, '1')
                self.assertEqual(seconds['RAMP_UP'], ramp)
                self.assertEqual(seconds['SETUP_TIMEOUT'], setup)
                self.assertEqual(seconds['HEARTBEAT_INTERVAL'], 15)
                self.assertEqual(seconds['POLL_INTERVAL'], 10)

    def test_recorded_overrides(self):
        with patch.dict(os.environ, {'STEADY_DURATION': '3m'}):
            self.assertEqual(runner.workload('100', '1')[1]['STEADY_DURATION'], 180)

    def test_invalid_durations(self):
        for value in ['0s', '-1s', '1', 'NaN', '1d']:
            with self.assertRaises(ValueError):
                runner.seconds(value)

    def test_baseline_guard(self):
        for committed, local in [('M src/a', ''), ('', '?? simulator/a'), ('', ' M pom.xml')]:
            with patch.object(runner, 'command', side_effect=['', committed, local, '']):
                with self.assertRaisesRegex(RuntimeError, 'baseline differs'):
                    runner.baseline('m10b')
            with patch.object(runner, 'command', side_effect=['', committed, local, '']):
                self.assertEqual(runner.baseline('m10d')['executable_local_state'], local)

    def test_missing_docker(self):
        with patch.object(runner.shutil, 'which', side_effect=lambda tool: None if tool == 'docker' else tool):
            with self.assertRaisesRegex(RuntimeError, 'Missing required tool: docker'):
                runner.preflight({})

    def test_wrong_java(self):
        with patch.object(runner.shutil, 'which', return_value='/tool'), patch.object(runner.platform, 'system', return_value='Linux'), patch.object(runner, 'command', return_value='openjdk version "17.0.1"'):
            with self.assertRaisesRegex(RuntimeError, 'Java 21'):
                runner.preflight({})

    def test_backend_pid_not_maven(self):
        with patch.object(runner, 'command', return_value='11 10 maven\n12 10 java dev.heimdall.HeimdallApplication\n13 9 java dev.heimdall.HeimdallApplication'):
            self.assertEqual(runner.backend_pid(10), 12)

    def test_cli(self):
        base = [sys.executable, str(runner.ROOT / 'scripts/m10b/runner.py')]
        for args in [['--help']]:
            result = subprocess.run(base + args, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
        for args in [['--profile', '99'], ['--profile', '100', '--scenario', 'unknown'],
                     ['--profile', '100', '--scenario', 'heartbeat', '--run-id', '10000'],
                     ['--profile', '100', '--scenario', 'mixed', '--suite', '--run-id-base', '9998'],
                     ['--profile', '100', '--scenario', 'mixed', '--run-id', '1', '--sample-interval', 'nan']]:
            self.assertNotEqual(subprocess.run(base + args, capture_output=True).returncode, 0)

    def test_dry_run_does_not_start_tools(self):
        args = type('Args', (), dict(profile='100', scenario='heartbeat', phase='m10b',
                                    dry_run=True, sample_interval=15))()
        with patch.object(runner.platform, 'platform', return_value='test'), patch.object(runner, 'command', return_value=''), patch.object(runner, 'baseline', return_value={}), patch.object(runner, 'preflight') as preflight, patch.object(runner.subprocess, 'Popen') as popen, contextlib.redirect_stdout(io.StringIO()):
            runner.run(args, 'run1', '1')
            preflight.assert_not_called()
            popen.assert_not_called()

    def test_suite_dry_run_stops_at_same_scale(self):
        output = io.StringIO()
        argv = ['scenario.sh', '--suite', '--profile', '100', '--scenario', 'mixed',
                '--run-id-base', '2200', '--dry-run']
        with patch.object(sys, 'argv', argv), patch.object(runner, 'baseline', return_value={}), contextlib.redirect_stdout(output):
            runner.main()
        for label, run_id in [('warmup', 2200), ('run1', 2201), ('run2', 2202)]:
            self.assertIn(f'100/mixed/{label}; RUN_ID={run_id}', output.getvalue())
        self.assertNotIn('1000/mixed', output.getvalue())

    def test_reset_requires_acknowledgement(self):
        args = type('Args', (), dict(profile='100', scenario='heartbeat', phase='m10b',
                                    dry_run=False, sample_interval=15, reset_disposable_db=False))()
        with patch.object(runner, 'baseline', return_value={}), contextlib.redirect_stdout(io.StringIO()), self.assertRaisesRegex(RuntimeError, 'Reset not acknowledged'):
            runner.run(args, 'run1', '1')

    def test_preflight_failure_retains_report_without_reset(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            config_path = root / 'load-tests/k6/lib'
            config_path.mkdir(parents=True)
            (config_path / 'config.js').write_text((runner.ROOT / 'load-tests/k6/lib/config.js').read_text())
            args = type('Args', (), dict(profile='100', scenario='heartbeat', phase='m10b',
                                        dry_run=False, sample_interval=15, reset_disposable_db=True))()
            with patch.object(runner, 'ROOT', root), patch.object(runner, 'command', return_value=''), patch.object(runner, 'baseline', return_value={}), patch.object(runner, 'preflight', side_effect=RuntimeError('missing Docker')), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaisesRegex(RuntimeError, 'Run failed'):
                    runner.run(args, 'run1', '1')
            reports = list(root.glob('load-tests/results/m10b/*/report.md'))
            self.assertEqual(len(reports), 1)
            self.assertIn('missing Docker', reports[0].read_text())

    @unittest.skipUnless(runner.shutil.which('k6'), 'optional local k6 validation')
    def test_wrapper_inspect_preserves_options(self):
        with tempfile.TemporaryDirectory() as tmp:
            for scenario, script in runner.SCRIPTS.items():
                wrapper = Path(tmp) / 'entry.js'
                wrapper.write_text(runner.entry_source({'scenario': scenario}))
                def inspect(path):
                    return json.loads(subprocess.check_output(['k6', 'inspect', '--execution-requirements', str(path)], text=True))
                self.assertEqual(inspect(wrapper), inspect(runner.ROOT / 'load-tests/k6' / script))

    @unittest.skipUnless(runner.shutil.which('k6'), 'optional no-HTTP k6 marker validation')
    def test_real_k6_marker_without_http_or_backend(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scripts = root / 'load-tests/k6'
            scripts.mkdir(parents=True)
            (scripts / 'heartbeat.js').write_text("import { sleep } from 'k6';\nexport const options = {scenarios:{heartbeat:{executor:'shared-iterations',vus:1,iterations:1}}};\nexport function setup() { sleep(0.01); }\nexport default function() { sleep(0.01); }\n")
            with patch.object(runner, 'ROOT', root):
                (root / 'entry.js').write_text(runner.entry_source({'scenario': 'heartbeat'}))
            (root / 'config.json').write_text('{}')
            subprocess.run(['k6', 'run', '--no-usage-report', '--address', '', '--config', str(root / 'config.json'),
                            '--out', 'json=' + str(root / 'samples.json'), str(root / 'entry.js')],
                           check=True, capture_output=True)
            rows = [json.loads(line) for line in (root / 'samples.json').read_text().splitlines()]
            markers = [r for r in rows if r.get('type') == 'Point' and r.get('metric') == 'm10_scenario_start_ms']
            self.assertEqual(len(markers), 1)
            self.assertEqual(markers[0]['data']['tags']['operation'], 'heartbeat')
            self.assertLessEqual(markers[0]['data']['value'] / 1000, analyze.timestamp(markers[0]['data']['time']))
            self.assertFalse(any(r.get('metric') == 'http_reqs' for r in rows))

    def test_monitor_cpu_over_100_and_missing_metrics(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = {'sample_interval': 15}
            monitor = runner.Monitor(Path(tmp), config, 11, 12)
            def capture(args):
                if args[0] == 'ps':
                    return '150.0 2.0 1024'
                if args[:2] == ['docker', 'stats']:
                    return json.dumps({'Name': 'heimdall-kafka', 'CPUPerc': '220.0%',
                                       'MemPerc': '1%', 'MemUsage': '12MiB / 8GiB'})
                if args[0] == 'curl':
                    monitor.done.set()
                    return 'hikaricp_connections_pending{pool="main"} 0'
                return ''
            with patch.object(monitor, 'capture', side_effect=capture):
                monitor.thread.start()
                monitor.thread.join()
                monitor.finish()
            self.assertEqual(config['resource_peaks']['heimdall-kafka_cpu_percent'], 220)
            self.assertEqual(config['resource_peaks']['backend_cpu_percent'], 150)
            self.assertIn('jvm_memory_used_bytes', config['missing_metrics'])
            self.assertNotIn('hikaricp_connections_pending', config['missing_metrics'])
            self.assertFalse(config['monitor_errors'])

    def test_monitor_errors_are_recorded(self):
        with tempfile.TemporaryDirectory() as tmp:
            monitor = runner.Monitor(Path(tmp), {'sample_interval': 15}, 1, 2)
            with patch.object(runner, 'command', side_effect=RuntimeError('unavailable')):
                self.assertEqual(monitor.capture(['test']), '')
                self.assertEqual(monitor.errors, {'unavailable'})

    def test_comparison_excludes_warmup(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            paths = []
            for label, rps in [('warmup', 1000), ('run1', 9), ('run2', 11)]:
                path = root / label
                path.mkdir()
                paths.append(path)
                (path / 'metadata.json').write_text(json.dumps({'label': label}))
                (path / 'analysis.json').write_text(json.dumps({'operations': {'heartbeat': {
                    'all': {'requests': 100, 'failures': 0}, 'steady': {'p95_ms': 10, 'p99_ms': 20},
                    'observed_rps': rps}}}))
            runner.comparison(paths, root / 'comparison.md')
            self.assertIn('heartbeat rps: 20.0%', (root / 'comparison.md').read_text())

    def test_owned_process_group_cleanup(self):
        proc = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'], start_new_session=True)
        runner.stop(proc)
        self.assertIsNotNone(proc.poll())

    def test_command_timeout(self):
        with self.assertRaises(subprocess.TimeoutExpired):
            runner.command([sys.executable, '-c', 'import time; time.sleep(30)'], timeout=.05)

    def test_readiness_failure_bounded(self):
        with self.assertRaisesRegex(RuntimeError, 'Timed out'):
            runner.wait_ready('test', lambda: False, 0)


class IntegrityTests(unittest.TestCase):
    """Use real Git status in an isolated repo, never dirty the user's checkout."""
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.root_patch = patch.object(runner, 'ROOT', self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.git('init', '-q')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'user.name', 'Tooling test')
        for name in ['src/example', 'simulator/example', 'load-tests/k6/example',
                     'pom.xml', 'compose.yaml', 'scripts/m10b/runner.py', 'docs/note.md']:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('baseline\n')
        self.git('add', '.')
        self.git('-c', 'commit.gpgsign=false', 'commit', '-qm', 'fixture baseline')
        baseline_patch = patch.object(runner, 'BASELINE', self.git('rev-parse', 'HEAD'))
        baseline_patch.start()
        self.addCleanup(baseline_patch.stop)

    def git(self, *args):
        return runner.command(['git', *args])

    def test_m10b_rejects_dirty_executable_paths(self):
        for name in ['src/example', 'simulator/example', 'load-tests/k6/example', 'pom.xml', 'compose.yaml']:
            with self.subTest(path=name):
                path = self.root / name
                path.write_text('modified\n')
                with self.assertRaisesRegex(RuntimeError, 'executable baseline differs'):
                    runner.baseline('m10b')
                path.write_text('baseline\n')

    def test_both_phases_reject_tracked_and_untracked_orchestration(self):
        tracked = self.root / 'scripts/m10b/runner.py'
        extra = self.root / 'scripts/m10b/helper.py'
        for state in ['unstaged', 'staged', 'untracked']:
            if state == 'untracked':
                extra.write_text('new helper\n')
            else:
                tracked.write_text('modified\n')
                if state == 'staged':
                    self.git('add', 'scripts/m10b/runner.py')
            for phase in ['m10b', 'm10d']:
                with self.subTest(phase=phase, state=state):
                    with self.assertRaisesRegex(RuntimeError, 'Orchestration has local changes'):
                        runner.baseline(phase)
            self.git('reset', '-q', 'HEAD', '--', 'scripts/m10b/runner.py')
            tracked.write_text('baseline\n')
            extra.unlink(missing_ok=True)

    def test_m10d_allows_changed_executable_baseline(self):
        (self.root / 'src/example').write_text('new implementation\n')
        self.git('add', 'src/example')
        self.git('-c', 'commit.gpgsign=false', 'commit', '-qm', 'fixture M10D change')
        result = runner.baseline('m10d')
        self.assertIn('src/example', result['executable_committed_diff'])
        self.assertEqual(result['orchestration_integrity'], 'PASS')
        with self.assertRaisesRegex(RuntimeError, 'executable baseline differs'):
            runner.baseline('m10b')

    def test_clean_orchestration_passes_with_unrelated_dirty_docs(self):
        (self.root / 'docs/note.md').write_text('documentation edit\n')
        (self.root / 'docs/new.md').write_text('untracked documentation\n')
        for phase in ['m10b', 'm10d']:
            with self.subTest(phase=phase):
                result = runner.baseline(phase)
                self.assertEqual(result['orchestration_local_state'], '')
                self.assertEqual(result['orchestration_integrity'], 'PASS')


if __name__ == '__main__':
    unittest.main()
