"""Prepare pinned public Wanxiang assets. Network access is explicit, never a build side effect."""
import argparse
import hashlib
import json
import os
import shutil
import tempfile
import urllib.parse
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
DATA_REPOSITORY = 'amzxyz/rime-wanxiang-data'
RETAINED_TABLES = {'lianxiang', 'renming'}


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def fetch(url, destination, expected=None, limit=600_000_000, expected_size=None):
    if (destination.is_file() and expected and digest(destination) == expected
            and (expected_size is None or destination.stat().st_size == expected_size)):
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix('.partial')
    try:
        headers = {'User-Agent': 'ZeroInput-dictionary-builder'}
        if urllib.parse.urlparse(url).hostname == 'api.github.com':
            headers['Accept'] = 'application/octet-stream'
        request = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(request, timeout=60) as response, temporary.open('wb') as output:
            total = 0
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                if total > limit or (expected_size is not None and total > expected_size):
                    raise ValueError('Source exceeds size limit')
                output.write(chunk)
        if expected_size is not None and total != expected_size:
            raise ValueError('Source size mismatch')
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


def check_revision(revision):
    if not isinstance(revision, str) or len(revision) != 40 or any(c not in '0123456789abcdef' for c in revision):
        raise ValueError('Invalid dictionary revision')


def source_archives(lock):
    """Every output table has exactly one explicit, hash-pinned source owner."""
    if lock['tables'] != TABLES:
        raise ValueError('Expected the complete sixteen-table dictionary')
    retained = lock.get('retainedSources', [])
    reserved = [table for source in retained for table in source['tables']]
    sources = [{'repository': lock.get('repository', 'amzxyz/rime-wanxiang'),
                'revision': lock['revision'], 'archive': lock['archive'],
                'tables': [table for table in TABLES if table not in reserved]}, *retained]
    assigned = []
    for source in sources:
        if source['repository'] not in ('amzxyz/rime-wanxiang', DATA_REPOSITORY):
            raise ValueError('Unreviewed dictionary repository')
        if not source['tables']:
            raise ValueError('Empty dictionary source')
        check_revision(source['revision'])
        assigned.extend(source['tables'])
    if sorted(assigned) != sorted(TABLES):
        raise ValueError('Duplicate, unknown or missing source table')
    return sources


def update_lock():
    """Resolve a proposal in memory; never replace the reviewed lock on failure."""
    previous = json.loads(LOCK.read_text(encoding='utf-8'))
    retained = []
    for source in source_archives(previous):
        selected = [table for table in source['tables'] if table in RETAINED_TABLES]
        if selected:
            retained.append({**source, 'tables': selected})
    revision = api('repos/' + DATA_REPOSITORY + '/commits/wanxiang')['sha']
    check_revision(revision)
    model_release = api('repos/' + DATA_REPOSITORY + '/releases/tags/LTS')
    model = next(a for a in model_release['assets'] if a['name'] == 'wanxiang-lts-zh-hans.gram')
    archive_url = 'https://codeload.github.com/' + DATA_REPOSITORY + '/zip/' + revision
    archive = CACHE / (revision + '.zip')
    fetch(archive_url, archive)
    model_hash = model['digest'].removeprefix('sha256:')
    if len(model_hash) != 64 or any(c not in '0123456789abcdef' for c in model_hash):
        raise ValueError('Missing upstream model digest')
    asset_url = f'https://api.github.com/repos/{DATA_REPOSITORY}/releases/assets/{model["id"]}'
    if (type(model['id']) is not int or model['id'] <= 0 or model['url'] != asset_url
            or type(model['size']) is not int or not 0 < model['size'] <= 600_000_000):
        raise ValueError('Invalid upstream model asset')
    lock = {'format': 1, 'version': 'data-' + revision[:12], 'revision': revision,
            'repository': DATA_REPOSITORY, 'retainedSources': retained,
            'license': 'CC-BY-4.0', 'tables': TABLES,
            'archive': {'url': archive_url, 'sha256': digest(archive)},
            'model': {'url': asset_url, 'sha256': model_hash,
                      'size': model['size'], 'assetId': model['id']}}
    source_archives(lock)
    return lock


def normalize_table(content, destination):
    """Remove tone marks from codes only; preserve all words and source frequencies."""
    import io
    in_data = False
    entries = 0
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
                entries += 1
            else:
                output.write(line)
            if line.strip() == '...':
                in_data = True
    if entries == 0:
        raise ValueError('Empty dictionary table')


def prepare_tables(lock, staging):
    records = {}
    expected_license = (ROOT / 'LICENSES/wanxiang-CC-BY-4.0.txt').read_bytes()
    total_size = 0
    for spec in source_archives(lock):
        archive = CACHE / (spec['revision'] + '.zip')
        fetch(spec['archive']['url'], archive, spec['archive']['sha256'])
        prefix = spec['repository'].split('/')[1] + '-' + spec['revision'] + '/'
        with zipfile.ZipFile(archive) as source:
            license_info = source.getinfo(prefix + 'LICENSE')
            if license_info.file_size > 100_000 or source.read(license_info) != expected_license:
                raise ValueError('Upstream license changed; explicit review required')
            if spec['repository'] == DATA_REPOSITORY:
                scope_info = source.getinfo(prefix + 'LICENSE-SCOPE.md')
                expected_scope = (ROOT / 'LICENSES/wanxiang-data-LICENSE-SCOPE.md').read_bytes()
                if scope_info.file_size > 100_000 or source.read(scope_info) != expected_scope:
                    raise ValueError('Upstream license scope changed; explicit review required')
            for table in spec['tables']:
                name = 'dicts/' + table + '.dict.yaml'
                matches = [entry for entry in source.infolist() if entry.filename == prefix + name]
                if len(matches) != 1:
                    raise ValueError('Missing or duplicate dictionary table: ' + table)
                info = matches[0]
                total_size += info.file_size
                if (not 0 < info.file_size <= 100_000_000 or total_size > 300_000_000
                        or (info.external_attr >> 16) & 0o170000 == 0o120000):
                    raise ValueError('Invalid dictionary size or file type')
                destination = staging / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                with source.open(info) as content:
                    normalize_table(content, destination)
                records[name] = digest(destination)
    return {name: records[name] for name in ('dicts/' + table + '.dict.yaml' for table in TABLES)}


def publish(staging, lock, refresh):
    """Roll back generated data if committing the new reviewed proposal fails."""
    if OUTPUT.is_symlink() or OUTPUT.resolve() == ROOT.resolve() or not OUTPUT.resolve().is_relative_to(ROOT.resolve()):
        raise ValueError('Generated output must stay inside its project root')
    previous = staging.parent / 'previous'
    if OUTPUT.exists():
        OUTPUT.rename(previous)
    try:
        staging.rename(OUTPUT)
        if refresh:
            temporary = staging.parent / 'sources.lock.json'
            temporary.write_text(json.dumps(lock, indent=2) + '\n', encoding='utf-8', newline='\n')
            os.replace(temporary, LOCK)
    except BaseException:
        if OUTPUT.exists():
            shutil.rmtree(OUTPUT)
        if previous.exists():
            previous.rename(OUTPUT)
        raise


def prepare(lock, refresh=False):
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='dictionary-stage-', dir=OUTPUT.parent) as directory:
        staging = Path(directory) / 'output'
        staging.mkdir()
        records = prepare_tables(lock, staging)
        print('Preparing full dictionary and grammar model', flush=True)
        model = CACHE / (lock['model']['sha256'] + '.gram')
        fetch(lock['model']['url'], model, lock['model']['sha256'], expected_size=lock['model']['size'])
        dictionary = staging / 'zeroinput_public.dict.yaml'
        write_dictionary(dictionary, lock)
        records[dictionary.name] = digest(dictionary)
        target_model = staging / 'wanxiang-lts-zh-hans.gram'
        shutil.copyfile(model, target_model)
        records[target_model.name] = lock['model']['sha256']
        if not refresh and records != lock.get('preparedFiles'):
            raise ValueError('Prepared artifacts differ from the reviewed lock')
        candidate = {**lock, 'preparedFiles': records}
        manifest = {'version': lock['version'], 'revision': lock['revision'], 'files': records}
        (staging / 'public-dictionaries.json').write_text(json.dumps(manifest, indent=2) + '\n',
                                                         encoding='utf-8', newline='\n')
        publish(staging, candidate, refresh)
    print('Prepared', len(records), 'verified files', flush=True)


def write_dictionary(dictionary, lock):
    header = {'name': 'zeroinput_public', 'version': lock['revision'], 'sort': 'by_weight',
              'use_preset_vocabulary': False,
              'import_tables': ['dicts/' + name for name in lock['tables']]}
    dictionary.write_text('---\n' + json.dumps(header) + '\n...\n', encoding='utf-8', newline='\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--refresh', action='store_true', help='Resolve upstream and update reviewed lock')
    args = parser.parse_args()
    lock = update_lock() if args.refresh else json.loads(LOCK.read_text(encoding='utf-8'))
    prepare(lock, args.refresh)


if __name__ == '__main__':
    main()
