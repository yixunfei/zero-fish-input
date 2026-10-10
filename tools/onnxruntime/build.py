"""Build the pinned reduced Android CPU runtime. Never downloads code on a device."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from jni import build_jni

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'build/onnxruntime-src'
REVISION = '8c546c37b43caaca1fa25db430dab94b901cf277'
VERSION = '1.26.0-zero1'
ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86_64')
CONFIG = Path(__file__).with_name('required-operators.config')
MIRROR = ROOT / 'build/onnx-dependency-mirror'
AAR_HASH = '09c0780ae8d734ef2774bdf498b624729a855e6f9a8e488a0e7398a4e7396032'


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def download(url, path, checksum):
    if not path.is_file() or digest(path) != checksum:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(path.suffix + '.tmp')
        try:
            with urllib.request.urlopen(url, timeout=120) as source, temporary.open('wb') as output:
                shutil.copyfileobj(source, output)
            if digest(temporary) != checksum:
                raise ValueError('Unverified build dependency')
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)


def prepare_source():
    if not SOURCE.exists():
        subprocess.run(['git', 'clone', '--depth', '1', '--branch', 'v1.26.0',
                        'https://github.com/microsoft/onnxruntime.git', str(SOURCE)], check=True)
    actual = subprocess.check_output(['git', '-C', str(SOURCE), 'rev-parse', 'HEAD'], text=True).strip()
    if actual != REVISION:
        raise ValueError('Unexpected runtime source revision')
    # Do not hide local modifications behind a successful reduced build.
    if subprocess.check_output(['git', '-C', str(SOURCE), 'status', '--porcelain'], text=True).strip():
        raise ValueError('Runtime source checkout must be unmodified')
    for item in json.loads(Path(__file__).with_name('dependencies.lock.json').read_text()):
        download(item['url'], MIRROR / item['url'].removeprefix('https://'), item['sha256'])


def verify_fetched_dependencies(output):
    allowed = {item['sha256'] for item in json.loads(Path(__file__).with_name('dependencies.lock.json').read_text())}
    for archive in (output / 'MinSizeRel/_deps').glob('*-subbuild/*-populate-prefix/src/*'):
        if archive.is_file() and archive.suffix in ('.zip', '.gz', '.xz') and digest(archive) not in allowed:
            raise ValueError('Unreviewed native dependency archive')


def build(args):
    prepare_source()
    for abi in args.abi or ABIS:
        output = ROOT / 'build/onnx-reduced' / abi
        command = [sys.executable, str(SOURCE / 'tools/ci_build/build.py'), '--build_dir', str(output),
                   '--config', 'MinSizeRel', '--android', '--android_abi', abi, '--android_api', '26',
                   '--android_sdk_path', str(args.sdk), '--android_ndk_path', str(args.ndk),
                   '--cmake_generator', 'Ninja', '--skip_submodule_sync', '--skip_tests',
                   '--build_shared_lib', '--disable_ml_ops', '--disable_generation_ops',
                   '--include_ops_by_config', str(CONFIG), '--parallel', str(args.jobs),
                   '--cmake_deps_mirror_dir', str(MIRROR), '--cmake_extra_defines',
                   'CMAKE_MAKE_PROGRAM=' + str(args.ninja), 'onnxruntime_BUILD_UNIT_TESTS=OFF',
                   '--update', '--build']
        subprocess.run(command, cwd=ROOT, check=True)
        verify_fetched_dependencies(output)
        library = output / 'MinSizeRel/libonnxruntime.so'
        host = 'windows-x86_64' if os.name == 'nt' else 'linux-x86_64'
        strip = args.ndk / 'toolchains/llvm/prebuilt' / host / 'bin' / ('llvm-strip.exe' if os.name == 'nt' else 'llvm-strip')
        subprocess.run([str(strip), '--strip-unneeded', str(library)], check=True)
        jni = build_jni(SOURCE, args.ndk, args.ninja, abi)
        subprocess.run([str(strip), '--strip-unneeded', str(jni)], check=True)
        (output / 'verified.json').write_text(json.dumps({
            'revision': REVISION, 'config': digest(CONFIG), 'library': digest(library),
            'jni': digest(jni),
        }, indent=2) + '\n')
    if args.package:
        package()


def package():
    lock = json.loads(Path(__file__).with_name('runtime.lock.json').read_text())
    if (lock['version'], lock['revision'], lock['config']) != (VERSION, REVISION, digest(CONFIG)):
        raise ValueError('Runtime build does not match the reviewed lock')
    base = ROOT / 'build/onnx-reduced/original.aar'
    download('https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.26.0/onnxruntime-android-1.26.0.aar', base, AAR_HASH)
    libraries = {}
    for abi in ABIS:
        directory = ROOT / 'build/onnx-reduced' / abi
        library = directory / 'MinSizeRel/libonnxruntime.so'
        stamp = json.loads((directory / 'verified.json').read_text())
        jni = ROOT / 'build/onnx-jni' / abi / 'libonnxruntime4j_jni.so'
        if stamp != {'revision': REVISION, 'config': digest(CONFIG), 'library': digest(library), 'jni': digest(jni)}:
            raise ValueError('Stale runtime build: ' + abi)
        if digest(library) != lock['libraries'][abi] or digest(jni) != lock['jni'][abi]:
            raise ValueError('Runtime binaries need independent review: ' + abi)
        libraries[abi] = library
    output = ROOT / 'build/onnx-maven/dev/zeroinput/onnxruntime-reduced' / VERSION
    output.mkdir(parents=True, exist_ok=True)
    aar = output / ('onnxruntime-reduced-' + VERSION + '.aar')
    with zipfile.ZipFile(base) as original, zipfile.ZipFile(aar, 'w', zipfile.ZIP_DEFLATED) as target:
        for entry in original.infolist():
            if entry.filename.startswith('jni/x86/'):
                continue
            data = original.read(entry)
            if entry.filename.startswith('jni/') and entry.filename.endswith('/libonnxruntime.so'):
                data = libraries[entry.filename.split('/')[1]].read_bytes()
            if entry.filename.startswith('jni/') and entry.filename.endswith('/libonnxruntime4j_jni.so'):
                data = (ROOT / 'build/onnx-jni' / entry.filename.split('/')[1] / 'libonnxruntime4j_jni.so').read_bytes()
            info = zipfile.ZipInfo(entry.filename, (2026, 1, 1, 0, 0, 0))
            info.create_system = 0
            info.compress_type = zipfile.ZIP_DEFLATED
            target.writestr(info, data)
    if digest(aar) != lock['aar']:
        raise ValueError('Runtime AAR does not match the reviewed lock')
    aar.with_suffix('.pom').write_text(
        '<project><modelVersion>4.0.0</modelVersion><groupId>dev.zeroinput</groupId>'
        '<artifactId>onnxruntime-reduced</artifactId><version>' + VERSION + '</version>'
        '<packaging>aar</packaging></project>\n')
    (output / 'verification.json').write_text(json.dumps({'aar': digest(aar), 'config': digest(CONFIG),
        'revision': REVISION, 'libraries': {abi: digest(path) for abi, path in libraries.items()},
        'jni': {abi: digest(ROOT / 'build/onnx-jni' / abi / 'libonnxruntime4j_jni.so') for abi in ABIS}}, indent=2) + '\n')
    print('Verified reduced runtime: ' + str(aar))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--ninja', type=Path, required=True)
    parser.add_argument('--abi', action='append', choices=ABIS)
    parser.add_argument('--jobs', type=int, default=6)
    parser.add_argument('--package', action='store_true')
    build(parser.parse_args())
