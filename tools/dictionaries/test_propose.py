"""Exercise real Git branch replacement only against disposable local remotes."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name('propose-update.sh').resolve()
BRANCH = 'zero/weekly-public-dictionaries'
SUBJECT = 'Update pinned Wanxiang dictionaries and grammar model'


class ProposalTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='dictionary-proposal-')
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        self.remote = root / 'remote.git'
        self.repo = root / 'checkout'
        self.repo.mkdir()
        self.run_git('init', '--bare', str(self.remote))
        self.run_git('init', '-b', 'main')
        self.run_git('config', 'user.name', 'Public fixture maintainer')
        self.run_git('config', 'user.email', 'fixture@example.invalid')
        self.run_git('remote', 'add', 'origin', str(self.remote))
        self.lock = self.repo / 'tools/dictionaries/sources.lock.json'
        self.lock.parent.mkdir(parents=True)
        self.lock.write_text('public baseline')
        for name in ('tools/resources/catalog.json', 'LICENSES/wanxiang-CC-BY-4.0.txt',
                     'engine-rime/prebuilt/rime/prebuilt/manifest.json'):
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('public baseline fixture')
        self.run_git('add', '.')
        self.run_git('commit', '-m', 'public baseline')
        self.run_git('push', 'origin', 'main')
        self.calls = root / 'gh-calls'
        commands = root / 'commands'
        commands.mkdir()
        gh = commands / 'gh'
        gh.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$GH_CALLS"\nexit 0\n', newline='\n')
        gh.chmod(0o755)
        self.environment = {**os.environ, 'PATH': str(commands) + os.pathsep + os.environ['PATH'],
                            'GH_CALLS': self.calls.as_posix()}
        git = Path(shutil.which('git')).resolve()
        self.bash = git.parents[1] / 'bin/bash.exe' if os.name == 'nt' else Path(shutil.which('bash'))

    def run_git(self, *arguments):
        result = subprocess.run(['git', *arguments], cwd=self.repo, capture_output=True, text=True)
        if result.returncode:
            self.fail(result.stderr)
        return result.stdout.strip()

    def seed(self, author='github-actions[bot]', subject=SUBJECT):
        self.run_git('switch', '-c', 'seed')
        self.lock.write_text('public bot proposal')
        self.run_git('add', '.')
        self.run_git('commit', '--author', author + ' <fixture@example.invalid>', '-m', subject)
        self.run_git('push', 'origin', 'seed:refs/heads/' + BRANCH)
        tip = self.run_git('rev-parse', 'HEAD')
        self.run_git('switch', 'main')
        self.lock.write_text('public refreshed proposal')
        return tip

    def propose(self):
        return subprocess.run([str(self.bash), SCRIPT.as_posix()], cwd=self.repo,
                              env=self.environment, capture_output=True, text=True)

    def remote_tip(self):
        return self.run_git('--git-dir', str(self.remote), 'rev-parse', BRANCH)

    def test_own_bot_branch_can_be_refreshed_and_includes_prebuilt_changes(self):
        old_tip = self.seed()
        artifact = self.repo / 'engine-rime/prebuilt/rime/prebuilt/manifest.json'
        artifact.parent.mkdir(parents=True, exist_ok=True)
        artifact.write_text('public prepared artifact fixture')
        result = self.propose()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotEqual(old_tip, self.remote_tip())
        self.assertEqual(SUBJECT, self.run_git('show', '-s', '--format=%s', 'HEAD'))
        self.assertIn('engine-rime/prebuilt/rime/prebuilt/manifest.json',
                      self.run_git('show', '--format=', '--name-only', 'HEAD'))
        self.assertIn('pr edit', self.calls.read_text())

    def test_human_authored_branch_is_never_replaced(self):
        old_tip = self.seed(author='Public fixture maintainer')
        result = self.propose()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Refusing to overwrite', result.stdout)
        self.assertEqual(old_tip, self.remote_tip())
        self.assertFalse(self.calls.exists())

    def test_unrecognized_bot_commit_is_never_replaced(self):
        old_tip = self.seed(subject='Other public bot work')
        result = self.propose()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(old_tip, self.remote_tip())
        self.assertFalse(self.calls.exists())

    def test_unchanged_inputs_do_not_create_a_branch_or_touch_the_pr(self):
        result = self.propose()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('main', self.run_git('branch', '--show-current'))
        self.assertFalse(self.calls.exists())


if __name__ == '__main__':
    unittest.main()
