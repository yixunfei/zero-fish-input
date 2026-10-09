"""Offline check of the complete prepared asset set against its reviewed source lock."""
import json
from prepare import OUTPUT, LOCK, TABLES, digest


def main():
    lock = json.loads(LOCK.read_text(encoding='utf-8'))
    manifest = json.loads((OUTPUT / 'public-dictionaries.json').read_text(encoding='utf-8'))
    assert lock['tables'] == TABLES
    assert manifest['revision'] == lock['revision']
    expected = {'dicts/' + name + '.dict.yaml' for name in TABLES}
    expected.update(('zeroinput_public.dict.yaml', 'wanxiang-lts-zh-hans.gram'))
    assert set(manifest['files']) == expected
    assert manifest['files'] == lock['preparedFiles']
    for name, checksum in manifest['files'].items():
        assert digest(OUTPUT / name) == checksum, name
    assert manifest['files']['wanxiang-lts-zh-hans.gram'] == lock['model']['sha256']
    assert (OUTPUT / 'wanxiang-lts-zh-hans.gram').stat().st_size == lock['model']['size']
    print('Verified complete dictionary/model asset set')


if __name__ == '__main__':
    main()
