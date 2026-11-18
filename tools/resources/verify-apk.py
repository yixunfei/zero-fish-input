"""Check full/lite APK contents and reject incremental ZIP space left behind."""
import argparse
import json
from pathlib import Path
import zipfile
import hashlib
import lzma

ROOT = Path(__file__).resolve().parents[2]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify_prebuilt(archive, names):
    root = ROOT / 'engine-rime/prebuilt/rime/prebuilt'
    manifest = (root / 'manifest.json').read_bytes()
    metadata = json.loads(manifest)
    expected = {'zeroinput_public.table.bin.xz', 'zeroinput_public.reverse.bin.xz',
                'zeroinput_pinyin.prism.bin.xz'}
    require(set(metadata['files']) == expected, 'Unexpected reviewed prebuilt file set')
    prefix = 'assets/rime/prebuilt/'
    require({name for name in names if name.startswith(prefix)} ==
            {prefix + name for name in expected | {'manifest.json'}}, 'Incomplete prebuilt startup assets')
    require(archive.read(prefix + 'manifest.json') == manifest, 'Unreviewed prebuilt manifest')
    for name in expected:
        record = metadata['files'][name]
        entry = archive.getinfo(prefix + name)
        require(entry.compress_type == zipfile.ZIP_STORED, 'Prebuilt XZ was double-compressed')
        require(entry.file_size == record['archiveBytes'] and
                hashlib.sha256(archive.read(entry)).hexdigest() == record['archiveSha256'],
                'Damaged prebuilt startup asset')


REQUIRED_LIBRARIES = {'libzeroinput_rime.so', 'libc++_shared.so',
                      'libonnxruntime.so', 'libonnxruntime4j_jni.so'}
SUPPORTED_ABIS = {'arm64-v8a', 'armeabi-v7a', 'x86_64'}


def verify_native_libraries(names, expected_abis=None):
    libraries = {}
    for name in names:
        parts = name.split('/')
        if len(parts) == 3 and parts[0] == 'lib' and parts[2].endswith('.so'):
            libraries.setdefault(parts[1], set()).add(parts[2])
    expected = set(expected_abis) if expected_abis is not None else set(libraries)
    require(expected and expected <= SUPPORTED_ABIS and set(libraries) == expected,
            'Missing or unsupported native ABI')
    for abi in sorted(expected):
        require(REQUIRED_LIBRARIES <= libraries[abi],
                f'Missing required native libraries for {abi}: ' +
                ', '.join(sorted(REQUIRED_LIBRARIES - libraries[abi])))


def verify(path, bundle, expected_abis=None):
    lock = json.loads((ROOT / 'tools/dictionaries/sources.lock.json').read_text(encoding='utf-8'))
    catalog = json.loads((ROOT / 'tools/resources/catalog.json').read_text(encoding='utf-8'))
    runtime = json.loads((ROOT / 'tools/onnxruntime/runtime.lock.json').read_text())
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        names = {entry.filename for entry in entries}
        require(len(names) == len(entries), 'Duplicate APK entries')
        verify_native_libraries(names, expected_abis)
        runtime_abis = {name.split('/')[1] for name in names if name.startswith('lib/') and name.endswith('/libonnxruntime.so')}
        jni_abis = {name.split('/')[1] for name in names if name.startswith('lib/') and name.endswith('/libonnxruntime4j_jni.so')}
        require(runtime_abis and runtime_abis == jni_abis and runtime_abis <= runtime['libraries'].keys(),
                'Missing or unsupported runtime ABI')
        verify_prebuilt(archive, names)
        expected = {'assets/rime/dicts/' + name + '.dict.yaml.xz' for name in lock['tables']}
        require({name for name in names if name.startswith('assets/rime/dicts/')} == expected, 'Incorrect core tables')
        for name in expected:
            require(archive.getinfo(name).compress_type == zipfile.ZIP_STORED, 'XZ table was double-compressed')
            data = lzma.decompress(archive.read(name))
            original = name.removeprefix('assets/rime/').removesuffix('.xz')
            require(hashlib.sha256(data).hexdigest() == lock['preparedFiles'][original], 'Unverified core table')
        for entry in entries:
            if entry.filename.startswith('lib/') and entry.filename.endswith('.so'):
                require(entry.compress_type == zipfile.ZIP_DEFLATED, 'Native library is not compressed')
                if entry.filename.endswith('/libonnxruntime.so'):
                    abi = entry.filename.split('/')[1]
                    require(hashlib.sha256(archive.read(entry)).hexdigest() == runtime['libraries'][abi], 'Unverified ONNX runtime')
                if entry.filename.endswith('/libonnxruntime4j_jni.so'):
                    abi = entry.filename.split('/')[1]
                    require(hashlib.sha256(archive.read(entry)).hexdigest() == runtime['jni'][abi], 'Unverified JNI binding')
        bundled_packs = {'mini-int8'} if bundle == 'full' else set()
        for pack in catalog['packs']:
            for spec in pack['files']:
                name = 'assets/' + spec['name']
                require((name in names) == (pack['id'] in bundled_packs), 'Incorrect optional resource contents')
                if pack['id'] in bundled_packs:
                    require(archive.getinfo(name).file_size == spec['bytes'] and
                            hashlib.sha256(archive.read(name)).hexdigest() == spec['sha256'], 'Unverified optional resource')
        require('assets/rime/luna_pinyin.dict.yaml' not in names and 'assets/rime/essay.txt' not in names,
                'Obsolete source dictionaries were packaged')
        require('assets/glide-zh.tsv' in names, 'Missing offline glide index')
        require(('assets/mini-int8/model.onnx' in names) == (bundle == 'full'), 'Incorrect full/lite model set')
        emoji_images = [name for name in names if name.startswith('assets/emoji/18.0/images/')]
        require(len(emoji_images) == 3972, 'Emoji artwork set is incomplete')
        payload = sum(entry.compress_size for entry in entries)
        require(path.stat().st_size - payload < 8 * 1024 * 1024, 'APK retains obsolete ZIP space; rebuild packaging')
    print(f'{bundle}: {path.name}: {path.stat().st_size} bytes; contents verified')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('bundle', choices=['full', 'lite'])
    parser.add_argument('apk', type=Path)
    parser.add_argument('--abis', nargs='+', choices=sorted(SUPPORTED_ABIS))
    args = parser.parse_args()
    verify(args.apk, args.bundle, args.abis)
