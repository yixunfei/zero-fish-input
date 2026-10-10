"""Restore the reviewed Mini derivative for reproducible host builds (never a device downloader)."""
import hashlib
import json
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
FILES = {
    'mini-int8/model.onnx': ('build/model-evaluation/android-assets/mini-int8/model.onnx',
                           '5fb4dbe2c618e8757258253e10481ea9181e8a7b9a8efea03ee70c3a5ca19446'),
    'mini-int8/vocab.txt': ('build/model-evaluation/mini/vocab.txt',
                          '45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c'),
}


def fetch():
    catalog = json.loads((ROOT / 'tools/resources/catalog.json').read_text(encoding='utf-8'))
    pack = next(item for item in catalog['packs'] if item['id'] == 'mini-int8')
    expected_url = ('https://github.com/yixunfei/zero-fish-input/releases/download/resources-'
                    + pack['version'] + '/mini-int8-' + pack['version'] + '.zip')
    if pack['url'] != expected_url or pack['bytes'] > 20_000_000:
        raise ValueError('Invalid Mini catalog')
    archive = ROOT / 'build/public-resources' / expected_url.split('/')[-1]
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        with urllib.request.urlopen(expected_url, timeout=120) as source:
            data = source.read(pack['bytes'] + 1)
        if len(data) != pack['bytes'] or hashlib.sha256(data).hexdigest() != pack['sha256']:
            raise ValueError('Invalid resource archive')
        archive.write_bytes(data)
    with archive.open('rb') as source:
        if archive.stat().st_size != pack['bytes'] or hashlib.file_digest(source, 'sha256').hexdigest() != pack['sha256']:
            raise ValueError('Invalid resource archive')
    with zipfile.ZipFile(archive) as source:
        if len(source.infolist()) != len(FILES) or set(source.namelist()) != set(FILES):
            raise ValueError('Unexpected resource entries')
        verified = []
        for name, (destination, checksum) in FILES.items():
            spec = next(item for item in pack['files'] if item['name'] == name)
            if source.getinfo(name).file_size != spec['bytes'] or spec['bytes'] > 16_000_000:
                raise ValueError('Oversized model')
            data = source.read(name)
            if hashlib.sha256(data).hexdigest() != checksum:
                raise ValueError('Unreviewed model')
            verified.append((ROOT / destination, data))
        for destination, data in verified:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)


if __name__ == '__main__':
    fetch()
