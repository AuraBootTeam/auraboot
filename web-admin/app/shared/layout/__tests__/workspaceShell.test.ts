import { describe, expect, it } from 'vitest';
import {
  findCurrentNavigationLink,
  resolveWorkspaceShell,
  workspaceSidebarGeometry,
} from '../workspaceShell';

describe('opt-in workspace shell geometry', () => {
  const shell = resolveWorkspaceShell({
    useThemeTokens: true,
    headerHeight: 64,
    sidebar: {
      expandedWidth: 218,
      compactWidth: 190,
      compactBreakpoint: 1180,
      collapsedWidth: 64,
      mobileBreakpoint: 700,
      brandHeight: 80,
    },
  })!;
  it('preserves omission and the legacy defaults', () => {
    expect(resolveWorkspaceShell(undefined)).toBeNull();
    const legacy = resolveWorkspaceShell({})!;
    expect(legacy.headerHeight).toBe(56);
    expect(legacy.mobileHeaderHeight).toBe(56);
    expect(resolveWorkspaceShell({ headerHeight: 64 })?.mobileHeaderHeight).toBe(64);
    expect(
      resolveWorkspaceShell({ headerHeight: 64, mobileHeaderHeight: 55 })?.mobileHeaderHeight,
    ).toBe(55);
    expect(
      resolveWorkspaceShell({ headerHeight: 64, mobileHeaderHeight: -1 })?.mobileHeaderHeight,
    ).toBe(64);
    expect(legacy.sidebar.brandHeight).toBe(56);
    expect(workspaceSidebarGeometry(legacy, 1440, false).panelWidth).toBe(216);
    expect(workspaceSidebarGeometry(legacy, 1440, true).panelWidth).toBe(68);
  });
  it('uses one complete width and breakpoint contract', () => {
    expect(shell.headerHeight).toBe(64);
    expect(shell.sidebar.brandHeight).toBe(80);
    expect(workspaceSidebarGeometry(shell, 1440, false)).toEqual({
      overlay: false,
      collapsed: false,
      panelWidth: 218,
      occupiedWidth: 218,
    });
    expect(workspaceSidebarGeometry(shell, 1180, false).panelWidth).toBe(190);
    expect(workspaceSidebarGeometry(shell, 701, false).occupiedWidth).toBe(190);
    expect(workspaceSidebarGeometry(shell, 1440, true).panelWidth).toBe(64);
    expect(workspaceSidebarGeometry(shell, 700, true)).toEqual({
      overlay: true,
      collapsed: false,
      panelWidth: 190,
      occupiedWidth: 0,
    });
  });
  it('does not pass invalid geometry through to CSS', () => {
    const value = resolveWorkspaceShell({
      headerHeight: -1,
      sidebar: { expandedWidth: Number.NaN, mobileBreakpoint: 0 },
    })!;
    expect(value.headerHeight).toBe(56);
    expect(value.sidebar.expandedWidth).toBe(216);
    expect(value.sidebar.mobileBreakpoint).toBe(1023);
  });
  it('locates the semantic current link even when only a decoy retains the old active color', () => {
    const nav = document.createElement('nav');
    nav.innerHTML =
      '<a class="bg-blue-100">Inactive</a><a aria-current="page" class="bg-accent-weak">Current</a>';
    expect(findCurrentNavigationLink(nav)?.textContent).toBe('Current');
  });
});
