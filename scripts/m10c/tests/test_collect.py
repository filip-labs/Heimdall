import importlib.util
from pathlib import Path
import unittest


PATH = Path(__file__).resolve().parents[1] / 'collect.py'
SPEC = importlib.util.spec_from_file_location('m10c_collect', PATH)
collect = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(collect)


class CollectTests(unittest.TestCase):
    def test_selects_supported_low_cardinality_metrics(self):
        payload = '''# HELP ignored ignored
tomcat_threads_busy_threads{name="http-nio-8080"} 12
hikaricp_connections_pending{pool="HikariPool-1"} 0
http_server_requests_seconds_count{method="GET",status="200",uri="/api/v1/test"} 5
unrelated_metric{vin="high-cardinality"} 99
'''
        selected = collect.select_metrics(payload)
        self.assertEqual(len(selected), 3)
        self.assertFalse(any('unrelated_metric' in line for line in selected))

    def test_tomcat_metric_names_are_explicit(self):
        self.assertIn('tomcat_threads_busy_threads', collect.TOMCAT_PREFIXES)
        self.assertIn('tomcat_threads_current_threads', collect.TOMCAT_PREFIXES)
        self.assertIn('tomcat_threads_config_max_threads', collect.TOMCAT_PREFIXES)

    def test_process_capture_excludes_unrelated_commands(self):
        output = 'PID PPID %CPU COMMAND\n1 0 4.0 python unrelated.py\n2 1 99.0 java HeimdallApplication\n3 1 80.0 k6 run diagnostic.js'
        selected = collect.relevant_processes(output)
        self.assertNotIn('unrelated.py', selected)
        self.assertIn('HeimdallApplication', selected)
        self.assertIn('k6 run', selected)


if __name__ == '__main__':
    unittest.main()
