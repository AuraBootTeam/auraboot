/**
 * Unit tests for menu service (pure functions only)
 * Covers the menu transformation helpers and the SSR menu fetch URL resolution.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getTokenMock } = vi.hoisted(() => ({
  getTokenMock: vi.fn(),
}));

vi.mock('~/shared/services/session', () => ({
  getTokenFromRequest: getTokenMock,
}));

import type { MenuItem } from '../menu';
import {
  getUserMenus,
  processMenuData,
  transformMenuForUI,
  isMenuDirectory,
  isClickableMenu,
  getMenuFullPath,
  findMenuByPermissionCode,
  getFlatMenuItems,
} from '../menu';

// ── Helpers ───────────────────────────────────────────────────────────────────

function makeMenu(overrides: Partial<MenuItem> & { id: number; name: string; path: string; type: number; visible: boolean; orderNo: number }): MenuItem {
  return {
    status: 'active',
    deletedFlag: false,
    children: null,
    ...overrides,
  };
}

// ── getUserMenus ──────────────────────────────────────────────────────────────

describe('getUserMenus', () => {
  const originalBffInternalUrl = process.env.BFF_INTERNAL_URL;
  let fetchSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    getTokenMock.mockReset();
    process.env.BFF_INTERNAL_URL = 'http://127.0.0.1:6190';
    fetchSpy = vi.spyOn(global, 'fetch');
  });

  afterEach(() => {
    if (originalBffInternalUrl === undefined) {
      delete process.env.BFF_INTERNAL_URL;
    } else {
      process.env.BFF_INTERNAL_URL = originalBffInternalUrl;
    }
    fetchSpy.mockRestore();
  });

  it('uses the SSR BFF URL and transforms active menu items', async () => {
    getTokenMock.mockResolvedValue('test-jwt');
    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => ({
        code: '0',
        data: [
          makeMenu({
            id: 1,
            pid: 'decisionops-preview',
            code: 'decisionops_console_preview',
            name: '决策中心综合控制台预览',
            path: '/decision-ops',
            type: 1,
            visible: true,
            orderNo: 1,
          }),
        ],
      }),
    } as any);

    const result = await getUserMenus(new Request('http://127.0.0.1:5192/decision-ops'));

    expect(fetchSpy).toHaveBeenCalledWith(
      'http://127.0.0.1:6190/api/menu/user',
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer test-jwt' }),
      }),
    );
    expect(result).toEqual([
      expect.objectContaining({
        id: 'decisionops-preview',
        name: '决策中心综合控制台预览',
        nameKey: 'menu.decisionops_console_preview',
        path: '/decision-ops',
      }),
    ]);
  });
});

// ── processMenuData ───────────────────────────────────────────────────────────

describe('processMenuData', () => {
  it('returns empty array for null / undefined input', () => {
    expect(processMenuData(null as any)).toEqual([]);
    expect(processMenuData(undefined as any)).toEqual([]);
  });

  it('filters out invisible items', () => {
    const menus = [
      makeMenu({ id: 1, name: 'Visible', path: '/v', type: 1, visible: true, orderNo: 1 }),
      makeMenu({ id: 2, name: 'Hidden', path: '/h', type: 1, visible: false, orderNo: 2 }),
    ];

    const result = processMenuData(menus);

    expect(result).toHaveLength(1);
    expect(result[0].name).toBe('Visible');
  });

  it('filters out non-active items', () => {
    const menus = [
      makeMenu({ id: 1, name: 'Active', path: '/a', type: 1, visible: true, orderNo: 1, status: 'active' }),
      makeMenu({ id: 2, name: 'Inactive', path: '/i', type: 1, visible: true, orderNo: 2, status: 'disabled' }),
    ];

    const result = processMenuData(menus);

    expect(result).toHaveLength(1);
    expect(result[0].name).toBe('Active');
  });

  it('filters out deleted items', () => {
    const menus = [
      makeMenu({ id: 1, name: 'Live', path: '/l', type: 1, visible: true, orderNo: 1, deletedFlag: false }),
      makeMenu({ id: 2, name: 'Deleted', path: '/d', type: 1, visible: true, orderNo: 2, deletedFlag: true }),
    ];

    const result = processMenuData(menus);

    expect(result).toHaveLength(1);
    expect(result[0].name).toBe('Live');
  });

  it('sorts by orderNo ascending', () => {
    const menus = [
      makeMenu({ id: 1, name: 'Third', path: '/c', type: 1, visible: true, orderNo: 3 }),
      makeMenu({ id: 2, name: 'First', path: '/a', type: 1, visible: true, orderNo: 1 }),
      makeMenu({ id: 3, name: 'Second', path: '/b', type: 1, visible: true, orderNo: 2 }),
    ];

    const result = processMenuData(menus);

    expect(result.map((m) => m.name)).toEqual(['First', 'Second', 'Third']);
  });

  it('recursively processes children', () => {
    const child = makeMenu({ id: 2, name: 'Child', path: '/p/c', type: 1, visible: true, orderNo: 1 });
    const hiddenChild = makeMenu({ id: 3, name: 'Hidden Child', path: '/p/h', type: 1, visible: false, orderNo: 2 });
    const parent = makeMenu({
      id: 1,
      name: 'Parent',
      path: '/p',
      type: 0,
      visible: true,
      orderNo: 1,
      children: [child, hiddenChild],
    });

    const result = processMenuData([parent]);

    expect(result).toHaveLength(1);
    expect(result[0].children).toHaveLength(1);
    expect(result[0].children![0].name).toBe('Child');
  });

  it('sets children to null when original children is null', () => {
    const menu = makeMenu({ id: 1, name: 'Leaf', path: '/l', type: 1, visible: true, orderNo: 1, children: null });

    const result = processMenuData([menu]);

    expect(result[0].children).toBeNull();
  });
});

// ── transformMenuForUI ────────────────────────────────────────────────────────

describe('transformMenuForUI', () => {
  it('maps MenuItem to UI shape using pid as id', () => {
    const menu = makeMenu({
      id: 1,
      pid: 'menu-pid-1',
      name: 'Dashboard',
      path: '/dashboard',
      type: 1,
      visible: true,
      orderNo: 1,
      icon: 'dashboard-icon',
      permissionCode: 'dashboard.view',
      i18nKey: 'menu.dashboard',
    });

    const result = transformMenuForUI([menu]);

    expect(result[0]).toMatchObject({
      id: 'menu-pid-1',
      name: 'Dashboard',
      path: '/dashboard',
      icon: 'dashboard-icon',
      permissionCode: 'dashboard.view',
      nameKey: 'menu.dashboard',
    });
  });

  it('derives nameKey from code when i18nKey is missing', () => {
    const menu = makeMenu({
      id: 1,
      name: 'Orders',
      path: '/orders',
      type: 1,
      visible: true,
      orderNo: 1,
      code: 'orders',
    });

    const result = transformMenuForUI([menu]);

    expect(result[0].nameKey).toBe('menu.orders');
  });

  it('nameKey is undefined when neither i18nKey nor code is present', () => {
    const menu = makeMenu({ id: 1, name: 'X', path: '/x', type: 1, visible: true, orderNo: 1 });

    const result = transformMenuForUI([menu]);

    expect(result[0].nameKey).toBeUndefined();
  });

  it('maps children to submenu when present', () => {
    const child = makeMenu({ id: 2, pid: 'c-pid', name: 'Sub', path: '/p/s', type: 1, visible: true, orderNo: 1 });
    const parent = makeMenu({
      id: 1,
      pid: 'p-pid',
      name: 'Parent',
      path: '/p',
      type: 0,
      visible: true,
      orderNo: 1,
      children: [child],
    });

    const result = transformMenuForUI([parent]);

    expect(result[0].submenu).toHaveLength(1);
    expect(result[0].submenu[0].id).toBe('c-pid');
  });

  it('sets submenu to undefined when children is empty or null', () => {
    const menu = makeMenu({ id: 1, name: 'Leaf', path: '/l', type: 1, visible: true, orderNo: 1, children: null });

    const result = transformMenuForUI([menu]);

    expect(result[0].submenu).toBeUndefined();
  });
});

// ── isMenuDirectory ───────────────────────────────────────────────────────────

describe('isMenuDirectory', () => {
  it('returns true for type=0 with non-empty children', () => {
    const child = makeMenu({ id: 2, name: 'Child', path: '/c', type: 1, visible: true, orderNo: 1 });
    const dir = makeMenu({ id: 1, name: 'Dir', path: '/d', type: 0, visible: true, orderNo: 1, children: [child] });

    expect(isMenuDirectory(dir)).toBe(true);
  });

  it('returns false for type=0 with empty children', () => {
    const dir = makeMenu({ id: 1, name: 'Dir', path: '/d', type: 0, visible: true, orderNo: 1, children: [] });

    expect(isMenuDirectory(dir)).toBe(false);
  });

  it('returns false for type=1', () => {
    const child = makeMenu({ id: 2, name: 'Child', path: '/c', type: 1, visible: true, orderNo: 1 });
    const menu = makeMenu({ id: 1, name: 'Menu', path: '/m', type: 1, visible: true, orderNo: 1, children: [child] });

    expect(isMenuDirectory(menu)).toBe(false);
  });
});

// ── isClickableMenu ───────────────────────────────────────────────────────────

describe('isClickableMenu', () => {
  it('returns true for type=1 with a path', () => {
    const menu = makeMenu({ id: 1, name: 'Link', path: '/link', type: 1, visible: true, orderNo: 1 });

    expect(isClickableMenu(menu)).toBe(true);
  });

  it('returns false for type=0', () => {
    const dir = makeMenu({ id: 1, name: 'Dir', path: '/dir', type: 0, visible: true, orderNo: 1 });

    expect(isClickableMenu(dir)).toBe(false);
  });

  it('returns false for type=1 with empty path', () => {
    const menu = makeMenu({ id: 1, name: 'No path', path: '', type: 1, visible: true, orderNo: 1 });

    expect(isClickableMenu(menu)).toBe(false);
  });
});

// ── getMenuFullPath ───────────────────────────────────────────────────────────

describe('getMenuFullPath', () => {
  it('returns the path when present', () => {
    const menu = makeMenu({ id: 1, name: 'M', path: '/dashboard', type: 1, visible: true, orderNo: 1 });

    expect(getMenuFullPath(menu)).toBe('/dashboard');
  });

  it('returns # when path is empty', () => {
    const menu = makeMenu({ id: 1, name: 'M', path: '', type: 1, visible: true, orderNo: 1 });

    expect(getMenuFullPath(menu)).toBe('#');
  });
});

// ── findMenuByPermissionCode ──────────────────────────────────────────────────

describe('findMenuByPermissionCode', () => {
  const child = makeMenu({
    id: 2,
    name: 'Orders',
    path: '/orders',
    type: 1,
    visible: true,
    orderNo: 1,
    permissionCode: 'order.list',
  });
  const parent = makeMenu({
    id: 1,
    name: 'Sales',
    path: '/sales',
    type: 0,
    visible: true,
    orderNo: 1,
    permissionCode: 'sales.root',
    children: [child],
  });

  it('finds top-level menu by permissionCode', () => {
    const result = findMenuByPermissionCode([parent], 'sales.root');

    expect(result).not.toBeNull();
    expect(result!.name).toBe('Sales');
  });

  it('finds nested menu by permissionCode', () => {
    const result = findMenuByPermissionCode([parent], 'order.list');

    expect(result).not.toBeNull();
    expect(result!.name).toBe('Orders');
  });

  it('returns null when not found', () => {
    const result = findMenuByPermissionCode([parent], 'nonexistent.code');

    expect(result).toBeNull();
  });
});

// ── getFlatMenuItems ──────────────────────────────────────────────────────────

describe('getFlatMenuItems', () => {
  it('returns only type=1 items with paths in flat list', () => {
    const child1 = makeMenu({ id: 2, name: 'Orders', path: '/orders', type: 1, visible: true, orderNo: 1 });
    const child2 = makeMenu({ id: 3, name: 'Products', path: '/products', type: 1, visible: true, orderNo: 2 });
    const parent = makeMenu({
      id: 1,
      name: 'Sales',
      path: '/sales',
      type: 0,
      visible: true,
      orderNo: 1,
      children: [child1, child2],
    });

    const result = getFlatMenuItems([parent]);

    expect(result).toHaveLength(2);
    expect(result.map((m) => m.name)).toEqual(['Orders', 'Products']);
  });

  it('excludes directory items (type=0)', () => {
    const menu = makeMenu({ id: 1, name: 'Dir', path: '/dir', type: 0, visible: true, orderNo: 1 });

    const result = getFlatMenuItems([menu]);

    expect(result).toHaveLength(0);
  });

  it('handles deeply nested menus', () => {
    const leaf = makeMenu({ id: 3, name: 'Deep', path: '/a/b/deep', type: 1, visible: true, orderNo: 1 });
    const mid = makeMenu({ id: 2, name: 'Mid', path: '/a/b', type: 0, visible: true, orderNo: 1, children: [leaf] });
    const root = makeMenu({ id: 1, name: 'Root', path: '/a', type: 0, visible: true, orderNo: 1, children: [mid] });

    const result = getFlatMenuItems([root]);

    expect(result).toHaveLength(1);
    expect(result[0].name).toBe('Deep');
  });
});
