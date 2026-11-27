"""Parity checks for the Node-free adapter to the existing navigation recipe."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('navigation', Path(__file__).with_name('case-role-navigation.py'))
navigation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(navigation)


def pid(number):
    return '01' + str(number).zfill(24)


class NavigationTests(unittest.TestCase):
    def fixture(self, allowed=True, ambiguous=False, stale=False, wrong_readback=False):
        calls, state = [], {'selected': [pid(3)], 'writes': 0}
        roles = [{'code': 'case_viewer', 'permissions': ['case.read']}]
        menus = [{'code': 'case', 'type': 1, 'permissionCode': 'case.read'},
                 {'code': 'write', 'type': 1, 'permissionCode': 'case.write'},
                 {'code': 'unprotected', 'type': 1}]

        def request(route, method='GET', body=None):
            calls.append((route, method, body))
            if route == '/api/roles/all':
                return [{'code': 'case_viewer', 'pid': pid(1)}]
            if route == '/api/menu/all':
                return [{'code': 'root', 'children': [{'code': 'case', 'type': 1, 'pid': pid(2)}]
                        + ([{'code': 'case', 'type': 1, 'pid': pid(4)}] if ambiguous else [])}]
            self.assertEqual(route, '/api/navigation/roles/' + pid(1))
            if body is not None:
                state['writes'] += 1
                self.assertEqual(method, 'PUT')
                self.assertEqual(body['expectedRevision'], 'a' * 64)
                if stale:
                    raise RuntimeError('stale revision')
                state['selected'] = body['selectedMenuPids']
            return {'rolePid': pid(1), 'revision': 'a' * 64,
                    'selectedMenuPids': [] if wrong_readback and state['writes'] else state['selected'],
                    'menus': [{'pid': pid(2), 'enabled': True, 'businessAllowed': allowed, 'directory': False}]}
        return request, roles, menus, calls, state

    def test_only_declared_readable_navigation_is_added_and_existing_selection_is_preserved(self):
        request, roles, menus, calls, state = self.fixture()
        result = navigation.install_case_role_navigation(request, roles, menus)
        self.assertEqual(result['roles'][0]['menuPids'], [pid(2), pid(3)])
        self.assertFalse(result['businessPermissionsChanged'])
        self.assertEqual(state['writes'], 1)
        self.assertFalse(any('permissions' in route or 'user-roles' in route for route, _, _ in calls))

    def test_baseline_and_duplicate_roles_are_rejected_before_inventory_or_writes(self):
        for declarations in [[{'code': 'tenant_admin', 'permissions': []}],
                             [{'code': 'duplicate', 'permissions': []}] * 2]:
            request, _, menus, calls, state = self.fixture()
            with self.assertRaises(RuntimeError):
                navigation.install_case_role_navigation(request, declarations, menus)
            self.assertEqual(calls, [])
            self.assertEqual(state['writes'], 0)

    def test_ambiguous_or_unreadable_targets_are_rejected_before_writes(self):
        for options in [{'allowed': False}, {'ambiguous': True}]:
            request, roles, menus, _, state = self.fixture(**options)
            with self.assertRaises(RuntimeError):
                navigation.install_case_role_navigation(request, roles, menus)
            self.assertEqual(state['writes'], 0)

    def test_conflicts_and_incorrect_readback_cannot_be_reported_as_success(self):
        for options in [{'stale': True}, {'wrong_readback': True}]:
            request, roles, menus, _, state = self.fixture(**options)
            with self.assertRaises(RuntimeError):
                navigation.install_case_role_navigation(request, roles, menus)
            self.assertEqual(state['writes'], 1)


if __name__ == '__main__':
    unittest.main()
