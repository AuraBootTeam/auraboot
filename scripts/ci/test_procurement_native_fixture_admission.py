"""Hermetic file/selector guard counterexamples, never Native or provider proof."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / 'dev/procurement_native_fixture_admission.py'
spec = importlib.util.spec_from_file_location('fixture_admission', MODULE)
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class NativeFixtureAdmissionTest(unittest.TestCase):
    def test_future_placeholders_and_short_commits_are_not_actual_pins(self):
        guard.validate_pins('a' * 40, 'b' * 40)
        for invalid in (None, 'FUTURE_ROOT_HEAD', 'a' * 39):
            with self.assertRaisesRegex(ValueError, 'EXPLICIT_ACTUAL'):
                guard.validate_pins(invalid, 'b' * 40)

    def test_source_roster_must_include_initializer_and_match_bytes(self):
        self.assertEqual(set(guard.PRODUCER_SOURCES), {
            'platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementOrderUiFixtureProducerIT.java',
            'platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementPublicSourceNativeFixture.java',
            'platform/src/test/java/com/auraboot/framework/notification/sourcebinding/ProductQuoteNativeInitialization.java'})
        self.assertEqual(len(guard.S06_FAMILIES), 5)
        self.assertIn('com/auraboot/framework/notification/sourcebinding/ProductQuoteNativeInitialization', guard.S06_FAMILIES)
        expected = {path: 'a' * 64 for path in guard.PRODUCER_SOURCES}
        guard.validate_source_hashes(expected, dict(expected))
        missing = dict(expected)
        missing.pop(guard.PRODUCER_SOURCES[-1])
        with self.assertRaisesRegex(ValueError, 'SOURCE_ROSTER'):
            guard.validate_source_hashes(missing, missing)
        changed = dict(expected)
        changed[guard.PRODUCER_SOURCES[-1]] = 'b' * 64
        with self.assertRaisesRegex(ValueError, 'BYTES_DRIFT'):
            guard.validate_source_hashes(expected, changed)

    def test_class_inventory_seals_main_and_every_nested_without_borrowing_tests(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            family = guard.PRODUCER_FAMILIES[0]
            main = root / (family + '.class')
            main.parent.mkdir(parents=True)
            main.write_bytes(b'counterexample fixture, not JVM bytecode')
            nested = root / (family + '$Actual.class')
            nested.write_bytes(b'nested counterexample fixture')
            (root / (family + 'Test.class')).write_bytes(b'unrelated pure test fixture')
            original = guard.compiled_inventory(root, (family,))
            self.assertEqual(set(original), {family + '.class', family + '$Actual.class'})
            guard.validate_inventory(original, dict(original))
            nested.write_bytes(b'mutated')
            with self.assertRaisesRegex(ValueError, 'ROSTER_DRIFT'):
                guard.validate_inventory(original, guard.compiled_inventory(root, (family,)))
            nested.unlink()
            with self.assertRaisesRegex(ValueError, 'ROSTER_DRIFT'):
                guard.validate_inventory(original, guard.compiled_inventory(root, (family,)))
            main.unlink()
            with self.assertRaisesRegex(ValueError, 'CLASSES_REQUIRED'):
                guard.compiled_inventory(root, (family,))

    def test_receipt_cannot_be_old_or_replaced_with_reused_birth(self):
        fresh = SimpleNamespace(st_mtime_ns=20, st_ctime_ns=20, st_birthtime_ns=20)
        guard.validate_fresh_stat(fresh, 10)
        for stat in (SimpleNamespace(st_mtime_ns=9, st_ctime_ns=20, st_birthtime_ns=20),
                     SimpleNamespace(st_mtime_ns=20, st_ctime_ns=9, st_birthtime_ns=20),
                     SimpleNamespace(st_mtime_ns=20, st_ctime_ns=20, st_birthtime_ns=9),
                     SimpleNamespace(st_mtime_ns=20, st_ctime_ns=20)):
            with self.assertRaises(ValueError):
                guard.validate_fresh_stat(stat, 10)

    def test_only_one_actual_producer_method_can_qualify(self):
        self.assertEqual(guard.PRODUCER_CLASS, 'com.auraboot.framework.plugin.pf4j.ProcurementOrderUiFixtureProducerIT')
        self.assertEqual(guard.PRODUCER_METHOD, 'producePendingFailedUnknownAndMixedSupplierExecutionPrerequisites')
        counts = {'tests': 1, 'failures': 0, 'errors': 0, 'skipped': 0}
        cases = [{'className': guard.PRODUCER_CLASS, 'method': guard.PRODUCER_METHOD, 'state': 'passed'}]
        guard.qualify_selector(0, counts, cases, True, True)
        for args in ((1, counts, cases, True, True),
                     (0, {**counts, 'skipped': 1}, cases, True, True),
                     (0, {**counts, 'tests': True}, cases, True, True),
                     (0, counts, cases * 2, True, True),
                     (0, counts, [{**cases[0], 'method': 'oldP14'}], True, True),
                     (0, counts, cases, False, True), (0, counts, cases, True, False)):
            with self.assertRaises(ValueError):
                guard.qualify_selector(*args)


if __name__ == '__main__':
    unittest.main()
