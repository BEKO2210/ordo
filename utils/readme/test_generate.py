"""Tests for generate.py. Run: python3 -m unittest discover -s utils/readme"""
import pathlib
import shutil
import tempfile
import unittest

import generate

REPO = pathlib.Path(__file__).resolve().parents[2]
BLOCKS = ['badges', 'new', 'status', 'screenshots', 'features', 'tests']


def readme(extra=''):
    body = '\n\n'.join(f'hand {n}\n<!-- AUTO:{n} -->\nold\n<!-- /AUTO:{n} -->' for n in BLOCKS)
    return f'# Title\n\n{body}\n{extra}\nhand end\n'


class RepoFixture(unittest.TestCase):
    """A minimal repo with every source the generator reads."""

    def setUp(self):
        self.root = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        self.put('app/build.gradle', "        minSdk 24\n        targetSdk 36\n        versionName '1.2.3'\n")
        self.put('app/src/main/A.kt', '// SPDX-License-Identifier: GPL-3.0-or-later\n')
        self.put('app/src/test/ATest.kt', '')
        self.put('e2e/run.sh', 'tests="smoke"\n[ "$write" = 1 ] && tests="$tests cast"\n')
        self.put('e2e/smoke.sh', '')
        self.put('e2e/cast.sh', '')
        for loc in ('en-US', 'de-DE'):
            base = f'fastlane/metadata/android/{loc}'
            self.put(f'{base}/changelogs/default.txt', f'New:\n• change {loc}\nFeedback: x\n')
            self.put(f'{base}/full_description.txt',
                     f'Intro {loc}.\n\nFind\n• one\n• two\n\nAsk\nSay it.\n• three\n\nPrivacy\nNo data.\n')
            self.put(f'{base}/images/phoneScreenshots/01_home.jpg', '')
        for name in ('README.md', 'README.de.md'):
            self.put(name, readme())

    def put(self, rel, text):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    def text(self, rel):
        return (self.root / rel).read_text(encoding='utf-8')


class GenerateTest(RepoFixture):
    def test_replaces_only_blocks(self):
        out = generate.generate(self.root)[self.root / 'README.md']
        self.assertTrue(out.startswith('# Title\n\nhand badges\n<!-- AUTO:badges -->\n'))
        self.assertTrue(out.endswith('<!-- /AUTO:tests -->\n\nhand end\n'))
        self.assertNotIn('\nold\n', out)
        self.assertIn('Version **1.2.3**', out)
        self.assertIn('Android 7.0 and later (minSdk 24, targetSdk 36 = Android 16)', out)
        self.assertIn('> **New in 1.2.3**\n> - change en-US\n<!--', out)
        self.assertNotIn('Feedback', out)
        self.assertIn('**Find**\n\n- one\n- two', out)
        self.assertIn('**Ask**\n\nSay it.\n\n- three', out)
        self.assertNotIn('Privacy', out)
        self.assertIn('fastlane/metadata/android/en-US/images/phoneScreenshots/01_home.jpg', out)
        self.assertIn('GPL--3.0--or--later', out)
        self.assertIn('2 scripts', out)

    def test_german_readme_uses_german_sources(self):
        out = generate.generate(self.root)[self.root / 'README.de.md']
        self.assertIn('> **Neu in 1.2.3**\n> - change de-DE', out)
        self.assertIn('android/de-DE/images/phoneScreenshots', out)
        self.assertNotIn('en-US', out)

    def test_idempotent(self):
        for path, text in generate.generate(self.root).items():
            path.write_text(text, encoding='utf-8')
        again = generate.generate(self.root)
        for path, text in again.items():
            self.assertEqual(path.read_text(encoding='utf-8'), text)

    def test_missing_source_writes_nothing(self):
        (self.root / 'fastlane/metadata/android/de-DE/changelogs/default.txt').unlink()
        before = self.text('README.md')
        with self.assertRaisesRegex(generate.SourceError, 'de-DE/changelogs/default.txt'):
            generate.generate(self.root)
        self.assertEqual(self.text('README.md'), before)

    def test_main_exits_nonzero_and_keeps_files(self):
        self.put('app/build.gradle', 'nothing here\n')
        before = {n: self.text(n) for n in ('README.md', 'README.de.md')}
        import sys
        argv, sys.argv = sys.argv, ['generate.py', str(self.root)]
        try:
            with self.assertRaises(SystemExit) as e:
                generate.main()
        finally:
            sys.argv = argv
        self.assertIn('versionName', str(e.exception.code))
        self.assertEqual({n: self.text(n) for n in before}, before)

    def test_missing_or_unknown_marker_fails(self):
        self.put('README.md', readme().replace('<!-- AUTO:tests -->', '<!-- AUTO:testz -->'))
        with self.assertRaisesRegex(generate.SourceError, 'README.md: blocks'):
            generate.generate(self.root)

    def test_unknown_api_level_fails(self):
        self.put('app/build.gradle', "minSdk 99\ntargetSdk 36\nversionName '1'\n")
        with self.assertRaisesRegex(generate.SourceError, 'API level 99'):
            generate.generate(self.root)

    def test_e2e_script_listed_but_missing_fails(self):
        (self.root / 'e2e/cast.sh').unlink()
        with self.assertRaisesRegex(generate.SourceError, 'e2e/cast.sh'):
            generate.generate(self.root)

    def test_no_screenshots_fails(self):
        (self.root / 'fastlane/metadata/android/en-US/images/phoneScreenshots/01_home.jpg').unlink()
        with self.assertRaisesRegex(generate.SourceError, 'no screenshots'):
            generate.generate(self.root)


class RealRepoTest(unittest.TestCase):
    def test_committed_readmes_are_current(self):
        for path, text in generate.generate(REPO).items():
            self.assertEqual(path.read_text(encoding='utf-8'), text, f'{path.name}: run utils/readme/generate.py')


if __name__ == '__main__':
    unittest.main()
