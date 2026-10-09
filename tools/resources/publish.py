"""Explicitly publish immutable, licensed resource assets. Never merge or publish an APK."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'build/public-resources'
REPO = 'yixunfei/zero-fish-input'


def credentials():
    token = os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
    if token:
        return token
    result = subprocess.run(['git', 'credential', 'fill'], input='protocol=https\nhost=github.com\n\n',
        text=True, capture_output=True, check=True,
        env=dict(os.environ, GIT_TERMINAL_PROMPT='0', GCM_INTERACTIVE='Never'), timeout=20)
    values = dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)
    return values['password']


def request(token, path, method='GET', data=None, file=None):
    url = path if path.startswith('https://') else f'https://api.github.com/repos/{REPO}/{path}'
    assert urllib.parse.urlparse(url).hostname in ('api.github.com', 'uploads.github.com')
    headers = {'User-Agent': 'ZeroInput-resource-publisher', 'Authorization': 'Bearer ' + token,
               'Accept': 'application/vnd.github+json'}
    if file:
        headers.update({'Content-Type': 'application/zip', 'Content-Length': str(file.stat().st_size)})
        with file.open('rb') as source:
            req = urllib.request.Request(url, data=source, method=method, headers=headers)
            with urllib.request.urlopen(req, timeout=600) as response:
                return json.load(response)
    if data is not None:
        data = json.dumps(data).encode()
        headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.load(response)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def sources(pack):
    target = OUT / (pack['id'] + '-' + pack['version'] + '-sources.zip')
    paths = [ROOT / 'tools/resources/catalog.json', ROOT / 'tools/resources/prepare.py',
             ROOT / 'tools/resources/README.md']
    if pack['id'] == 'handwriting':
        paths += [ROOT / 'LICENSES/Apache-2.0.txt', ROOT / 'LICENSES/Tegaki-LGPL-2.1.txt',
                  ROOT / 'LICENSES/Zinnia-BSD-3-Clause.txt', ROOT / 'tools/prepare-handwriting-stroke-model.py',
                  ROOT / 'tools/prepare-handwriting-model.py', ROOT / 'tools/handwriting_quality.py']
        archives = {
            'tegaki-zinnia-simplified-chinese-light-0.3.zip': '598787133a4d59fcf3a2fbc5654c68eaf35a8e422274efcea2035cc081f3446c',
            'tegaki-zinnia-traditional-chinese-0.3.zip': 'f41032e67a4eff056813d243eabc32ea07eea404c714bdb5882a5b0fcda51690',
        }
        for name, checksum in archives.items():
            path = ROOT / 'build/handwriting-stroke-model' / name
            assert digest(path) == checksum
            paths.append(path)
    else:
        paths += [ROOT / 'LICENSES/wanxiang-CC-BY-4.0.txt']
    with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for path in paths:
            info = zipfile.ZipInfo(path.relative_to(ROOT).as_posix(), (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes())
    return target


def publish(token, pack):
    archive = OUT / Path(urllib.parse.urlparse(pack['url']).path).name
    assert archive.stat().st_size == pack['bytes'] and digest(archive) == pack['sha256']
    tag = 'resources-' + pack['version']
    try:
        release = request(token, 'releases/tags/' + tag)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
        release = request(token, 'releases', 'POST', {'tag_name': tag, 'target_commitish': commit,
            'name': pack['id'] + ' resources ' + pack['version'], 'draft': True, 'make_latest': 'false',
            'body': 'Public offline data for ZeroInput. License: ' + pack['license'] +
            '. The companion source archive includes attribution, license and applicable rebuild sources. '
            'Verified data only; no executable libraries or user data. Not an application release.'})
    source_name = pack['id'] + '-' + pack['version'] + '-sources.zip'
    existing_sources = next((a for a in release['assets'] if a['name'] == source_name), None)
    if existing_sources:
        # This immutable companion describes the original publication. A later
        # catalog/script change must not replace sources for unchanged data.
        assert existing_sources.get('state') == 'uploaded' and existing_sources['size'] > 0
        checksum = existing_sources.get('digest', '')
        assert checksum.startswith('sha256:') and len(checksum) == 71
    uploads = [archive] if existing_sources else [archive, sources(pack)]
    for file in uploads:
        existing = next((a for a in release['assets'] if a['name'] == file.name), None)
        if existing:
            assert existing['size'] == file.stat().st_size and existing.get('digest') == 'sha256:' + digest(file)
            continue
        print('Uploading ' + file.name, flush=True)
        url = release['upload_url'].split('{')[0] + '?name=' + urllib.parse.quote(file.name)
        asset = request(token, url, 'POST', file=file)
        assert asset['size'] == file.stat().st_size and asset['digest'] == 'sha256:' + digest(file)
    if release['draft']:
        release = request(token, 'releases/' + str(release['id']), 'PATCH', {'draft': False, 'make_latest': 'false'})
    print(release['html_url'], flush=True)


def main():
    catalog = json.loads((ROOT / 'tools/resources/catalog.json').read_text())
    token = credentials()
    for pack in catalog['packs']:
        publish(token, pack)


if __name__ == '__main__':
    main()
