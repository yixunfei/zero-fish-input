"""Check reviewed default artifacts against the exact public build inputs."""
import hashlib
import json
from pathlib import Path
from generate import ASSET_VERSION, FORMATS, android_json, digest, generation_identity

ROOT = Path(__file__).resolve().parents[2]
PUBLIC = ROOT / 'engine-rime/build/generated/publicDictionaryAssets/rime'
PREBUILT = ROOT / 'engine-rime/prebuilt/rime/prebuilt'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    manifest = json.loads((PREBUILT / 'manifest.json').read_text(encoding='utf-8'))
    metadata = json.loads((PUBLIC / 'public-dictionaries.json').read_text(encoding='utf-8'))
    lock = json.loads((ROOT / 'tools/dictionaries/sources.lock.json').read_text(encoding='utf-8'))
    require(metadata['revision'] == lock['revision'] and metadata['files'] == lock['preparedFiles'],
            'Prepared dictionaries differ from the reviewed lock')
    source, generation = generation_identity(metadata)
    require(manifest['format'] == 1 and manifest['assetVersion'] == ASSET_VERSION, 'Invalid artifact version')
    require(manifest['grammar'] == 'none' and manifest['generation'] == generation, 'Invalid default generation')
    require(manifest['sourceIdentity'] == source, 'Invalid public source identity')
    require(manifest['rime'] == '1.13.1', 'Unreviewed Rime version')
    require(manifest['rimeCommit'] == '1c23358157934bd6e6d6981f0c0164f05393b497', 'Unreviewed Rime commit')
    require(manifest['binaryFormats'] == FORMATS, 'Unreviewed binary formats')
    installer = (ROOT / 'engine-rime/src/main/kotlin/dev/zeroinput/engine/rime/RimeAssetInstaller.kt').read_text(encoding='utf-8')
    require(f'const val ASSET_VERSION = "{ASSET_VERSION}"' in installer, 'Installer asset version mismatch')
    require(manifest['rimeCommit'] in installer, 'Installer Rime commit mismatch')
    upstream = (ROOT / 'third_party/librime/CMakeLists.txt').read_text(encoding='utf-8')
    require(f'set(rime_version {manifest["rime"]})' in upstream, 'Upstream Rime version mismatch')
    require(set(manifest['files']) == {name + '.xz' for name in FORMATS}, 'Unexpected prebuilt file set')
    require(len([name for name in metadata['files'] if name.startswith('dicts/')]) == 16, 'Unexpected table count')
    require(manifest['publicDictionaryMetadataSha256'] == digest(PUBLIC / 'public-dictionaries.json'),
            'Public dictionary metadata checksum mismatch')
    for name in metadata['files']:
        if name != 'wanxiang-lts-zh-hans.gram':
            require(digest(PUBLIC / name) == metadata['files'][name], 'Public dictionary checksum mismatch: ' + name)
    schema = json.loads((ROOT / 'engine-rime/src/main/assets/rime/zeroinput_pinyin.schema.yaml').read_text(encoding='utf-8'))
    schema.pop('grammar', None)
    sources = manifest['sourceFiles']
    require(hashlib.sha256(android_json(schema).encode()).hexdigest() == sources['zeroinput_pinyin.schema.yaml'],
            'Normalized schema checksum mismatch')
    require(digest(ROOT / 'engine-rime/src/main/assets/rime/default.yaml') == sources['default.yaml'],
            'Default configuration checksum mismatch')
    dictionary = json.loads((PUBLIC / 'zeroinput_public.dict.yaml').read_text(encoding='utf-8').splitlines()[1])
    dictionary['version'] = generation
    require(hashlib.sha256(('---\n' + android_json(dictionary) + '\n...\n').encode()).hexdigest() == sources['zeroinput_public.dict.yaml'],
            'Primary dictionary checksum mismatch')
    for name, record in manifest['files'].items():
        archive = PREBUILT / name
        require(archive.is_file() and archive.stat().st_size == record['archiveBytes'], 'Missing or truncated archive: ' + name)
        require(0 < record['bytes'] <= 100_000_000, 'Invalid decoded size: ' + name)
        require(0 < record['archiveBytes'] <= 50_000_000, 'Invalid archive size: ' + name)
        require(digest(archive) == record['archiveSha256'], 'Archive checksum mismatch: ' + name)
    print('Verified default generation:', generation)


if __name__ == '__main__':
    main()
