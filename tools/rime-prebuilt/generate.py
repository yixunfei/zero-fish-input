"""Build the exact managed/default Rime generation with the pinned deployer.

Run on a host with Docker: python tools/rime-prebuilt/generate.py
The source checkout is read-only except for the reviewed artifact directory.
"""
import argparse
import hashlib
import json
import lzma
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'engine-rime/prebuilt/rime/prebuilt'
BUILD = ROOT / 'build/rime-prebuilt-linux'
IMAGE = 'python@sha256:adafcc17694d715c905b4c7bebd96907a1fd5cf183395f0ebc4d3428bd22d92d'
FORMATS = {'zeroinput_public.table.bin': 'Rime::Table/4.0',
           'zeroinput_pinyin.prism.bin': 'Rime::Prism/3.0',
           'zeroinput_public.reverse.bin': 'Rime::Reverse/3.1'}


ASSET_VERSION = '11'


def android_json(value):
    """JSONObject's compact UTF-8 form, including its escaped forward slashes."""
    return json.dumps(value, ensure_ascii=False, separators=(',', ':')).replace('/', '\\/')


def generation_identity(metadata):
    files = metadata['files']
    # Kotlin Sequence.joinToString uses comma-space by default. Empty custom
    # dictionary and disabled/replacement segments add no separator at the end.
    source_text = ', '.join(name + files[name] for name in sorted(files))
    source = hashlib.sha256(source_text.encode()).hexdigest()[:24]
    generation = hashlib.sha256((ASSET_VERSION + source + 'none').encode()).hexdigest()[:24]
    return source, generation


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def checked(path, expected, limit=100_000_000):
    if not path.is_file() or not 0 < path.stat().st_size <= limit or digest(path) != expected:
        raise ValueError('Unreviewed public input: ' + path.name)


def prepare():
    public = ROOT / 'engine-rime/build/generated/publicDictionaryAssets/rime'
    metadata = json.loads((public / 'public-dictionaries.json').read_text(encoding='utf-8'))
    lock = json.loads((ROOT / 'tools/dictionaries/sources.lock.json').read_text(encoding='utf-8'))
    if metadata['revision'] != lock['revision'] or metadata['files'] != lock['preparedFiles']:
        raise ValueError('Prepared public dictionaries differ from the reviewed lock')
    if len([name for name in metadata['files'] if name.startswith('dicts/')]) != 16:
        raise ValueError('Expected the reviewed sixteen-table public dictionary')
    for name, expected in metadata['files'].items():
        if name == 'wanxiang-lts-zh-hans.gram':
            continue
        checked(public / name, expected)
    source = BUILD / 'input/shared'
    user = BUILD / 'input/user'
    if (BUILD / 'input').exists():
        shutil.rmtree(BUILD / 'input')
    shutil.copytree(ROOT / 'engine-rime/src/main/assets/rime', source,
                    ignore=shutil.ignore_patterns('prebuilt', 'essay.txt', 'luna_pinyin.dict.yaml'))
    shutil.copytree(public / 'dicts', source / 'dicts')
    shutil.copy2(public / 'zeroinput_public.dict.yaml', source)
    user.mkdir(parents=True)
    asset_identity, generation = generation_identity(metadata)
    # The Android installer writes the selected generation into the primary
    # dictionary header; librime includes these exact bytes in its checksum.
    dictionary = source / 'zeroinput_public.dict.yaml'
    dictionary_metadata = json.loads(dictionary.read_text(encoding='utf-8').splitlines()[1])
    dictionary_metadata['version'] = generation
    dictionary.write_text('---\n' + android_json(dictionary_metadata) + '\n...\n',
                          encoding='utf-8', newline='\n')
    schema = source / 'zeroinput_pinyin.schema.yaml'
    config = json.loads(schema.read_text(encoding='utf-8'))
    del config['grammar']
    # Android JSONObject escapes slashes; match the deployed bytes exactly.
    schema.write_text(android_json(config),
                      encoding='utf-8', newline='\n')
    for name in ('default.yaml', 'zeroinput_pinyin.schema.yaml'):
        (BUILD / ('input-' + name + '.sha256')).write_text(digest(source / name), encoding='ascii')
    deployer = BUILD / 'rime_deployer'
    subprocess.run([str(deployer), '--build', str(user), str(source), str(user / 'build')],
                   cwd=source, check=True, timeout=600)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    records = {}
    for name, header in FORMATS.items():
        compiled = user / 'build' / name
        if not compiled.is_file() or compiled.read_bytes()[:len(header)] != header.encode('ascii'):
            raise ValueError('Invalid compiled Rime format: ' + name)
        raw = compiled.read_bytes()
        archive = lzma.compress(raw, format=lzma.FORMAT_XZ, check=lzma.CHECK_CRC64, preset=6)
        (OUTPUT / (name + '.xz')).write_bytes(archive)
        records[name + '.xz'] = {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(),
                                'archiveBytes': len(archive), 'archiveSha256': hashlib.sha256(archive).hexdigest()}
        print(name, len(raw), '->', len(archive), 'bytes', flush=True)
    manifest = {'format': 1, 'assetVersion': ASSET_VERSION, 'sourceIdentity': asset_identity,
                'grammar': 'none', 'generation': generation, 'rime': '1.13.1',
                'publicDictionaryMetadataSha256': digest(public / 'public-dictionaries.json'),
                'rimeCommit': '1c23358157934bd6e6d6981f0c0164f05393b497',
                'binaryFormats': FORMATS, 'sourceFiles': {
                    'default.yaml': digest(source / 'default.yaml'),
                    'zeroinput_pinyin.schema.yaml': digest(schema),
                    'zeroinput_public.dict.yaml': digest(dictionary),
                }, 'files': records}
    (OUTPUT / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n',
                                         encoding='utf-8', newline='\n')
    print('Default generation:', generation, flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', action='store_true')
    args = parser.parse_args()
    if args.container:
        prepare()
        return
    subprocess.run(['docker', 'run', '--rm',
                    '--mount', f'type=bind,source={ROOT},target=/workspace,readonly',
                    '--mount', f'type=bind,source={ROOT / "build"},target=/workspace/build',
                    '--mount', f'type=bind,source={ROOT / "engine-rime/prebuilt"},target=/workspace/engine-rime/prebuilt',
                    '-w', '/workspace', IMAGE, 'sh', '-c',
                    'apt-get update -qq && apt-get install -y -qq --no-install-recommends '
                    'g++ make cmake ninja-build >/dev/null && '
                    'cmake -S tools/rime-prebuilt -B build/rime-prebuilt-linux -G Ninja '
                    '-DCMAKE_BUILD_TYPE=Release -DZEROINPUT_THIRD_PARTY_DIR=/workspace/third_party && '
                    'cmake --build build/rime-prebuilt-linux --target rime_deployer --parallel 4 && '
                    'python tools/rime-prebuilt/generate.py --container'], check=True)


if __name__ == '__main__':
    main()
