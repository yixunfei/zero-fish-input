"""Prepare pinned public Wanxiang assets. Network access is explicit, never a build side effect."""
import argparse
import hashlib
import json
import shutil
import sys
import urllib.request
import unicodedata
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LOCK = Path(__file__).with_name('sources.lock.json')
CACHE = ROOT / 'build/dictionary-sources'
OUTPUT = ROOT / 'engine-rime/build/generated/publicDictionaryAssets/rime'
TABLES = ('zi jichu lianxiang cuoyin duoyin shici diming yixue huaxue yaopin '
          'mingren yiren wuzhong renming taifeng fangyan').split()


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def fetch(url, destination, expected=None, limit=600_000_000):
    if destination.is_file() and expected and digest(destination) == expected:
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix('.partial')
    try:
        request = urllib.request.Request(url, headers={'User-Agent': 'ZeroInput-dictionary-builder'})
        with urllib.request.urlopen(request, timeout=60) as response, temporary.open('wb') as output:
            total = 0
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                if total > limit:
                    raise ValueError('Source exceeds size limit')
                output.write(chunk)
        if expected and digest(temporary) != expected:
            raise ValueError('Source checksum mismatch')
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def api(path):
    request = urllib.request.Request('https://api.github.com/' + path,
                                     headers={'User-Agent': 'ZeroInput-dictionary-builder'})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def update_lock():
    release = api('repos/amzxyz/rime-wanxiang/releases/latest')
    revision = api('repos/amzxyz/rime-wanxiang/commits/' + release['tag_name'])['sha']
    model_release = api('repos/amzxyz/RIME-LMDG/releases/tags/LTS')
    model = next(a for a in model_release['assets'] if a['name'] == 'wanxiang-lts-zh-hans.gram')
    archive_url = 'https://codeload.github.com/amzxyz/rime-wanxiang/zip/' + revision
    archive = CACHE / (revision + '.zip')
    fetch(archive_url, archive)
    model_hash = model['digest'].removeprefix('sha256:')
    if len(model_hash) != 64:
        raise ValueError('Missing upstream model digest')
    lock = {'format': 1, 'version': release['tag_name'], 'revision': revision,
            'license': 'CC-BY-4.0', 'tables': TABLES,
            'archive': {'url': archive_url, 'sha256': digest(archive)},
            'model': {'url': model['browser_download_url'], 'sha256': model_hash,
                      'size': model['size'], 'assetId': model['id']}}
    LOCK.write_text(json.dumps(lock, indent=2) + '\n', encoding='utf-8')
    return lock


def normalize_table(content, destination):
    """Remove tone marks from codes only; preserve all words and source frequencies."""
    import io
    in_data = False
    with io.TextIOWrapper(content, encoding='utf-8') as source, destination.open('w', encoding='utf-8', newline='\n') as output:
        for line in source:
            if len(line) > 8192:
                raise ValueError('Oversized dictionary row')
            if in_data and line.strip() and not line.startswith('#'):
                fields = line.rstrip('\r\n').split('\t')
                if len(fields) < 2:
                    raise ValueError('Missing public reading')
                code = unicodedata.normalize('NFD', fields[1]).replace('u\u0308', 'v')
                fields[1] = ''.join(c for c in code if unicodedata.category(c) != 'Mn')
                if any(c not in 'abcdefghijklmnopqrstuvwxyz ' for c in fields[1]):
                    raise ValueError('Unsupported public reading')
                output.write('\t'.join(fields) + '\n')
            else:
                output.write(line)
            if line.strip() == '...':
                in_data = True


def prepare(lock, refresh=False):
    archive = CACHE / (lock['revision'] + '.zip')
    fetch(lock['archive']['url'], archive, lock['archive']['sha256'])
    model = CACHE / (lock['model']['sha256'] + '.gram')
    print('Preparing full dictionary and grammar model', flush=True)
    fetch(lock['model']['url'], model, lock['model']['sha256'])
    OUTPUT.mkdir(parents=True, exist_ok=True)
    records = {}
    prefix = 'rime-wanxiang-' + lock['revision'] + '/'
    with zipfile.ZipFile(archive) as source:
        for table in lock['tables']:
            name = 'dicts/' + table + '.dict.yaml'
            info = source.getinfo(prefix + name)
            if info.file_size > 100_000_000:
                raise ValueError('Dictionary exceeds limit')
            destination = OUTPUT / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            with source.open(info) as content:
                normalize_table(content, destination)
            records[name] = digest(destination)
        license_text = source.read(prefix + 'LICENSE')
        (ROOT / 'LICENSES/wanxiang-CC-BY-4.0.txt').write_bytes(license_text)
    dictionary = OUTPUT / 'zeroinput_public.dict.yaml'
    header = {'name': 'zeroinput_public', 'version': lock['revision'], 'sort': 'by_weight',
              'use_preset_vocabulary': False,
              'import_tables': ['dicts/' + name for name in lock['tables']]}
    dictionary.write_text('---\n' + json.dumps(header) + '\n...\n', encoding='utf-8', newline='\n')
    records[dictionary.name] = digest(dictionary)
    target_model = OUTPUT / 'wanxiang-lts-zh-hans.gram'
    if not target_model.is_file() or digest(target_model) != lock['model']['sha256']:
        shutil.copyfile(model, target_model)
    records[target_model.name] = lock['model']['sha256']
    manifest = {'version': lock['version'], 'revision': lock['revision'], 'files': records}
    if refresh:
        lock['preparedFiles'] = records
        LOCK.write_text(json.dumps(lock, indent=2) + '\n', encoding='utf-8', newline='\n')
    elif records != lock.get('preparedFiles'):
        raise ValueError('Prepared artifacts differ from the reviewed lock')
    (OUTPUT / 'public-dictionaries.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8', newline='\n')
    print('Prepared', len(records), 'verified files', flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--refresh', action='store_true', help='Resolve upstream and update reviewed lock')
    args = parser.parse_args()
    lock = update_lock() if args.refresh else json.loads(LOCK.read_text(encoding='utf-8'))
    prepare(lock, args.refresh)


if __name__ == '__main__':
    main()
