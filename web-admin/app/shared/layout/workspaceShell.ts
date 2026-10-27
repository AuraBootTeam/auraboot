import { useEffect, useState } from 'react';
import type { WorkspaceShellConfig } from '~/framework/meta/schemas/types';

export interface ResolvedWorkspaceShell {
  useThemeTokens: boolean;
  headerHeight: number;
  mobileHeaderHeight: number;
  sidebar: {
    expandedWidth: number;
    compactWidth: number;
    compactBreakpoint: number;
    collapsedWidth: number;
    mobileBreakpoint: number;
    brandHeight: number;
  };
}

function positiveInteger(value: unknown, fallback: number, maximum = 4096): number {
  return typeof value === 'number' &&
    Number.isFinite(value) &&
    Number.isInteger(value) &&
    value > 0 &&
    value <= maximum
    ? value
    : fallback;
}

export function resolveWorkspaceShell(
  raw: WorkspaceShellConfig | undefined,
): ResolvedWorkspaceShell | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const sidebar = raw.sidebar && typeof raw.sidebar === 'object' ? raw.sidebar : {};
  const expandedWidth = positiveInteger(sidebar.expandedWidth, 216, 1024);
  const compactWidth = Math.min(
    expandedWidth,
    positiveInteger(sidebar.compactWidth, expandedWidth, 1024),
  );
  const collapsedWidth = Math.min(compactWidth, positiveInteger(sidebar.collapsedWidth, 68, 1024));
  const headerHeight = positiveInteger(raw.headerHeight, 56, 256);
  return {
    useThemeTokens: raw.useThemeTokens === true,
    headerHeight,
    mobileHeaderHeight: positiveInteger(raw.mobileHeaderHeight, headerHeight, 256),
    sidebar: {
      expandedWidth,
      compactWidth,
      compactBreakpoint: positiveInteger(sidebar.compactBreakpoint, 1180),
      collapsedWidth,
      mobileBreakpoint: positiveInteger(sidebar.mobileBreakpoint, 1023),
      brandHeight: positiveInteger(sidebar.brandHeight, 56, 256),
    },
  };
}

export function workspaceSidebarGeometry(
  shell: ResolvedWorkspaceShell,
  viewportWidth: number,
  collapsed: boolean,
): { overlay: boolean; collapsed: boolean; panelWidth: number; occupiedWidth: number } {
  const overlay = viewportWidth <= shell.sidebar.mobileBreakpoint;
  const effectiveCollapsed = !overlay && collapsed;
  const panelWidth = overlay
    ? shell.sidebar.compactWidth
    : effectiveCollapsed
      ? shell.sidebar.collapsedWidth
      : viewportWidth <= shell.sidebar.compactBreakpoint
        ? shell.sidebar.compactWidth
        : shell.sidebar.expandedWidth;
  return {
    overlay,
    collapsed: effectiveCollapsed,
    panelWidth,
    occupiedWidth: overlay ? 0 : panelWidth,
  };
}

/** One viewport source per shell owner; no CSS breakpoint guesses in consumers. */
export function useViewportWidth(): number {
  const [width, setWidth] = useState(() =>
    typeof window === 'undefined' ? 1280 : window.innerWidth,
  );
  useEffect(() => {
    const update = () => setWidth(window.innerWidth);
    update();
    window.addEventListener('resize', update);
    return () => window.removeEventListener('resize', update);
  }, []);
  return width;
}

/** Active navigation follows router semantics, independently of the theme. */
export function findCurrentNavigationLink(root: ParentNode): HTMLElement | null {
  return root.querySelector<HTMLElement>('[aria-current="page"]');
}
