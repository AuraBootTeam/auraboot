import { expect, type Page } from '@playwright/test';

type Menu = { code: string; pid: string; type: number; children?: Menu[] };

/** Explicit test-fixture navigation only; business grants remain independently provisioned. */
export async function assignRoleNavigation(page: Page, rolePid: string, menuCodes: string[]) {
  const read = async (route: string) => {
    const response = await page.request.get(route);
    expect(response.status()).toBe(200);
    const envelope = await response.json();
    expect(String(envelope.code)).toBe('0');
    return envelope.data;
  };
  const flatten = (rows: Menu[]): Menu[] =>
    rows.flatMap((row) => [row, ...flatten(row.children || [])]);
  const menus = flatten(await read('/api/menu/all'));
  const route = `/api/navigation/roles/${rolePid}`;
  const current = await read(route);
  expect(current.rolePid).toBe(rolePid);
  expect(current.revision).toMatch(/^[a-f0-9]{64}$/);
  const selected = new Set<string>(current.selectedMenuPids);
  for (const code of menuCodes) {
    const matches = menus.filter((menu) => menu.code === code && menu.type === 1);
    expect(matches, `unambiguous explicit fixture menu ${code}`).toHaveLength(1);
    const choice = current.menus.find((menu: { pid: string }) => menu.pid === matches[0].pid);
    expect(choice, `navigation must not grant missing business access: ${code}`).toMatchObject({
      enabled: true,
      businessAllowed: true,
      directory: false,
    });
    selected.add(matches[0].pid);
  }
  const selectedMenuPids = [...selected].sort();
  const saved = await page.request.put(route, {
    data: { expectedRevision: current.revision, selectedMenuPids },
  });
  expect(saved.status()).toBe(200);
  expect(String((await saved.json()).code)).toBe('0');
  const actual = await read(route);
  expect([...actual.selectedMenuPids].sort()).toEqual(selectedMenuPids);
}
