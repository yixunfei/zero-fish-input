"""Offline negative tests for the APK supply-chain boundary."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location('verify_apk', Path(__file__).with_name('verify-apk.py'))
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class RuntimeIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.previous = VERIFIER.ROOT
        VERIFIER.ROOT = self.root
        self.write_json('tools/dictionaries/sources.lock.json', {'tables': []})
        self.write_json('tools/resources/catalog.json', {'packs': []})
        self.runtime = b'reviewed public runtime fixture'
        self.jni = b'reviewed public JNI fixture'
        self.lock = {'libraries': {'x86_64': self.digest(self.runtime)}, 'jni': {'x86_64': self.digest(self.jni)}}
        self.write_json('tools/onnxruntime/runtime.lock.json', self.lock)

    def tearDown(self):
        VERIFIER.ROOT = self.previous
        self.directory.cleanup()

    @staticmethod
    def digest(data):
        return hashlib.sha256(data).hexdigest()

    def write_json(self, name, data):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data))

    def apk(self, runtime, jni, abi='x86_64'):
        path = self.root / 'fixture.apk'
        with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('assets/glide-zh.tsv', b'public fixture')
            if runtime is not None:
                archive.writestr(f'lib/{abi}/libonnxruntime.so', runtime)
            if jni is not None:
                archive.writestr(f'lib/{abi}/libonnxruntime4j_jni.so', jni)
        return path

    def test_reviewed_libraries_need_no_generated_verification_stamp(self):
        VERIFIER.verify(self.apk(self.runtime, self.jni), 'lite')

    def test_tampered_library_and_matching_generated_stamp_still_fail(self):
        for runtime, jni in [(b'tampered', self.jni), (self.runtime, b'tampered')]:
            self.write_json('build/onnx-maven/dev/zeroinput/onnxruntime-reduced/1.26.0-zero1/verification.json',
                            {'libraries': {'x86_64': self.digest(runtime)}, 'jni': {'x86_64': self.digest(jni)}})
            with self.assertRaises(AssertionError):
                VERIFIER.verify(self.apk(runtime, jni), 'lite')

    def test_missing_or_unsupported_runtime_is_rejected(self):
        for runtime, jni in [(None, self.jni), (self.runtime, None), (None, None)]:
            with self.assertRaises(AssertionError):
                VERIFIER.verify(self.apk(runtime, jni), 'lite')
        with self.assertRaises(AssertionError):
            VERIFIER.verify(self.apk(self.runtime, self.jni, 'unknown'), 'lite')


if __name__ == '__main__':
    unittest.main()
