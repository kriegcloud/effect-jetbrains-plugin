"""Exercise release guards locally, using real disposable Git repos and fake gh."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parent
ROOT = SCRIPTS.parent.parent


class VersionGateTest(unittest.TestCase):
    def test_matching_and_mismatched_tags(self):
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        command = re.search(r'run: (python3 .*release-version\.py[^\n]*)', workflow).group(1)
        command = command.replace('.github/scripts/release-version.py',
                                  str(SCRIPTS / 'release-version.py'))
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, 'gradle.properties').write_text('pluginVersion = 1.2.3\n')
            for tag, expected in [('1.2.3', 0), ('1.2.4', 1), ('v1.2.3', 1)]:
                with self.subTest(tag=tag):
                    result = subprocess.run(
                        ['bash', '-e', '-c', command + '\nprintf "sign publish upload"'],
                        cwd=directory, env=dict(os.environ, RELEASE_VERSION=tag),
                        capture_output=True, text=True)
                    self.assertEqual(result.returncode, expected)
                    self.assertEqual(result.stdout, 'sign publish upload' if expected == 0 else '')

    def test_workflow_gate_blocks_all_publication_paths(self):
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        release, post = workflow.split('  postPublication:')
        gate = release.index('run: python3 .github/scripts/release-version.py "$RELEASE_VERSION"')
        self.assertLess(release.index('ref: ${{ github.event.release.tag_name }}'), gate)
        for command in ('signPlugin', 'publishPlugin', 'actions/upload-artifact@'):
            self.assertLess(gate, release.index(command))
        self.assertNotIn('continue-on-error', workflow)
        self.assertNotIn('always()', workflow)
        self.assertIn('needs: [release]', post)
        self.assertIn('release-assets.py signed', post)
        self.assertIn('release-changelog.py "$RELEASE_VERSION"', post)


FAKE_GH = '''#!/usr/bin/env python3
import json, os, pathlib, sys
p = pathlib.Path(os.environ['GH_TEST_STATE'])
s = json.loads(p.read_text())
a = sys.argv[1:]
s['calls'].append(a)
if a[:2] == ['pr', 'list']:
    print(json.dumps(s['prs']))
elif a[:2] == ['pr', 'create']:
    if s.get('fail_create'):
        p.write_text(json.dumps(s))
        sys.exit(1)
    s['prs'].append({'number': 1, 'state': 'OPEN'})
elif a[:2] != ['label', 'create']:
    raise AssertionError(a)
p.write_text(json.dumps(s))
'''


class ChangelogRetryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / 'repo'
        self.repo.mkdir()
        self.env = dict(os.environ, GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL='/dev/null',
                        GIT_TERMINAL_PROMPT='0')
        self.git('init', '--bare', str(self.root / 'origin.git'))
        self.git('init')
        self.git('config', 'user.name', 'Test')
        self.git('config', 'user.email', 'test@example.com')
        (self.repo / 'CHANGELOG.md').write_text('Before\n')
        (self.repo / 'other').write_text('Preserve\n')
        self.git('add', '.')
        self.git('commit', '-m', 'Release')
        self.base = self.git('rev-parse', 'HEAD')
        self.git('tag', '1.2.3')
        self.git('remote', 'add', 'origin', str(self.root / 'origin.git'))
        self.git('push', 'origin', 'HEAD:main', '--tags')
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        (self.bin / 'gh').write_text(FAKE_GH)
        (self.bin / 'gh').chmod(0o755)
        self.state = self.root / 'state.json'
        self.state.write_text(json.dumps({'calls': [], 'prs': []}))
        self.env.update(PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                        GH_TEST_STATE=str(self.state))

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.repo, env=self.env,
                                       text=True, stderr=subprocess.DEVNULL).strip()

    def patch(self):
        (self.repo / 'CHANGELOG.md').write_text('After\n')

    def invoke(self, succeeds=True):
        result = subprocess.run([sys.executable, str(SCRIPTS / 'release-changelog.py'), '1.2.3'],
                                cwd=self.repo, env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode == 0, succeeds, result.stdout + result.stderr)
        return result

    def retry_checkout(self):
        self.git('checkout', '--detach', self.base)
        self.patch()

    def remote_tip(self):
        return self.git('ls-remote', '--heads', 'origin', 'refs/heads/changelog-update-1.2.3')

    def test_no_delta_skips_commit_push_and_pr(self):
        self.invoke()
        self.assertEqual(self.git('rev-parse', 'HEAD'), self.base)
        self.assertEqual(self.remote_tip(), '')
        self.assertEqual(json.loads(self.state.read_text())['calls'], [])

    def test_retry_after_push_then_failed_pr_creation(self):
        state = json.loads(self.state.read_text())
        state['fail_create'] = True
        self.state.write_text(json.dumps(state))
        self.patch()
        self.invoke(succeeds=False)
        tip = self.remote_tip()
        self.assertTrue(tip)
        self.retry_checkout()
        state = json.loads(self.state.read_text())
        state['fail_create'] = False
        self.state.write_text(json.dumps(state))
        self.invoke()
        self.assertEqual(self.remote_tip(), tip)
        self.assertEqual(len(json.loads(self.state.read_text())['prs']), 1)

    def test_existing_open_pr_is_reused_without_duplicate(self):
        self.patch()
        self.invoke()
        tip = self.remote_tip()
        self.retry_checkout()
        self.invoke()
        state = json.loads(self.state.read_text())
        self.assertEqual(sum(c[:2] == ['pr', 'create'] for c in state['calls']), 1)
        self.assertEqual(self.remote_tip(), tip)

    def test_human_changes_are_preserved_with_recovery_error(self):
        self.patch()
        self.invoke()
        (self.repo / 'CHANGELOG.md').write_text('Human edits\n')
        self.git('commit', '-am', 'Human update')
        self.git('push', 'origin', 'HEAD:changelog-update-1.2.3')
        tip = self.remote_tip()
        self.retry_checkout()
        result = self.invoke(succeeds=False)
        self.assertIn('reconcile manually', result.stderr)
        self.assertEqual(self.remote_tip(), tip)

    def test_unrelated_work_is_not_committed(self):
        self.patch()
        (self.repo / 'other').write_text('Local changes\n')
        self.invoke(succeeds=False)
        self.assertEqual(self.remote_tip(), '')


if __name__ == '__main__':
    unittest.main()
