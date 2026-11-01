"""Public-fixture regressions for dictionary update publication and provenance."""
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import prepare


class PreparationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for name, value in {
            'ROOT': self.root, 'LOCK': self.root / 'sources.lock.json',
            'CACHE': self.root / 'cache', 'OUTPUT': self.root / 'output',
        }.items():
            replacement = patch.object(prepare, name, value)
            replacement.start()
            self.addCleanup(replacement.stop)
        prepare.CACHE.mkdir()
        prepare.OUTPUT.mkdir()
        (prepare.OUTPUT / 'dicts').mkdir()
        (prepare.OUTPUT / 'dicts/zi.dict.yaml').write_text('previous verified table')
        (self.root / 'LICENSES').mkdir()
        (self.root / 'LICENSES/wanxiang-CC-BY-4.0.txt').write_bytes(b'public license fixture')
        (self.root / 'LICENSES/wanxiang-data-LICENSE-SCOPE.md').write_bytes(b'public data scope')
        self.lock = {
            'format': 1, 'version': 'public-fixture', 'revision': 'a' * 40,
            'license': 'CC-BY-4.0', 'tables': list(prepare.TABLES),
            'archive': {'url': 'https://example.invalid/source.zip', 'sha256': '0' * 64},
            'model': {'url': 'https://example.invalid/model', 'sha256': self.hash(b'model'),
                      'size': 5, 'assetId': 123},
            'preparedFiles': {'unreviewed': '0' * 64},
        }
        prepare.LOCK.write_text(json.dumps(self.lock))
        (prepare.CACHE / (self.lock['model']['sha256'] + '.gram')).write_bytes(b'model')

    @staticmethod
    def hash(value):
        return hashlib.sha256(value).hexdigest()

    def archive(self, missing=(), license_data=b'public license fixture',
                repository='rime-wanxiang', scope_data=None):
        path = prepare.CACHE / (self.lock['revision'] + '.zip')
        with zipfile.ZipFile(path, 'w') as archive:
            prefix = repository + '-' + self.lock['revision'] + '/'
            for table in prepare.TABLES:
                if table not in missing:
                    archive.writestr(prefix + 'dicts/' + table + '.dict.yaml',
                                     '---\nname: fixture\n...\n公开\tgōng kāi\t10\n')
            archive.writestr(prefix + 'LICENSE', license_data)
            if scope_data is not None:
                archive.writestr(prefix + 'LICENSE-SCOPE.md', scope_data)
        self.lock['archive']['sha256'] = prepare.digest(path)
        return path

    def assert_preserved(self, before):
        self.assertEqual(before, prepare.LOCK.read_bytes())
        self.assertEqual('previous verified table',
                         (prepare.OUTPUT / 'dicts/zi.dict.yaml').read_text())
        self.assertFalse((prepare.OUTPUT / 'public-dictionaries.json').exists())

    def test_missing_table_does_not_replace_existing_output_or_lock(self):
        self.archive(missing={'lianxiang'})
        before = prepare.LOCK.read_bytes()
        with patch.object(prepare, 'fetch'), self.assertRaises((KeyError, ValueError)):
            prepare.prepare(self.lock, refresh=True)
        self.assert_preserved(before)

    def test_unreviewed_artifacts_do_not_replace_existing_output_or_lock(self):
        self.archive()
        before = prepare.LOCK.read_bytes()
        with patch.object(prepare, 'fetch'), self.assertRaises(ValueError):
            prepare.prepare(self.lock)
        self.assert_preserved(before)

    def test_resolving_an_update_does_not_persist_an_unprepared_lock(self):
        before = prepare.LOCK.read_bytes()
        model = {'name': 'wanxiang-lts-zh-hans.gram', 'digest': 'sha256:' + 'c' * 64,
                 'size': 5, 'id': 456, 'browser_download_url': 'https://example.invalid/model',
                 'url': 'https://api.github.com/repos/amzxyz/rime-wanxiang-data/releases/assets/456'}

        def api(path):
            if '/commits/' in path:
                return {'sha': 'b' * 40}
            if path.endswith('/releases/latest'):
                return {'tag_name': 'fixture'}
            return {'assets': [model]}

        def fetch(url, destination, *args, **kwargs):
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(b'public archive fixture')

        with patch.object(prepare, 'api', side_effect=api), patch.object(prepare, 'fetch', side_effect=fetch):
            candidate = prepare.update_lock()
        self.assertEqual('b' * 40, candidate['revision'])
        self.assertEqual(model['url'], candidate['model']['url'])
        self.assertEqual({'lianxiang', 'renming'}, set(candidate['retainedSources'][0]['tables']))
        self.assertEqual(self.lock['revision'], candidate['retainedSources'][0]['revision'])
        self.assertEqual(before, prepare.LOCK.read_bytes())

    def test_fourteen_new_tables_and_two_retained_tables_are_reproducible(self):
        self.archive()
        candidate = copy.deepcopy(self.lock)
        candidate.update({'repository': prepare.DATA_REPOSITORY, 'revision': 'b' * 40,
                          'version': 'data-fixture', 'retainedSources': [{
                              'repository': 'amzxyz/rime-wanxiang',
                              'revision': self.lock['revision'], 'archive': self.lock['archive'],
                              'tables': ['lianxiang', 'renming'],
                          }]})
        archive_path = prepare.CACHE / (candidate['revision'] + '.zip')
        with zipfile.ZipFile(archive_path, 'w') as archive:
            prefix = 'rime-wanxiang-data-' + candidate['revision'] + '/'
            for table in prepare.TABLES:
                if table not in prepare.RETAINED_TABLES:
                    archive.writestr(prefix + 'dicts/' + table + '.dict.yaml',
                                     '---\nname: fixture\n...\n新增\txīn zēng\t10\n')
            archive.writestr(prefix + 'LICENSE', b'public license fixture')
            archive.writestr(prefix + 'LICENSE-SCOPE.md', b'public data scope')
        candidate['archive'] = {'url': 'https://example.invalid/new.zip',
                                'sha256': prepare.digest(archive_path)}
        with patch.object(prepare, 'fetch'):
            prepare.prepare(candidate, refresh=True)
        reviewed = json.loads(prepare.LOCK.read_text())
        self.assertEqual(18, len(reviewed['preparedFiles']))
        self.assertEqual(candidate['retainedSources'], reviewed['retainedSources'])
        for table in prepare.TABLES:
            text = (prepare.OUTPUT / ('dicts/' + table + '.dict.yaml')).read_text(encoding='utf-8')
            self.assertIn('公开' if table in prepare.RETAINED_TABLES else '新增', text)
        before = prepare.LOCK.read_bytes()
        with patch.object(prepare, 'fetch'):
            prepare.prepare(reviewed)
        self.assertEqual(before, prepare.LOCK.read_bytes())

    def test_failed_lock_commit_restores_the_previous_generated_directory(self):
        self.archive()
        before = prepare.LOCK.read_bytes()
        with patch.object(prepare, 'fetch'), patch.object(prepare.os, 'replace', side_effect=PermissionError), \
                self.assertRaises(PermissionError):
            prepare.prepare(self.lock, refresh=True)
        self.assert_preserved(before)

    def test_duplicate_unknown_or_missing_table_ownership_is_rejected(self):
        for names in (['zi', 'zi'], ['unknown'], list(prepare.TABLES)):
            with self.subTest(names=names):
                lock = copy.deepcopy(self.lock)
                lock['retainedSources'] = [{'repository': 'amzxyz/rime-wanxiang',
                                           'revision': lock['revision'], 'archive': lock['archive'],
                                           'tables': names}]
                with self.assertRaises(ValueError):
                    prepare.source_archives(lock)

    def test_missing_required_output_table_and_path_like_revision_are_rejected(self):
        for change in ({'tables': prepare.TABLES[:-1]}, {'revision': '../outside'}):
            lock = {**self.lock, **change}
            with self.assertRaises(ValueError):
                prepare.source_archives(lock)

    def test_invalid_resolved_revision_is_rejected_before_any_download(self):
        before = prepare.LOCK.read_bytes()
        with patch.object(prepare, 'api', return_value={'sha': '../outside'}), \
                patch.object(prepare, 'fetch') as fetch, self.assertRaises(ValueError):
            prepare.update_lock()
        fetch.assert_not_called()
        self.assertEqual(before, prepare.LOCK.read_bytes())

    def test_changed_license_does_not_replace_the_reviewed_license_or_output(self):
        self.archive(license_data=b'different license')
        before = prepare.LOCK.read_bytes()
        with patch.object(prepare, 'fetch'), self.assertRaises(ValueError):
            prepare.prepare(self.lock, refresh=True)
        self.assert_preserved(before)
        self.assertEqual(b'public license fixture',
                         (self.root / 'LICENSES/wanxiang-CC-BY-4.0.txt').read_bytes())

    def test_empty_table_is_rejected(self):
        with self.assertRaises(ValueError):
            prepare.normalize_table(io.BytesIO(b'---\nname: fixture\n...\n'), self.root / 'empty.yaml')

    def test_changed_data_license_scope_requires_review_without_replacing_output(self):
        self.lock['repository'] = prepare.DATA_REPOSITORY
        self.archive(repository='rime-wanxiang-data', scope_data=b'changed data scope')
        before = prepare.LOCK.read_bytes()
        with patch.object(prepare, 'fetch'), self.assertRaisesRegex(ValueError, 'license scope'):
            prepare.prepare(self.lock, refresh=True)
        self.assert_preserved(before)

    def test_model_size_mismatch_never_replaces_an_existing_cache_file(self):
        target = self.root / 'model.gram'
        target.write_bytes(b'previous verified model')
        with patch.object(prepare.urllib.request, 'urlopen', return_value=io.BytesIO(b'short')), \
                self.assertRaises(ValueError):
            prepare.fetch('https://api.github.com/repos/public/fixture/releases/assets/1',
                          target, self.hash(b'short'), expected_size=10)
        self.assertEqual(b'previous verified model', target.read_bytes())
        self.assertFalse(target.with_suffix('.partial').exists())

    def test_model_hash_mismatch_never_replaces_an_existing_cache_file(self):
        target = self.root / 'model.gram'
        target.write_bytes(b'previous verified model')
        with patch.object(prepare.urllib.request, 'urlopen', return_value=io.BytesIO(b'wrong')), \
                self.assertRaises(ValueError):
            prepare.fetch('https://example.invalid/model', target, '0' * 64, expected_size=5)
        self.assertEqual(b'previous verified model', target.read_bytes())

    def test_fixed_asset_download_requests_binary_content(self):
        target = self.root / 'model.gram'
        with patch.object(prepare.urllib.request, 'urlopen', return_value=io.BytesIO(b'model')) as request:
            prepare.fetch('https://api.github.com/repos/public/fixture/releases/assets/1',
                          target, self.hash(b'model'), expected_size=5)
        self.assertEqual('application/octet-stream', request.call_args.args[0].get_header('Accept'))
        self.assertEqual(b'model', target.read_bytes())


if __name__ == '__main__':
    unittest.main()
