"""Python transport for the existing case-navigation.v1 HTTP recipe.

Public source setup does not require host Node. Navigation assignments remain
separate from business grants, using the same revision/readback API as the JS adapter.
"""
import re


def need(value, message):
    if not value:
        raise RuntimeError(message)


def install_case_role_navigation(request, declarations, menu_declarations):
    codes = [role.get('code') for role in declarations]
    need(len(codes) == len(set(codes)), 'Case roles must be unique')
    for role in declarations:
        need(isinstance(role.get('code'), str)
             and role['code'] not in ('tenant_admin', 'tenant_member', 'platform_admin')
             and isinstance(role.get('permissions'), list)
             and all(isinstance(code, str) for code in role['permissions']), 'Declared case roles required')
    roles, tree = request('/api/roles/all'), request('/api/menu/all')
    need(isinstance(roles, list) and isinstance(tree, list), 'Explicit role/menu inventory required')

    def flatten(rows):
        return [item for row in rows for item in [row] + flatten(row.get('children') or [])]

    menus, prepared = flatten(tree), []
    for role in declarations:
        found = [item for item in roles if item.get('code') == role['code']]
        need(len(found) == 1 and re.fullmatch(r'[0-9A-Z]{26}', found[0].get('pid', '')),
             'Case role is missing or ambiguous')
        role_pid = found[0]['pid']
        route = '/api/navigation/roles/' + role_pid
        current = request(route)
        need(current.get('rolePid') == role_pid
             and re.fullmatch(r'[a-f0-9]{64}', current.get('revision', ''))
             and isinstance(current.get('selectedMenuPids'), list)
             and isinstance(current.get('menus'), list), 'Invalid current navigation selection')
        selected = set(current['selectedMenuPids'])
        leaves = {item['code'] for item in menu_declarations if item.get('type') == 1
                  and item.get('visible') is not False and item.get('permissionCode')
                  and item['permissionCode'] in role['permissions']}
        for code in sorted(leaves):
            matches = [item for item in menus if item.get('code') == code and item.get('type') == 1]
            need(len(matches) == 1 and re.fullmatch(r'[0-9A-Z]{26}', matches[0].get('pid', '')),
                 'Case menu is missing or ambiguous')
            choice = next((item for item in current['menus'] if item.get('pid') == matches[0]['pid']), None)
            need(choice and choice.get('enabled') and choice.get('businessAllowed')
                 and not choice.get('directory'), 'Case target is not readable by declared role')
            selected.add(matches[0]['pid'])
        prepared.append((role['code'], role_pid, route, current['revision'], sorted(selected)))
    receipts = []
    for code, role_pid, route, revision, selected in prepared:
        request(route, method='PUT', body={'expectedRevision': revision, 'selectedMenuPids': selected})
        actual = request(route)
        need(actual.get('rolePid') == role_pid and isinstance(actual.get('selectedMenuPids'), list)
             and sorted(actual['selectedMenuPids']) == selected, 'Case navigation readback differs')
        receipts.append({'roleCode': code, 'rolePid': role_pid, 'menuPids': selected})
    return {'format': 'auraboot.case-navigation.v1', 'roles': receipts, 'businessPermissionsChanged': False}
