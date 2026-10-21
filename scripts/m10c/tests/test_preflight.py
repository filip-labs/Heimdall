import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

from test_jmx import collect, bean, response


class JavaSelectionTests(unittest.TestCase):
    def version(self, args, **kwargs):
        if args == ['/usr/libexec/java_home', '-v', '21']:
            return subprocess.CompletedProcess(args, 0, '/jdk21\n', '')
        feature = 24 if '24' in args[0] else 21
        if args[-1] == '-version' and args[0].endswith('/javac'):
            return subprocess.CompletedProcess(args, 0, f'javac {feature}.0.1', '')
        return subprocess.CompletedProcess(args, 0, '',
            f'    java.specification.version = {feature}\n    java.home = /jdk{feature}\n')

    def test_java_home_preferred_and_paths_recorded(self):
        with patch.dict(os.environ, {'JAVA_HOME': '/jdk21'}), \
                patch.object(collect.subprocess, 'run', side_effect=self.version) as run:
            result = collect.select_java21()
        self.assertEqual(result['source'], 'JAVA_HOME')
        self.assertEqual(result['java_executable'], '/jdk21/bin/java')
        self.assertEqual(result['javac_executable'], '/jdk21/bin/javac')
        self.assertEqual(result['java_feature_version'], 21)
        self.assertEqual(run.call_count, 2)

    def test_mac_fallback_rejects_wrong_java_home(self):
        with patch.dict(os.environ, {'JAVA_HOME': '/jdk24'}), \
                patch.object(collect.sys, 'platform', 'darwin'), \
                patch.object(collect.subprocess, 'run', side_effect=self.version):
            result = collect.select_java21()
        self.assertEqual(result['source'], 'macOS java_home -v 21')
        self.assertIn('detected 24', result['rejected_candidates'][0])

    def test_verified_path_on_linux(self):
        with patch.dict(os.environ, {}, clear=True), \
                patch.object(collect.sys, 'platform', 'linux'), \
                patch.object(collect.shutil, 'which', return_value='/jdk21/bin/java'), \
                patch.object(collect.subprocess, 'run', side_effect=self.version):
            self.assertEqual(collect.select_java21()['source'], 'verified PATH')

    def test_wrong_path_runtime_rejected(self):
        with patch.dict(os.environ, {}, clear=True), \
                patch.object(collect.sys, 'platform', 'linux'), \
                patch.object(collect.shutil, 'which', return_value='/jdk24/bin/java'), \
                patch.object(collect.subprocess, 'run', side_effect=self.version), \
                self.assertRaisesRegex(RuntimeError, 'detected 24'):
            collect.select_java21()

    def test_missing_jdk_and_unusable_compiler(self):
        with patch.dict(os.environ, {}, clear=True), \
                patch.object(collect.sys, 'platform', 'linux'), \
                patch.object(collect.shutil, 'which', return_value=None), \
                self.assertRaisesRegex(RuntimeError, 'No valid Java 21 JDK'):
            collect.select_java21()
        with patch.object(collect.subprocess, 'run', side_effect=[
                self.version(['/jdk21/bin/java']), FileNotFoundError('javac missing')]), \
                self.assertRaisesRegex(RuntimeError, 'javac missing'):
            collect.select_java21('/jdk21')

    def test_pinned_jdk_never_falls_back_and_timeout_is_bounded(self):
        for error in (ValueError('wrong Java'), subprocess.TimeoutExpired('java', 3)):
            with patch.object(collect.subprocess, 'run', side_effect=error) as run, \
                    self.assertRaisesRegex(RuntimeError, 'No valid Java 21 JDK'):
                collect.select_java21('/pinned')
            self.assertEqual(run.call_count, 1)

    def test_helper_uses_cached_absolute_java(self):
        probe = collect.JmxProbe()
        with patch.object(collect, 'select_java21', return_value={'java_executable': '/jdk21/bin/java'}) as select_jdk:
            probe.ensure_java()
            probe.ensure_java()
        select_jdk.assert_called_once()


class PreflightTests(unittest.TestCase):
    def probe(self, result=None):
        return Mock(jdk={'java_feature_version': 21}, sample=Mock(return_value=(
            collect.normalize_jmx(response(bean())) if result is None else result)))

    def health(self, status='UP'):
        result = Mock()
        result.__enter__ = Mock(return_value=Mock(read=Mock(return_value=json.dumps({'status': status}).encode())))
        result.__exit__ = Mock(return_value=False)
        return result

    def test_success(self):
        probe = self.probe()
        with patch.object(collect.urllib.request, 'urlopen', return_value=self.health()) as health:
            result = collect.preflight(probe)
        self.assertTrue(result['preflight']['ok'])
        probe.ensure_java.assert_called_once()
        health.assert_called_once_with('http://localhost:8080/actuator/health', timeout=5)

    def test_health_failure_prevents_attach(self):
        probe = self.probe()
        with patch.object(collect.urllib.request, 'urlopen', side_effect=TimeoutError('health timeout')):
            result = collect.preflight(probe)
        self.assertFalse(result['preflight']['ok'])
        self.assertIn('health timeout', result['preflight']['error'])
        probe.sample.assert_not_called()

    def test_java_failure_prevents_health_and_attach(self):
        probe = self.probe()
        probe.ensure_java.side_effect = RuntimeError('No valid Java 21 JDK')
        with patch.object(collect.urllib.request, 'urlopen') as health:
            result = collect.preflight(probe)
        self.assertFalse(result['preflight']['ok'])
        health.assert_not_called()
        probe.sample.assert_not_called()

    def test_jmx_failures_and_missing_limits_rejected(self):
        cases = [collect.jmx_unavailable(reason) for reason in (
            'Heimdall JVM unavailable', 'Ambiguous Heimdall JVMs', 'attach failed', 'No matching Tomcat ThreadPool MBean')]
        for field in ('tomcat_jmx_max_connections', 'tomcat_jmx_max_threads'):
            result = collect.normalize_jmx(response(bean()))
            result[field] = None
            cases.append(result)
        for case in cases:
            with self.subTest(case=case), patch.object(collect.urllib.request, 'urlopen', return_value=self.health()):
                self.assertFalse(collect.preflight(self.probe(case))['preflight']['ok'])


class RunnerGateTests(unittest.TestCase):
    def run_stubbed(self, preflight_ok, jfr=False, jfr_ok=True, k6_status=0):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scripts = root / 'scripts/m10c'
            scripts.mkdir(parents=True)
            shutil.copy(Path(collect.__file__).with_name('run-mixed-diagnostic.sh'), scripts)
            bin_dir = root / 'bin'
            bin_dir.mkdir()
            python = bin_dir / 'python3'
            python.write_text(f'#!{sys.executable}\n' + '''import json, os, sys, time
from pathlib import Path
args = sys.argv[1:]
with open('calls.jsonl', 'a') as out: out.write(json.dumps(args) + '\\n')
if any(a.endswith('/jfr.py') for a in args):
    directory = Path(args[args.index('--result-dir') + 1])
    mode = args[args.index('--result-dir') - 1]
    if mode == 'start':
        (directory / 'jfr-start.log').write_text('scheduled' if os.environ['JFR_OK'] == '1' else 'rejected')
        (directory / 'jfr-status.json').write_text('{}')
        if os.environ['JFR_OK'] != '1': sys.exit(1)
    elif mode == 'finish':
        (directory / 'jfr-check-after.log').write_text('finished')
        (directory / 'm10c-5104-steady.jfr').write_bytes(b'fixture only')
elif '--preflight' in args:
    if os.environ['PREFLIGHT_OK'] != '1':
        print('test: Java/JMX preflight failed', file=sys.stderr)
        sys.exit(1)
    print(json.dumps({'tomcat_jmx': {'jdk': {'java_home': '/verified/jdk21'}}}))
elif '-c' in args:
    print('/verified/jdk21')
else:
    Path('collector-started').touch()
    time.sleep(20)
''')
            python.chmod(0o755)
            k6 = bin_dir / 'k6'
            k6.write_text(f'#!{sys.executable}\nimport time, os, sys\nfrom pathlib import Path\nPath("k6-started").touch()\ntime.sleep(0.2)\nsys.exit(int(os.environ["K6_STATUS"]))\n')
            k6.chmod(0o755)
            git = bin_dir / 'git'
            git.write_text('#!/bin/sh\nexit 0\n')
            git.chmod(0o755)
            env = {**os.environ, 'PATH': str(bin_dir) + os.pathsep + os.environ['PATH'],
                   'RUN_ID': '5104' if jfr else '9999', 'JFR_OK': '1' if jfr_ok else '0', 'K6_STATUS': str(k6_status), 'RESULT_ROOT': str(root / 'results'),
                   'PREFLIGHT_OK': '1' if preflight_ok else '0'}
            result = subprocess.run(['bash', str(scripts / 'run-mixed-diagnostic.sh')],
                                    cwd=root, env=env, text=True, capture_output=True, timeout=10)
            calls = [json.loads(x) for x in (root / 'calls.jsonl').read_text().splitlines()]
            if preflight_ok and jfr_ok:
                self.assertEqual(result.returncode, k6_status, result.stderr)
                self.assertTrue((root / 'k6-started').exists())
                collector = next(c for c in calls if '--output' in c)
                self.assertEqual(collector[collector.index('--java-home') + 1], '/verified/jdk21')
                self.assertIn('143', next((root / 'results').glob('*/collector-status.txt')).read_text())
                if jfr:
                    modes = [c[c.index('--result-dir') - 1] for c in calls if '--result-dir' in c]
                    self.assertEqual(modes, ['start', 'launch', 'finish'])
                    directory = next((root / 'results').iterdir())
                    self.assertEqual((directory / 'k6-exit-code.txt').read_text().strip(), str(k6_status))
                    self.assertTrue((directory / 'm10c-5104-steady.jfr').stat().st_size)
                    self.assertTrue((directory / 'jfr-start.log').exists())
            else:
                self.assertNotEqual(result.returncode, 0)
                if not preflight_ok:
                    self.assertIn('k6 was not started', result.stderr)
                self.assertFalse((root / 'k6-started').exists())
                self.assertFalse((root / 'collector-started').exists())
                if not preflight_ok:
                    self.assertEqual(len(calls), 1)

    def test_failed_preflight_never_starts_k6(self):
        self.run_stubbed(False)

    def test_success_pins_java_before_starting_workload_stub(self):
        self.run_stubbed(True)

    def test_jfr_failure_prevents_workload(self):
        self.run_stubbed(True, jfr=True, jfr_ok=False)

    def test_jfr_success_preserves_threshold_99_and_evidence(self):
        self.run_stubbed(True, jfr=True, k6_status=99)


if __name__ == '__main__':
    unittest.main()
