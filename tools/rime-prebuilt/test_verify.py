"""The build gate must reject unreviewed artifacts even with Python optimization."""
import json
from pathlib import Path
import subprocess
import shutil
import sys
import tempfile
import unittest

import verify


class VerificationTest(unittest.TestCase):
    def test_invalid_manifest_is_rejected_with_and_without_optimization(self):
        manifest = json.loads((verify.PREBUILT / 'manifest.json').read_text(encoding='utf-8'))
        manifest['rimeCommit'] = 'unreviewed'
        with tempfile.TemporaryDirectory(prefix='zeroinput-prebuilt-') as directory:
            (Path(directory) / 'manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
            script = (
                'import sys; from pathlib import Path; '
                'sys.path.insert(0, sys.argv[1]); import verify; '
                'verify.PREBUILT = Path(sys.argv[2]); verify.main()'
            )
            for flags in ([], ['-O']):
                with self.subTest(flags=flags):
                    result = subprocess.run(
                        [sys.executable, *flags, '-c', script, str(Path(__file__).parent), directory],
                        capture_output=True, text=True, check=False,
                    )
                    self.assertNotEqual(0, result.returncode, result.stdout)

    def test_archive_hash_mismatch_is_rejected_with_optimization(self):
        with tempfile.TemporaryDirectory(prefix='zeroinput-prebuilt-') as directory:
            target = Path(directory) / 'artifacts'
            shutil.copytree(verify.PREBUILT, target)
            manifest_path = target / 'manifest.json'
            manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
            next(iter(manifest['files'].values()))['archiveSha256'] = '0' * 64
            manifest_path.write_text(json.dumps(manifest), encoding='utf-8')
            result = subprocess.run(
                [sys.executable, '-O', '-c',
                 'import sys; from pathlib import Path; sys.path.insert(0, sys.argv[1]); '
                 'import verify; verify.PREBUILT = Path(sys.argv[2]); verify.main()',
                 str(Path(__file__).parent), str(target)],
                capture_output=True, text=True, check=False,
            )
            self.assertNotEqual(0, result.returncode, result.stdout)


if __name__ == '__main__':
    unittest.main()
