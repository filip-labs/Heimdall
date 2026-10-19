import importlib.util
import json
import os
from pathlib import Path
import select
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location('m10c_jmx_collect', Path(__file__).resolve().parents[1] / 'collect.py')
collect = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(collect)


def bean(port=8080, **overrides):
    result = {'object_name': f'Tomcat:name="http-nio-{port}",type=ThreadPool',
              'type': 'ThreadPool', 'keys': {'name': f'http-nio-{port}'},
              'attributes': {'localPort': port, 'connectionCount': 8192, 'maxConnections': 8192,
                             'keepAliveCount': 17, 'currentThreadCount': 23,
                             'currentThreadsBusy': 12, 'maxThreads': 200}, 'errors': {}}
    result.update(overrides)
    return result


def response(*beans):
    return json.dumps({'ok': True, 'pid': 42, 'mbeans': list(beans)})


class NormalizationTests(unittest.TestCase):
    def test_valid(self):
        result = collect.normalize_jmx(response(bean()))
        self.assertTrue(result['tomcat_jmx']['ok'])
        self.assertEqual([result[k] for k in collect.JMX_FIELDS], [8192, 8192, 17, 23, 12, 200])
        self.assertEqual(result['tomcat_jmx']['selected']['port_source'], 'localPort')

    def test_missing_optional_and_negative_sentinel(self):
        b = bean()
        del b['attributes']['keepAliveCount']
        b['attributes']['maxThreads'] = -1
        result = collect.normalize_jmx(response(b))
        self.assertTrue(result['tomcat_jmx']['ok'])
        self.assertIsNone(result['tomcat_jmx_keep_alive_count'])
        self.assertIsNone(result['tomcat_jmx_max_threads'])
        self.assertEqual(result['tomcat_jmx']['unavailable_attributes'], ['keepAliveCount', 'maxThreads'])
        self.assertEqual(result['tomcat_jmx']['selected']['attributes']['maxThreads'], -1)

    def test_port_selection_is_order_independent(self):
        for beans in ([bean(9090), bean()], [bean(), bean(9090)]):
            result = collect.normalize_jmx(response(*beans))
            self.assertTrue(result['tomcat_jmx']['ok'])
            self.assertEqual(result['tomcat_jmx']['selected']['attributes']['localPort'], 8080)
            self.assertEqual(len(result['tomcat_jmx']['candidates']), 2)

    def test_ambiguous_connectors_retain_candidates(self):
        result = collect.normalize_jmx(response(bean(), bean(object_name='Other:type=ThreadPool,name=http-nio-8080')))
        self.assertFalse(result['tomcat_jmx']['ok'])
        self.assertIn('Ambiguous', result['tomcat_jmx']['error'])
        self.assertEqual(len(result['tomcat_jmx']['candidates']), 2)
        self.assertTrue(all(result[k] is None for k in collect.JMX_FIELDS))

    def test_no_mbeans_or_wrong_port_or_unreadable(self):
        for raw in (response(), response(bean(9090)), response(bean(attributes={'localPort': 8080}))):
            result = collect.normalize_jmx(raw)
            self.assertFalse(result['tomcat_jmx']['ok'])
            self.assertTrue(all(result[k] is None for k in collect.JMX_FIELDS))

    def test_name_port_fallback_preserves_provenance(self):
        b = bean()
        del b['attributes']['localPort']
        result = collect.normalize_jmx(response(b))
        self.assertTrue(result['tomcat_jmx']['ok'])
        self.assertEqual(result['tomcat_jmx']['selected']['port_source'], 'ObjectName.name suffix')

    def test_malformed_response(self):
        for raw in ('not json', '[]', '{}', '{"ok":"yes"}', '{"ok":true,"mbeans":[{}]}'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                collect.normalize_jmx(raw)

    def test_pid_discovery_excludes_launchers_and_unrelated_processes(self):
        lines = ''' 42 /Library/Java/JavaVirtualMachines/jdk 21/Contents/Home/bin/java -cp /tmp/classes dev.heimdall.HeimdallApplication
43 java org.codehaus.plexus.classworlds.launcher.Launcher -Dspring-boot.run.main-class=dev.heimdall.HeimdallApplication
44 java -jar kafka.jar
45 k6 run dev.heimdall.HeimdallApplication
46 python java dev.heimdall.HeimdallApplication
47 java -Dnote=dev.heimdall.HeimdallApplication AnotherApp
'''
        self.assertEqual(collect.backend_candidates(lines), [{'pid': 42, 'main': collect.APPLICATION_MAIN}])


class ProbeFailureTests(unittest.TestCase):
    def setUp(self):
        self.probe = collect.JmxProbe(startup_timeout=0.1, sample_timeout=0.1)
        self.addCleanup(self.probe.close)

    def assert_failed_collector_continues(self, result, message):
        self.assertFalse(result['tomcat_jmx']['ok'])
        self.assertIn(message, result['tomcat_jmx']['error'])
        self.assertTrue(all(result[k] is None for k in collect.JMX_FIELDS))
        with patch.object(collect, 'scrape', side_effect=TimeoutError('HTTP timeout')), \
                patch.object(collect, 'run_command', return_value={'ok': True, 'stdout': ''}):
            sample = collect.safe_sample(Mock(sample=Mock(return_value=result)))
        self.assertIn('prometheus_error', sample)
        for field in ('postgres_activity', 'containers', 'processes', 'tomcat_jmx'):
            self.assertIn(field, sample)

    def test_unavailable_or_ambiguous_pid(self):
        for output, message in [('', 'unavailable'),
                ('42 java dev.heimdall.HeimdallApplication\n43 java dev.heimdall.HeimdallApplication', 'Ambiguous')]:
            with self.subTest(message=message), patch.object(collect, 'run_command', return_value={'ok': True, 'stdout': output}):
                self.probe.next_retry = 0
                result = self.probe.sample()
                self.assert_failed_collector_continues(result, message)
                self.assertEqual(self.probe.starts, 0)

    def fake_start(self, script):
        def start(pid):
            self.probe.pid = pid
            self.probe.starts += 1
            self.probe.stderr = tempfile.TemporaryFile()
            self.probe.process = subprocess.Popen([sys.executable, '-u', '-c', script],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.probe.stderr, bufsize=0)
            os.set_blocking(self.probe.process.stdout.fileno(), False)
        return start

    def test_pipe_failures_and_cooldown(self):
        cases = [
            ('import time; time.sleep(20)', 'timed out'),
            ('import sys; sys.stdin.readline(); sys.exit(7)', 'status 7'),
            ('import sys; sys.stdin.readline(); print("broken")', 'JSONDecodeError'),
            ('import sys; sys.stdin.readline(); print(\'{"ok":false,"stage":"attach","error":"attach denied"}\')', 'attach denied'),
        ]
        for script, message in cases:
            with self.subTest(message=message), \
                    patch.object(collect, 'run_command', return_value={'ok': True, 'stdout': '42 java dev.heimdall.HeimdallApplication'}), \
                    patch.object(self.probe, 'start', side_effect=self.fake_start(script)):
                self.probe.next_retry = 0
                result = self.probe.sample()
                self.assert_failed_collector_continues(result, message)
                self.assertIsNone(self.probe.process)
                starts = self.probe.starts
                retry = self.probe.sample()
                self.assertIn('retry_in_seconds', retry['tomcat_jmx'])
                self.assertEqual(self.probe.starts, starts)

    def test_no_mbean_continues_without_restarting_helper(self):
        with patch.object(collect, 'run_command', return_value={'ok': True, 'stdout': '42 java dev.heimdall.HeimdallApplication'}), \
                patch.object(self.probe, 'start', side_effect=self.fake_start(
                    'import sys\nfor line in sys.stdin: print(\'{"ok":true,"pid":42,"mbeans":[]}\')')):
            self.probe.startup_timeout = 2
            result = self.probe.sample()
            self.assert_failed_collector_continues(result, 'No matching')
            process = self.probe.process
            self.probe.sample()
            self.assertIs(self.probe.process, process)
            self.assertEqual(self.probe.starts, 1)

    def test_unexpected_probe_exception_preserves_other_observations(self):
        with patch.object(collect, 'scrape', return_value=('', [])), \
                patch.object(collect, 'run_command', return_value={'ok': True, 'stdout': ''}):
            result = collect.sample(Mock(sample=Mock(side_effect=RuntimeError('unexpected probe failure'))))
        self.assertFalse(result['tomcat_jmx']['ok'])
        self.assertIn('unexpected probe failure', result['tomcat_jmx']['error'])
        self.assertTrue(all(result[k] is None for k in collect.JMX_FIELDS))
        for field in ('metrics', 'postgres_activity', 'containers', 'processes'):
            self.assertIn(field, result)

    def test_reused_helper_read_timeout_is_bounded_and_reaped(self):
        script = 'import sys, time\nsys.stdin.readline()\nprint(' + repr(response(bean())) + ')\nsys.stdin.readline()\ntime.sleep(20)'
        with patch.object(collect, 'run_command', return_value={'ok': True, 'stdout': '42 java dev.heimdall.HeimdallApplication'}), \
                patch.object(self.probe, 'start', side_effect=self.fake_start(script)):
            self.probe.startup_timeout = 2
            first = self.probe.sample()
            self.assertTrue(first['tomcat_jmx']['ok'], first)
            process = self.probe.process
            second = self.probe.sample()
        self.assert_failed_collector_continues(second, 'timed out after 0.1s')
        self.assertIsNotNone(process.poll())
        self.assertEqual(self.probe.starts, 1)

    def test_successful_jmx_survives_http_failure(self):
        result = collect.normalize_jmx(response(bean()))
        with patch.object(collect, 'scrape', side_effect=TimeoutError('HTTP timeout')), \
                patch.object(collect, 'run_command', return_value={'ok': True, 'stdout': ''}):
            sample = collect.safe_sample(Mock(sample=Mock(return_value=result)))
        self.assertEqual(sample['tomcat_jmx_connection_count'], 8192)
        self.assertIn('prometheus_error', sample)
        self.assertIn('postgres_activity', sample)


class LocalAttachTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='m10c-jmx-test-')
        cls.addClassCleanup(cls.temp.cleanup)
        java_home = os.environ.get('JAVA_HOME')
        cls.java = str(Path(java_home) / 'bin/java') if java_home else 'java'
        javac = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
        subprocess.run([javac, '--add-modules', 'jdk.attach', '-d', cls.temp.name,
            str(Path(collect.__file__).with_name('TomcatJmxProbe.java')),
            str(Path(__file__).with_name('JmxFixture.java'))], check=True, capture_output=True, timeout=30)

    def fixture(self, *args):
        process = subprocess.Popen([self.java, '-cp', self.temp.name, 'JmxFixture', *args],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        def close():
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)
            for stream in (process.stdin, process.stdout, process.stderr):
                stream.close()
        self.addCleanup(close)
        self.assertTrue(select.select([process.stdout], [], [], 10)[0], 'fixture startup timed out')
        self.assertEqual(process.stdout.readline().strip(), b'ready')
        return process

    def test_real_attach_quoted_names_multiple_ports_connection_reuse(self):
        fixture = self.fixture()
        probe = collect.JmxProbe()
        self.addCleanup(probe.close)
        with patch.object(collect, 'APPLICATION_MAIN', 'JmxFixture'), \
                patch.object(collect, 'backend_candidates', return_value=[{'pid': fixture.pid}]), \
                patch.object(collect, 'run_command', return_value={'ok': True, 'stdout': ''}):
            first = probe.sample()
            self.assertTrue(first['tomcat_jmx']['ok'], first)
            process = probe.process
            second = probe.sample()
        self.assertTrue(second['tomcat_jmx']['ok'], second)
        self.assertIs(probe.process, process)
        self.assertEqual(probe.starts, 1)
        self.assertEqual(second['tomcat_jmx']['selected_pid'], fixture.pid)
        self.assertEqual(second['tomcat_jmx_connection_count'], 17)
        self.assertEqual(second['tomcat_jmx']['selected']['keys']['name'], 'http-nio-8080')
        self.assertIn('\n', second['tomcat_jmx']['selected']['attributes']['modelerType'])
        self.assertEqual(len(second['tomcat_jmx']['candidates']), 2)
        # A new helper must reuse the management agent already present in this JVM.
        probe.close()
        with patch.object(collect, 'APPLICATION_MAIN', 'JmxFixture'):
            probe.start(fixture.pid)
            third = collect.normalize_jmx(probe.exchange(15))
        self.assertFalse(third['tomcat_jmx']['helper']['agent_started_by_helper'])

    def test_real_no_mbeans(self):
        fixture = self.fixture('empty')
        probe = collect.JmxProbe()
        self.addCleanup(probe.close)
        with patch.object(collect, 'APPLICATION_MAIN', 'JmxFixture'):
            probe.start(fixture.pid)
            result = collect.normalize_jmx(probe.exchange(15))
        self.assertFalse(result['tomcat_jmx']['ok'])
        self.assertIn('No matching', result['tomcat_jmx']['error'])

    def test_real_target_identity_mismatch_rejected_before_agent_start(self):
        fixture = self.fixture()
        probe = collect.JmxProbe()
        self.addCleanup(probe.close)
        probe.start(fixture.pid)
        result = collect.normalize_jmx(probe.exchange(15))
        self.assertFalse(result['tomcat_jmx']['ok'])
        self.assertIn('Target main does not match', result['tomcat_jmx']['error'])


if __name__ == '__main__':
    unittest.main()
