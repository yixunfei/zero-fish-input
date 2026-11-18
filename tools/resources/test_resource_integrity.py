"""Offline negative tests for the APK supply-chain boundary."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import subprocess
import sys
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location('verify_apk', Path(__file__).with_name('verify-apk.py'))
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class NativeLibrarySetTest(unittest.TestCase):
    def names(self, abis):
        return {f'lib/{abi}/{library}' for abi in abis for library in VERIFIER.REQUIRED_LIBRARIES}

    def test_three_complete_abis_pass(self):
        VERIFIER.verify_native_libraries(self.names(VERIFIER.SUPPORTED_ABIS), VERIFIER.SUPPORTED_ABIS)

    def test_every_required_library_is_checked_in_every_abi(self):
        complete = self.names(VERIFIER.SUPPORTED_ABIS)
        for abi in VERIFIER.SUPPORTED_ABIS:
            for library in VERIFIER.REQUIRED_LIBRARIES:
                with self.subTest(abi=abi, library=library), self.assertRaisesRegex(ValueError, 'Missing required'):
                    VERIFIER.verify_native_libraries(complete - {f'lib/{abi}/{library}'}, VERIFIER.SUPPORTED_ABIS)

    def test_entire_missing_abi_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'native ABI'):
            VERIFIER.verify_native_libraries(self.names({'x86_64'}), VERIFIER.SUPPORTED_ABIS)


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
        self.prebuilt = b'reviewed public prebuilt fixture'
        self.prebuilt_files = ['zeroinput_public.table.bin.xz', 'zeroinput_public.reverse.bin.xz',
                               'zeroinput_pinyin.prism.bin.xz']
        self.prebuilt_manifest = {'files': {name: {'archiveBytes': len(self.prebuilt),
                                                  'archiveSha256': self.digest(self.prebuilt)}
                                           for name in self.prebuilt_files}}
        self.write_json('engine-rime/prebuilt/rime/prebuilt/manifest.json', self.prebuilt_manifest)

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

    def apk(self, runtime, jni, abi='x86_64', prebuilt=True, prebuilt_data=None):
        path = self.root / 'fixture.apk'
        with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as archive:
            if prebuilt:
                archive.writestr('assets/rime/prebuilt/manifest.json', json.dumps(self.prebuilt_manifest))
                for name in self.prebuilt_files:
                    archive.writestr('assets/rime/prebuilt/' + name,
                                     self.prebuilt if prebuilt_data is None else prebuilt_data,
                                     compress_type=zipfile.ZIP_STORED)
            archive.writestr('assets/glide-zh.tsv', b'public fixture')
            for index in range(3972):
                archive.writestr(f'assets/emoji/18.0/images/{index}.webp', b'public artwork fixture')
            archive.writestr(f'lib/{abi}/libzeroinput_rime.so', b'public Rime fixture')
            archive.writestr(f'lib/{abi}/libc++_shared.so', b'public C++ fixture')
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
            with self.assertRaises(ValueError):
                VERIFIER.verify(self.apk(runtime, jni), 'lite')

    def test_missing_or_unsupported_runtime_is_rejected(self):
        for runtime, jni in [(None, self.jni), (self.runtime, None), (None, None)]:
            with self.assertRaises(ValueError):
                VERIFIER.verify(self.apk(runtime, jni), 'lite')
        with self.assertRaises(ValueError):
            VERIFIER.verify(self.apk(self.runtime, self.jni, 'unknown'), 'lite')

    def test_missing_or_changed_prebuilt_assets_are_rejected(self):
        for options in ({'prebuilt': False}, {'prebuilt_data': b'changed'}):
            with self.assertRaises(ValueError):
                VERIFIER.verify(self.apk(self.runtime, self.jni, **options), 'lite')

    def test_integrity_gate_cannot_be_disabled_by_python_optimization(self):
        path = self.apk(b'tampered', self.jni)
        script = ('import sys; from pathlib import Path; '
                  'sys.path.insert(0, sys.argv[1]); import test_resource_integrity as test; '
                  'test.VERIFIER.ROOT = Path(sys.argv[2]); '
                  'test.VERIFIER.verify(Path(sys.argv[3]), "lite")')
        result = subprocess.run([sys.executable, '-O', '-c', script, str(Path(__file__).parent),
                                 str(self.root), str(path)], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Unverified ONNX runtime', result.stderr)


if __name__ == '__main__':
    unittest.main()
