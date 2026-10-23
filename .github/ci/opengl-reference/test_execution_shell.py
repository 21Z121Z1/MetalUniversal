"""Run the production workflow shell, including GitHub's inherited errexit."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import textwrap
import unittest

import test_verify_reference as oracle
from verify_reference import verify


class ExecutionShellTest(unittest.TestCase):
    def test_production_shell_records_and_preserves_gradle_exit(self):
        workflow = (Path(__file__).parents[2] / 'workflows/opengl-reference.yml').read_text()
        step = workflow.split('      - name: Run the stock OpenGL production client\n', 1)[1]
        script = textwrap.dedent(step.split('        run: |\n', 1)[1].split('\n      - name:', 1)[0])
        for code in (0, 1, 124):
            with self.subTest(exit_code=code):
                fixture = oracle.ReferenceOracleTest()
                fixture.setUp()
                try:
                    fixture.check()
                    root = fixture.root
                    output = root / 'build/opengl-reference'
                    output.mkdir(parents=True)
                    for name in ('reference.json', 'inputs.json', 'frame.rgba8', 'frame.png'):
                        shutil.copyfile(root / name, output / name)
                    gradle = root / 'gradlew'
                    gradle.write_text('#!/usr/bin/env bash\nprintf "stub Gradle exited %s\\n" "$STUB_EXIT"\nexit "$STUB_EXIT"\n')
                    gradle.chmod(0o755)
                    env = dict(os.environ, STUB_EXIT=str(code), REFERENCE_RENDERER='iris',
                               METALLUM_SOURCE_SHA='a' * 40, RUNNER_TEMP=str(root / 'runner'))
                    result = subprocess.run(['bash', '--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', script],
                                            cwd=root, env=env, capture_output=True, text=True, timeout=10)
                    self.assertEqual(result.returncode, code, result.stdout + result.stderr)
                    receipt = output / 'execution.json'
                    self.assertTrue(receipt.is_file(), 'Inherited errexit skipped the execution receipt')
                    self.assertEqual(json.loads(receipt.read_text()),
                                     {'exitCode': code, 'sourceSha': 'a' * 40, 'renderer': 'iris'})
                    self.assertIn(f'stub Gradle exited {code}', (output / 'client.log').read_text())
                    if code == 0:
                        verify(output, 'a' * 40, fixture.driver, fixture.jar)
                    else:
                        with self.assertRaises(ValueError):
                            verify(output, 'a' * 40, fixture.driver, fixture.jar)
                finally:
                    fixture.doCleanups()


if __name__ == '__main__':
    unittest.main()
