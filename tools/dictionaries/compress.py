"""Losslessly package reviewed tables; original bytes/hashes remain the runtime contract."""
import hashlib
import json
import lzma
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'engine-rime/build/generated/publicDictionaryAssets/rime'
TARGET = ROOT / 'engine-rime/build/generated/compressedDictionaryAssets/rime'


def prepare():
    metadata = json.loads((SOURCE / 'public-dictionaries.json').read_text(encoding='utf-8'))
    records = {}
    (TARGET / 'dicts').mkdir(parents=True, exist_ok=True)
    for name, checksum in sorted(metadata['files'].items()):
        if not name.startswith('dicts/'):
            continue
        source = (SOURCE / name).read_bytes()
        if hashlib.sha256(source).hexdigest() != checksum:
            raise ValueError('Unverified dictionary')
        data = lzma.compress(source, format=lzma.FORMAT_XZ, check=lzma.CHECK_CRC64, preset=6)
        (TARGET / (name + '.xz')).write_bytes(data)
        records[name] = {'bytes': len(source), 'sha256': checksum,
                         'archiveBytes': len(data), 'archiveSha256': hashlib.sha256(data).hexdigest()}
    if len(records) != 16:
        raise ValueError('Incomplete core tables')
    (TARGET / 'dictionary-archives.json').write_text(
        json.dumps({'format': 1, 'files': records}, indent=2) + '\n', encoding='utf-8', newline='\n')


if __name__ == '__main__':
    prepare()
