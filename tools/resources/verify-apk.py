"""Check full/lite APK contents and reject incremental ZIP space left behind."""
import argparse
import json
from pathlib import Path
import zipfile
import hashlib
import lzma

ROOT = Path(__file__).resolve().parents[2]


def verify(path, bundle):
    lock = json.loads((ROOT / 'tools/dictionaries/sources.lock.json').read_text(encoding='utf-8'))
    catalog = json.loads((ROOT / 'tools/resources/catalog.json').read_text(encoding='utf-8'))
    runtime = json.loads((ROOT / 'tools/onnxruntime/runtime.lock.json').read_text())
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        names = {entry.filename for entry in entries}
        assert len(names) == len(entries), 'Duplicate APK entries'
        runtime_abis = {name.split('/')[1] for name in names if name.startswith('lib/') and name.endswith('/libonnxruntime.so')}
        jni_abis = {name.split('/')[1] for name in names if name.startswith('lib/') and name.endswith('/libonnxruntime4j_jni.so')}
        assert runtime_abis and runtime_abis == jni_abis and runtime_abis <= runtime['libraries'].keys(), 'Missing or unsupported runtime ABI'
        expected = {'assets/rime/dicts/' + name + '.dict.yaml.xz' for name in lock['tables']}
        assert {name for name in names if name.startswith('assets/rime/dicts/')} == expected
        for name in expected:
            assert archive.getinfo(name).compress_type == zipfile.ZIP_STORED, 'XZ table was double-compressed'
            data = lzma.decompress(archive.read(name))
            original = name.removeprefix('assets/rime/').removesuffix('.xz')
            assert hashlib.sha256(data).hexdigest() == lock['preparedFiles'][original]
        for entry in entries:
            if entry.filename.startswith('lib/') and entry.filename.endswith('.so'):
                assert entry.compress_type == zipfile.ZIP_DEFLATED, 'Native library is not compressed'
                if entry.filename.endswith('/libonnxruntime.so'):
                    abi = entry.filename.split('/')[1]
                    assert hashlib.sha256(archive.read(entry)).hexdigest() == runtime['libraries'][abi], 'Unverified ONNX runtime'
                if entry.filename.endswith('/libonnxruntime4j_jni.so'):
                    abi = entry.filename.split('/')[1]
                    assert hashlib.sha256(archive.read(entry)).hexdigest() == runtime['jni'][abi], 'Unverified JNI binding'
        for pack in catalog['packs']:
            for spec in pack['files']:
                name = 'assets/' + spec['name']
                assert (name in names) == (bundle == 'full'), 'Incorrect optional resource contents'
                if bundle == 'full':
                    assert archive.getinfo(name).file_size == spec['bytes']
        assert 'assets/rime/luna_pinyin.dict.yaml' not in names
        assert 'assets/rime/essay.txt' not in names
        assert 'assets/glide-zh.tsv' in names
        assert ('assets/mini-int8/model.onnx' in names) == (bundle == 'full')
        payload = sum(entry.compress_size for entry in entries)
        assert path.stat().st_size - payload < 8 * 1024 * 1024, 'APK retains obsolete ZIP space; rebuild packaging'
    print(f'{bundle}: {path.name}: {path.stat().st_size} bytes; contents verified')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('bundle', choices=['full', 'lite'])
    parser.add_argument('apk', type=Path)
    args = parser.parse_args()
    verify(args.apk, args.bundle)
