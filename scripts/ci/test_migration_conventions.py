"""Exact internal-storage classification must preserve business-table ratchets."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

CHECKER = Path(__file__).resolve().parents[1] / 'check-migration-conventions.py'
spec = importlib.util.spec_from_file_location('migration_conventions', CHECKER)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class InfrastructurePolicyTest(unittest.TestCase):
    def fixture(self, directory, *, extra='', tenant=True, columns=None, scope='internal-state'):
        root = Path(directory)
        file = root / 'V1__internal.sql'
        file.write_text('CREATE TABLE internal_state ('
                        + ('tenant_id BIGINT, ' if tenant else '')
                        + 'request_hash TEXT PRIMARY KEY);\n' + extra)
        rule = {'scope': scope, 'exemptMissingColumns': columns or ['created_by', 'updated_by', 'pid'],
                'reason': 'Explicit fixture internal storage uses its natural key, not a public editable business record.',
                'evidence': ['scripts/check-migration-conventions.py']}
        value = {'schemaVersion': 1, 'migrations': {file.name: {
            'sha256': hashlib.sha256(file.read_bytes()).hexdigest(), 'tables': {'internal_state': rule}}}}
        policy = root / 'policy.json'; policy.write_text(json.dumps(value))
        return file, policy, value

    def test_only_exact_internal_columns_are_classified_and_business_stays_red(self):
        with tempfile.TemporaryDirectory() as directory:
            file, policy, _ = self.fixture(directory)
            (Path(directory) / 'V2__business.sql').write_text('CREATE TABLE business_order (id BIGINT);')
            exemptions = checker.infrastructure_policy(directory, policy)
            self.assertEqual(len(exemptions), 3)
            remaining = set(checker.issue_lines(directory)) - exemptions
            self.assertEqual(remaining, {'V2__business.sql: business_order: missing '+column+' column'
                                        for column in ['created_by', 'updated_by', 'tenant_id', 'pid']})
            self.assertEqual(len(checker.check_file(file)), 3, 'Default business rules are not weakened')

    def test_changed_migration_bytes_and_renamed_tables_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            file, policy, value = self.fixture(directory)
            file.write_text(file.read_text() + '-- changed original\n')
            with self.assertRaisesRegex(ValueError, 'source changed'):
                checker.infrastructure_policy(directory, policy)
            value['migrations'][file.name]['sha256'] = hashlib.sha256(file.read_bytes()).hexdigest()
            value['migrations'][file.name]['tables']['other_table'] = value['migrations'][file.name]['tables'].pop('internal_state')
            policy.write_text(json.dumps(value))
            with self.assertRaisesRegex(ValueError, 'table declaration'):
                checker.infrastructure_policy(directory, policy)

    def test_destructive_operations_are_never_classified_away(self):
        with tempfile.TemporaryDirectory() as directory:
            _, policy, _ = self.fixture(directory, extra='DROP TABLE old_state;\n')
            remaining = set(checker.issue_lines(directory)) - checker.infrastructure_policy(directory, policy)
            self.assertEqual(remaining, {'V1__internal.sql: destructive: DROP TABLE old_state'})

    def test_tenant_column_exception_requires_explicit_global_provider_scope(self):
        with tempfile.TemporaryDirectory() as directory:
            _, policy, value = self.fixture(directory, tenant=False,
                columns=['created_by', 'updated_by', 'pid', 'tenant_id'])
            with self.assertRaisesRegex(ValueError, 'column scope'):
                checker.infrastructure_policy(directory, policy)
            value['migrations']['V1__internal.sql']['tables']['internal_state']['scope'] = 'global-provider-fact'
            policy.write_text(json.dumps(value))
            self.assertEqual(len(checker.infrastructure_policy(directory, policy)), 4)

    def test_empty_reason_missing_owner_and_broad_rules_are_rejected(self):
        for change in [{'reason': ''}, {'evidence': []}, {'evidence': ['missing-owner.java']},
                       {'scope': 'business'}, {'exemptMissingColumns': ['DROP TABLE']},
                       {'exemptMissingColumns': ['pid', 'pid']}]:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                _, policy, value = self.fixture(directory)
                value['migrations']['V1__internal.sql']['tables']['internal_state'].update(change)
                policy.write_text(json.dumps(value))
                with self.assertRaises(ValueError): checker.infrastructure_policy(directory, policy)

    def test_unused_classification_is_rejected_instead_of_accumulating_debt(self):
        with tempfile.TemporaryDirectory() as directory:
            _, policy, _ = self.fixture(directory, columns=['tenant_id'])
            with self.assertRaises(ValueError): checker.infrastructure_policy(directory, policy)

    def test_actual_policy_matches_owned_original_migrations(self):
        scripts = CHECKER.parent
        root = scripts.parent / 'platform/src/main/resources/db/migration/core'
        classified = checker.infrastructure_policy(root, scripts / 'migration-conventions-infrastructure.json')
        self.assertTrue(classified)
        baseline = set((scripts / 'migration-conventions-baseline.txt').read_text().splitlines())
        self.assertEqual(set(checker.issue_lines(root)) - classified - baseline, set())
        self.assertFalse(classified & baseline, 'Infrastructure scope is separate from accepted historical debt')


if __name__ == '__main__':
    unittest.main()
