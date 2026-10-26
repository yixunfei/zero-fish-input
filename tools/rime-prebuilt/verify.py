"""Check reviewed default artifacts against the exact public build inputs."""
import hashlib
import json
from pathlib import Path
from generate import ASSET_VERSION, FORMATS, android_json, digest, generation_identity

ROOT = Path(__file__).resolve().parents[2]
PUBLIC = ROOT / 'engine-rime/build/generated/publicDictionaryAssets/rime'
PREBUILT = ROOT / 'engine-rime/prebuilt/rime/prebuilt'


def main():
    manifest = json.loads((PREBUILT / 'manifest.json').read_text(encoding='utf-8'))
    metadata = json.loads((PUBLIC / 'public-dictionaries.json').read_text(encoding='utf-8'))
    lock = json.loads((ROOT / 'tools/dictionaries/sources.lock.json').read_text(encoding='utf-8'))
    assert metadata['revision'] == lock['revision'] and metadata['files'] == lock['preparedFiles']
    source, generation = generation_identity(metadata)
    assert manifest['format'] == 1 and manifest['assetVersion'] == ASSET_VERSION
    assert manifest['grammar'] == 'none' and manifest['generation'] == generation
    assert manifest['sourceIdentity'] == source
    assert manifest['rime'] == '1.13.1'
    assert manifest['rimeCommit'] == '1c23358157934bd6e6d6981f0c0164f05393b497'
    assert manifest['binaryFormats'] == FORMATS
    installer = (ROOT / 'engine-rime/src/main/kotlin/dev/zeroinput/engine/rime/RimeAssetInstaller.kt').read_text(encoding='utf-8')
    assert f'const val ASSET_VERSION = "{ASSET_VERSION}"' in installer
    assert manifest['rimeCommit'] in installer
    upstream = (ROOT / 'third_party/librime/CMakeLists.txt').read_text(encoding='utf-8')
    assert f'set(rime_version {manifest["rime"]})' in upstream
    assert set(manifest['files']) == {name + '.xz' for name in FORMATS}
    assert len([name for name in metadata['files'] if name.startswith('dicts/')]) == 16
    assert manifest['publicDictionaryMetadataSha256'] == digest(PUBLIC / 'public-dictionaries.json')
    for name in metadata['files']:
        if name != 'wanxiang-lts-zh-hans.gram':
            assert digest(PUBLIC / name) == metadata['files'][name], name
    schema = json.loads((ROOT / 'engine-rime/src/main/assets/rime/zeroinput_pinyin.schema.yaml').read_text(encoding='utf-8'))
    schema.pop('grammar', None)
    sources = manifest['sourceFiles']
    assert hashlib.sha256(android_json(schema).encode()).hexdigest() == sources['zeroinput_pinyin.schema.yaml']
    assert digest(ROOT / 'engine-rime/src/main/assets/rime/default.yaml') == sources['default.yaml']
    dictionary = json.loads((PUBLIC / 'zeroinput_public.dict.yaml').read_text(encoding='utf-8').splitlines()[1])
    dictionary['version'] = generation
    assert hashlib.sha256(('---\n' + android_json(dictionary) + '\n...\n').encode()).hexdigest() == sources['zeroinput_public.dict.yaml']
    for name, record in manifest['files'].items():
        archive = PREBUILT / name
        assert archive.is_file() and archive.stat().st_size == record['archiveBytes']
        assert 0 < record['bytes'] <= 100_000_000
        assert 0 < record['archiveBytes'] <= 50_000_000
        assert digest(archive) == record['archiveSha256'], name
    print('Verified default generation:', generation)


if __name__ == '__main__':
    main()
