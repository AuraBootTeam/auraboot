import copy
from pathlib import Path
import tempfile
import unittest
from backend_test_selection import plan, reconcile


class BackendSelectionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        for module, name in [('', 'CoreTest'), ('platform-app', 'AppIT')]:
            file = self.root / module / 'src/test/java/example' / (name + '.java')
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text('package example; class ' + name + ' {}')

    def test_default_retains_full_suite(self):
        self.assertEqual(plan(self.root, [])['mode'], 'full')
        self.assertEqual(plan(self.root, [])['gradleArguments'], [])

    def test_exact_owners_and_task_scoped_filters(self):
        result = plan(self.root, ['--test-class', 'example.CoreTest', '--test-class', 'example.AppIT'])
        self.assertFalse(result['fullNightlyQualification'])
        self.assertEqual(result['gradleArguments'], [':cleanTest', ':test', '--tests', 'example.CoreTest',
            ':platform-app:cleanTest', ':platform-app:test', '--tests', 'example.AppIT'])

    def test_patterns_duplicates_methods_unknown_missing_and_options_reject(self):
        for arguments in [['--tests', 'example.CoreTest'], ['--test-class'],
                ['--test-class', 'example.*'], ['--test-class', 'example.CoreTest.method'],
                ['--test-class', 'example.Absent'], ['--test-class', '../CoreTest'],
                ['--test-class', 'example.CoreTest'] * 2]:
            with self.subTest(arguments=arguments), self.assertRaises(ValueError):
                plan(self.root, arguments)

    def test_ambiguous_owner_and_symlink_reject(self):
        original = self.root / 'src/test/java/example/CoreTest.java'
        duplicate = self.root / 'platform-app/src/test/java/example/CoreTest.java'
        duplicate.write_bytes(original.read_bytes())
        with self.assertRaises(ValueError):
            plan(self.root, ['--test-class', 'example.CoreTest'])
        duplicate.unlink()
        original.rename(original.with_suffix('.txt'))
        original.symlink_to(original.with_suffix('.txt'))
        with self.assertRaises(ValueError):
            plan(self.root, ['--test-class', 'example.CoreTest'])

    def test_no_tests_skipped_failed_wrong_owner_and_wrong_class_cannot_pass(self):
        selection = plan(self.root, ['--test-class', 'example.AppIT'])
        junit = self.root / 'junit'
        for owner, name, content in [
            ('platform-app', 'example.AppIT', '<skipped/>'),
            ('platform-app', 'example.AppIT', '<failure/>'),
            ('platform-app', 'example.AppIT', '<error/>'),
            ('platform', 'example.AppIT', ''),
            ('platform-app', 'example.OtherIT', '')]:
            directory = junit / owner / 'test'
            directory.mkdir(parents=True, exist_ok=True)
            report = directory / 'TEST-fixture.xml'
            report.write_text(f'<testsuite><testcase classname="{name}">{content}</testcase></testsuite>')
            with self.subTest(owner=owner, name=name, content=content):
                self.assertFalse(reconcile(selection, junit)['passed'])
            report.unlink()
        self.assertFalse(reconcile(selection, junit)['passed'])
        report = junit / 'platform-app/test/TEST-fixture.xml'
        report.write_text('<testsuite><testcase classname="example.AppIT"/></testsuite>')
        result = reconcile(selection, junit)
        self.assertTrue(result['passed'])
        self.assertFalse(result['fullNightlyQualification'])


if __name__ == '__main__':
    unittest.main()
