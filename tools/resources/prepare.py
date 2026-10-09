"""Build reproducible GitHub Release resources from already verified local inputs."""
import hashlib
import json
from pathlib import Path
import zipfile
import argparse

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'build/public-resources'
CATALOG = Path(__file__).with_name('catalog.json')
REPOSITORY = 'yixunfei/zero-fish-input'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def prepare(lts_only=False):
    lock = json.loads((ROOT / 'tools/dictionaries/sources.lock.json').read_text())
    model = lock['model']
    packs = {
        'wanxiang-lts': ('CC-BY-4.0', [
            ('rime/wanxiang-lts-zh-hans.gram', 'engine-rime/build/generated/publicDictionaryAssets/rime/wanxiang-lts-zh-hans.gram', model['size'], model['sha256']),
        ]),
        'handwriting': ('Apache-2.0 AND LGPL-2.1', [
            ('handwriting/model.onnx', 'build/handwriting-model/inference.onnx', 16534782, 'da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092'),
            ('handwriting/characters.txt', 'build/handwriting-model/characters.txt', 74012, 'd1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b'),
            ('handwriting/stroke-simplified.zsh', 'build/handwriting-stroke-model/stroke-simplified.zsh', 7016330, 'fdd47959e8cb95add75fc1e5bd10ff62e88b5d09fc6c6305d284d8f3df57667f'),
            ('handwriting/stroke-traditional.zsh', 'build/handwriting-stroke-model/stroke-traditional.zsh', 39052454, '7eaa62001987b03fa0ea24824b1a1203599064db905604026da8bc7e4e3b0288'),
        ]),
    }
    OUT.mkdir(parents=True, exist_ok=True)
    catalog = {'format': 1, 'packs': []}
    if lts_only:
        catalog['packs'] = [p for p in json.loads(CATALOG.read_text())['packs'] if p['id'] != 'wanxiang-lts']
        packs = {'wanxiang-lts': packs['wanxiang-lts']}
    for pack_id, (license_name, files) in packs.items():
        records = []
        for name, source, size, checksum in files:
            path = ROOT / source
            if path.stat().st_size != size or digest(path) != checksum:
                raise ValueError('Unverified resource: ' + name)
            records.append({'name': name, 'bytes': size, 'sha256': checksum})
        version = hashlib.sha256(json.dumps(records, sort_keys=True).encode()).hexdigest()[:16]
        filename = f'{pack_id}-{version}.zip'
        target = OUT / filename
        with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for (name, source, _, _) in files:
                info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                with (ROOT / source).open('rb') as src, archive.open(info, 'w') as dst:
                    import shutil
                    shutil.copyfileobj(src, dst, 1024 * 1024)
        catalog['packs'].append({'id': pack_id, 'version': version, 'license': license_name,
            'url': f'https://github.com/{REPOSITORY}/releases/download/resources-{version}/{filename}',
            'bytes': target.stat().st_size, 'sha256': digest(target), 'files': records})
    catalog['packs'].sort(key=lambda pack: pack['id'])
    CATALOG.write_text(json.dumps(catalog, indent=2) + '\n', encoding='utf-8')
    print('Prepared verified resource archives and catalog')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--lts-only', action='store_true')
    prepare(parser.parse_args().lts_only)
