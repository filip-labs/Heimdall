import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from test_jmx import collect

SPEC = importlib.util.spec_from_file_location('m10c_jfr', Path(collect.__file__).with_name('jfr.py'))
jfr = importlib.util.module_from_spec(SPEC)
with patch.dict(sys.modules, {'collect': collect}):
    SPEC.loader.exec_module(jfr)


class JfrControlTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='m10c-jfr ')
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.jdk = {'java_home': '/verified/jdk21', 'java_feature_version': 21}
        (self.directory / 'preflight.json').write_text(json.dumps({
            'preflight': {'ok': True}, 'tomcat_jmx': {'ok': True, 'selected_pid': 123,
                                                   'jdk': self.jdk}}))
        self.calls = []
        self.started = False
        self.conflict = False
        self.rejected = False

    def run_jcmd(self, args, **kwargs):
        self.calls.append(args)
        self.assertEqual(args[:2], ['/verified/jdk21/bin/jcmd', '123'])
        self.assertEqual(kwargs['timeout'], 15)
        name = args[2]
        if name == 'VM.version': output = 'JDK 21.0.0\n'
        elif name == 'VM.command_line': output = 'java_command: dev.heimdall.HeimdallApplication --server.tomcat.max-connections=12000\n'
        elif name == 'JFR.start':
            self.started = not self.rejected
            output = 'Could not start recording' if self.rejected else 'Recording 1 scheduled to start in 5 m.'
        elif name == 'JFR.check':
            output = 'Recording 1: name=M10C5104 (delayed)\n' if self.started or self.conflict else 'No available recordings.\n'
        else: raise AssertionError(args)
        return subprocess.CompletedProcess(args, 0, output, '')

    def start(self):
        with patch.object(jfr, 'select_java21', return_value=self.jdk) as selected, \
                patch.object(jfr.os, 'access', return_value=True), \
                patch.object(jfr.subprocess, 'run', side_effect=self.run_jcmd):
            result = jfr.start(self.directory)
        selected.assert_called_once_with('/verified/jdk21')
        return result

    def test_schedule_command_and_final_file_evidence(self):
        self.assertEqual(self.start(), 0)
        args = next(c for c in self.calls if c[2] == 'JFR.start')
        for item in ('name=M10C5104', 'settings=profile', 'delay=5m', 'duration=10m', 'disk=true'):
            self.assertIn(item, args)
        target = self.directory.resolve() / 'm10c-5104-steady.jfr'
        self.assertIn('filename=' + json.dumps(str(target)), args)
        self.assertEqual([c[2] for c in self.calls], ['VM.version', 'VM.command_line', 'JFR.check', 'JFR.start', 'JFR.check'])
        jfr.update(self.directory, 'launch')
        target.write_bytes(b'test JFR fixture')
        with patch.object(jfr.subprocess, 'run', side_effect=self.run_jcmd):
            self.assertEqual(jfr.update(self.directory, 'finish'), 0)
        status = json.loads((self.directory / 'jfr-status.json').read_text())
        self.assertTrue(status['scheduling_ok'])
        self.assertTrue(status['recording_file_ok'])
        self.assertEqual(status['file_size_bytes'], 16)
        self.assertIn('k6_launch_requested_at', status)
        self.assertIn('start_command_returned_at', status)
        for name in ('jfr-start.log', 'jfr-check-before.log', 'jfr-check-after.log'):
            self.assertTrue((self.directory / name).stat().st_size)

    def test_same_name_recording_rejected(self):
        self.conflict = True
        self.assertEqual(self.start(), 1)
        self.assertFalse(any(c[2] == 'JFR.start' for c in self.calls))

    def test_zero_exit_rejection_detected_by_check(self):
        self.rejected = True
        self.assertEqual(self.start(), 1)
        status = json.loads((self.directory / 'jfr-status.json').read_text())
        self.assertFalse(status['scheduling_ok'])

    def test_existing_file_not_overwritten(self):
        target = self.directory / 'm10c-5104-steady.jfr'
        target.write_bytes(b'preserve')
        self.assertEqual(self.start(), 1)
        self.assertEqual(target.read_bytes(), b'preserve')
        self.assertFalse(self.calls)

    def test_missing_jcmd_wrong_java_and_unreachable_target(self):
        for failure in ('missing', 'java', 'timeout'):
            with self.subTest(failure=failure):
                status_path = self.directory / 'jfr-status.json'
                status_path.unlink(missing_ok=True)
                with patch.object(jfr, 'select_java21', side_effect=RuntimeError('wrong Java') if failure == 'java' else None, return_value=self.jdk), \
                        patch.object(jfr.os, 'access', return_value=failure != 'missing'), \
                        patch.object(jfr.subprocess, 'run', side_effect=subprocess.TimeoutExpired('jcmd', 15)):
                    self.assertEqual(jfr.start(self.directory), 1)
                self.assertFalse(json.loads(status_path.read_text())['scheduling_ok'])

    def test_target_identity_rejected(self):
        original = self.run_jcmd
        def run(args, **kwargs):
            if args[2] == 'VM.command_line':
                return subprocess.CompletedProcess(args, 0, 'java_command: OtherMain dev.heimdall.HeimdallApplication\n', '')
            return original(args, **kwargs)
        with patch.object(jfr, 'select_java21', return_value=self.jdk), \
                patch.object(jfr.os, 'access', return_value=True), \
                patch.object(jfr.subprocess, 'run', side_effect=run):
            self.assertEqual(jfr.start(self.directory), 1)
        self.assertFalse(any(c[2] == 'JFR.start' for c in self.calls))

    def test_missing_final_file_retains_status(self):
        self.assertEqual(self.start(), 0)
        with patch.object(jfr.subprocess, 'run', side_effect=self.run_jcmd):
            self.assertEqual(jfr.update(self.directory, 'finish'), 1)
        self.assertFalse(json.loads((self.directory / 'jfr-status.json').read_text())['recording_file_ok'])

    def test_repeated_start_preserves_original_evidence(self):
        self.assertEqual(self.start(), 0)
        before = (self.directory / 'jfr-status.json').read_bytes()
        self.assertEqual(jfr.start(self.directory), 1)
        self.assertEqual((self.directory / 'jfr-status.json').read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
