import copy
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

from open_platform_backend_catalog import cases, plan, reconcile


class BackendCatalogTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(__file__).resolve().parents[2]
        self.expected = plan(self.root)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.results = Path(self.temporary.name)
        for index, row in enumerate(self.expected['classes']):
            suite = ET.Element('testsuite', tests=str(len(row['cases'])),
                               failures='0', errors='0', skipped='0')
            for name in row['cases']:
                ET.SubElement(suite, 'testcase', classname=row['class'], name=name)
            ET.ElementTree(suite).write(self.results / f'TEST-{index}.xml')

    def test_profile_reuses_every_open_platform_class_and_existing_query_regressions(self):
        names = {row['class'] for row in self.expected['classes']}
        directory = self.root / 'platform/src/test/java/com/auraboot/framework/openplatform'
        source_names = {'com.auraboot.framework.openplatform.' +
                        '.'.join(p.relative_to(directory).with_suffix('').parts)
                        for p in directory.rglob('*Test.java')}
        self.assertEqual(source_names, {name for name in names if '.openplatform.' in name})
        self.assertIn('com.auraboot.framework.openplatform.service.OpenPlatformLifecycleIntegrationTest', names)
        self.assertIn('com.auraboot.framework.meta.service.AggregateQueryServiceIntegrationTest', names)
        self.assertIn('com.auraboot.framework.integration.agent.NamedQueryShadowInvokerIntegrationTest', names)

    def test_exact_case_results_are_accepted(self):
        receipt = reconcile(self.expected, self.results)
        self.assertEqual(self.expected['expectedCases'], receipt['passed'])
        self.assertEqual('PASS', receipt['verdict'])

    def test_omitted_class_is_rejected(self):
        next(self.results.glob('*.xml')).unlink()
        with self.assertRaises(ValueError):
            reconcile(self.expected, self.results)

    def test_duplicate_or_renamed_case_is_rejected_even_at_same_count(self):
        file = next(self.results.glob('*.xml'))
        tree = ET.parse(file)
        original = tree.getroot().find('testcase').get('name')
        for name in [original + '-renamed', 'unknown()']:
            tree.getroot().find('testcase').set('name', name)
            tree.write(file)
            with self.assertRaises(ValueError):
                reconcile(self.expected, self.results)
        tree.getroot().find('testcase').set('name', original)
        duplicate = copy.deepcopy(tree.getroot().find('testcase'))
        tree.getroot().append(duplicate)
        tree.getroot().set('tests', str(len(tree.getroot().findall('testcase'))))
        tree.write(file)
        with self.assertRaises(ValueError):
            reconcile(self.expected, self.results)

    def test_failures_errors_and_skips_cannot_be_hidden_by_summary(self):
        file = next(self.results.glob('*.xml'))
        for tag in ['failure', 'error', 'skipped']:
            tree = ET.parse(file)
            case = tree.getroot().find('testcase')
            ET.SubElement(case, tag)
            tree.write(file)
            with self.assertRaises(ValueError):
                reconcile(self.expected, self.results)
            case.remove(case.find(tag))
            tree.write(file)

    def test_missing_results_and_wrong_suite_count_are_rejected(self):
        file = next(self.results.glob('*.xml'))
        tree = ET.parse(file)
        tree.getroot().set('tests', '0')
        tree.write(file)
        with self.assertRaises(ValueError):
            reconcile(self.expected, self.results)
        for file in self.results.glob('*.xml'):
            file.unlink()
        with self.assertRaises(ValueError):
            reconcile(self.expected, self.results)

    def test_string_value_source_expands_exact_instances(self):
        source = '''@ParameterizedTest @ValueSource(strings = {"en-US", "tr-TR"})
                    void locales(String localeTag) {} @Test void simple() {}'''
        self.assertEqual(['[1] localeTag=en-US', '[2] localeTag=tr-TR', 'simple()'], cases(source))

    def test_unsupported_or_disabled_declarations_fail_closed(self):
        for source in ['@Disabled @Test void test() {}', '@TestFactory void tests() {}',
                       '@ParameterizedTest @MethodSource("cases") void test(String value) {}']:
            with self.assertRaises(ValueError):
                cases(source)
        self.assertEqual(['actual()'], cases('// @Test void fake() {}\n@Test void actual() {}'))

    def test_fully_qualified_parameterized_annotation_and_tempdir_are_expanded(self):
        source = '''@org.junit.jupiter.params.ParameterizedTest(name = "entry via {0}")
                    @org.junit.jupiter.params.provider.ValueSource(strings = {"json", "zip"})
                    void entries(String entry, @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {}
                    @org.junit.jupiter.api.Test void ordinary() {}'''
        self.assertEqual(['entry via json', 'entry via zip', 'ordinary()'], cases(source))
        with self.assertRaises(ValueError):
            cases('@org.junit.jupiter.api.Disabled @org.junit.jupiter.api.Test void disabled() {}')

    def test_display_names_before_and_after_test_keep_exact_junit_identity(self):
        source = '''@DisplayName("before (named)") @Order(1) @Test void one() {}
                    @Test @DisplayName("after") void two() {} @Test void three() {}'''
        self.assertEqual(['before (named)', 'after', 'three()'], cases(source))


if __name__ == '__main__':
    unittest.main()
