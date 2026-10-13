import copy
import json
from unittest.mock import patch
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

from open_platform_backend_catalog import cases, plan, reconcile, coverage_result


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

        self.coverage = self.results / 'jacoco.xml'
        report = ET.Element('report', name='hermetic-coverage-fixture')
        for row in self.expected['coverage']['sources']:
            package_name, filename = row['class'].rsplit('.', 1)
            package = ET.SubElement(report, 'package', name=package_name.replace('.', '/'))
            source = ET.SubElement(package, 'sourcefile', name=filename + '.java')
            for line in range(1, 101):
                ET.SubElement(source, 'line', nr=str(line), ci='1' if line <= 80 else '0',
                              mi='0' if line <= 80 else '1')
            ET.SubElement(source, 'counter', type='LINE', covered='80', missed='20')
        ET.ElementTree(report).write(self.coverage)

    def test_profile_reuses_every_open_platform_class_and_existing_query_regressions(self):
        names = {row['class'] for row in self.expected['classes']}
        directory = self.root / 'platform/src/test/java/com/auraboot/framework/openplatform'
        source_names = {'com.auraboot.framework.openplatform.' +
                        '.'.join(p.relative_to(directory).with_suffix('').parts)
                        for p in directory.rglob('*Test.java')}
        self.assertEqual(source_names, {name for name in names if '.openplatform.' in name})
        self.assertIn('com.auraboot.framework.openplatform.service.OpenPlatformLifecycleIntegrationTest', names)
        self.assertIn('com.auraboot.framework.meta.service.AggregateQueryServiceIntegrationTest', names)
        self.assertIn('com.auraboot.framework.meta.service.impl.AggregateQueryFilterTreeTest', names)
        self.assertIn('com.auraboot.framework.meta.service.impl.AggregateQueryServiceImplDataScopeTest', names)
        self.assertIn('com.auraboot.framework.integration.agent.NamedQueryShadowInvokerIntegrationTest', names)
        for name in ['com.auraboot.framework.plugin.service.PluginDirectoryLoaderTest',
                     'com.auraboot.framework.plugin.service.impl.PluginZipResourceReaderTest',
                     'com.auraboot.framework.plugin.service.PluginImportTwoPhaseReferenceIntegrationTest']:
            self.assertIn(name, names)
            profile_file = self.root / 'scripts/ci/open-platform-backend.profile.json'
            broken = json.loads(profile_file.read_text()); broken['classes'].remove(name)
            original_read = Path.read_text
            def read(path, *args, **kwargs):
                return json.dumps(broken) if path == profile_file else original_read(path, *args, **kwargs)
            with patch.object(Path, 'read_text', read), self.assertRaisesRegex(ValueError, 'Public plugin declaration'):
                plan(self.root)
        self.assertIn('com.auraboot.framework.meta.service.impl.OutboxWorkerImplCoverageIT', names)
        coverage_names = {row['class'] for row in self.expected['coverage']['sources']}
        all_open_sources = {'com.auraboot.framework.openplatform.' + folder + '.' + file.stem
                            for folder in ['service', 'security']
                            for file in (self.root / 'platform/src/main/java/com/auraboot/framework/openplatform' / folder).rglob('*.java')}
        exemptions = {row['class'] for row in self.expected['coverage']['nonExecutable']}
        self.assertEqual({'com.auraboot.framework.openplatform.service.OpenApiCapabilityContributor'}, exemptions)
        self.assertEqual(all_open_sources - exemptions, {name for name in coverage_names if '.openplatform.' in name})
        self.assertIn('com.auraboot.framework.meta.service.impl.NamedQueryServiceImpl', coverage_names)
        self.assertIn('com.auraboot.framework.meta.service.impl.AggregateQueryServiceImpl', coverage_names)

    def test_exact_case_results_are_accepted(self):
        receipt = reconcile(self.expected, self.results, self.coverage)
        self.assertEqual(self.expected['expectedCases'], receipt['passed'])
        self.assertEqual('PASS', receipt['verdict'])
        self.assertEqual(80, receipt['coverage']['minimumLinePercent'])
        self.assertEqual(len(self.expected['coverage']['sources']), len(receipt['coverage']['sources']))
        original = self.coverage.read_bytes()
        for mutation in ['below', 'missing', 'duplicate', 'empty', 'fake-counter', 'negative', 'missing-report']:
            self.coverage.write_bytes(original)
            tree = ET.parse(self.coverage)
            source = tree.getroot().find('package/sourcefile')
            counter = source.find('counter')
            if mutation == 'below':
                source.findall('line')[79].set('ci', '0')
                source.findall('line')[79].set('mi', '1')
                counter.set('covered', '79'); counter.set('missed', '21')
            elif mutation == 'missing': tree.getroot().find('package').remove(source)
            elif mutation == 'duplicate': tree.getroot().find('package').append(copy.deepcopy(source))
            elif mutation == 'empty': counter.set('covered', '0'); counter.set('missed', '0')
            elif mutation == 'fake-counter': counter.set('covered', '81'); counter.set('missed', '19')
            elif mutation == 'negative': counter.set('missed', '-1')
            tree.write(self.coverage)
            if mutation == 'missing-report': self.coverage.unlink()
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                reconcile(self.expected, self.results, self.coverage)
        self.coverage.write_bytes(original)
        with self.assertRaises(ValueError):
            reconcile(self.expected, self.results)

    def test_omitted_class_is_rejected(self):
        next(self.results.glob('*.xml')).unlink()
        with self.assertRaises(ValueError):
            reconcile(self.expected, self.results, self.coverage)

    def test_duplicate_or_renamed_case_is_rejected_even_at_same_count(self):
        file = next(self.results.glob('*.xml'))
        tree = ET.parse(file)
        original = tree.getroot().find('testcase').get('name')
        for name in [original + '-renamed', 'unknown()']:
            tree.getroot().find('testcase').set('name', name)
            tree.write(file)
            with self.assertRaises(ValueError):
                reconcile(self.expected, self.results, self.coverage)
        tree.getroot().find('testcase').set('name', original)
        duplicate = copy.deepcopy(tree.getroot().find('testcase'))
        tree.getroot().append(duplicate)
        tree.getroot().set('tests', str(len(tree.getroot().findall('testcase'))))
        tree.write(file)
        with self.assertRaises(ValueError):
            reconcile(self.expected, self.results, self.coverage)

    def test_failures_errors_and_skips_cannot_be_hidden_by_summary(self):
        file = next(self.results.glob('*.xml'))
        for tag in ['failure', 'error', 'skipped']:
            tree = ET.parse(file)
            case = tree.getroot().find('testcase')
            ET.SubElement(case, tag)
            tree.write(file)
            with self.assertRaises(ValueError):
                reconcile(self.expected, self.results, self.coverage)
            case.remove(case.find(tag))
            tree.write(file)

    def test_missing_results_and_wrong_suite_count_are_rejected(self):
        file = next(self.results.glob('*.xml'))
        tree = ET.parse(file)
        tree.getroot().set('tests', '0')
        tree.write(file)
        with self.assertRaises(ValueError):
            reconcile(self.expected, self.results, self.coverage)
        for file in self.results.glob('*.xml'):
            file.unlink()
        with self.assertRaises(ValueError):
            reconcile(self.expected, self.results, self.coverage)

    def test_string_value_source_expands_exact_instances(self):
        source = '''@ParameterizedTest @ValueSource(strings = {"en-US", "tr-TR"})
                    void locales(String localeTag) {} @Test void simple() {}'''
        self.assertEqual(['[1] localeTag=en-US', '[2] localeTag=tr-TR', 'simple()'], cases(source))
        booleans = '@ParameterizedTest(name = "source {0}") @ValueSource(booleans={false, true}) void sources(boolean source) {}'
        self.assertEqual(['source false', 'source true'], cases(booleans))
        for invalid in [booleans.replace('false, true', 'false, 1'), booleans.replace('boolean source', 'String source'),
                        booleans.replace('false, true', '"false", "true"')]:
            with self.assertRaises(ValueError): cases(invalid)

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
