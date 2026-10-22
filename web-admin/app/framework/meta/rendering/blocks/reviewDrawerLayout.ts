import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import type { BlockConfig } from '~/framework/meta/schemas/types';

export type PointerState = {
  x: number;
  y: number;
  left?: number;
  top?: number;
  width?: number;
  height?: number;
};

export type DrawerLayoutState = {
  left: number;
  top: number;
  width: number;
  height: number;
};

export const DEFAULT_DRAWER_LAYOUT: DrawerLayoutState = {
  left: 24,
  top: 24,
  width: 1100,
  height: 640,
};

export const MIN_DRAWER_WIDTH = 760;

export const MIN_DRAWER_HEIGHT = 500;

export const DRAWER_STORAGE_PREFIX = 'auraboot:review-drawer-layout';

export function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

export function getViewportSize(): { width: number; height: number } {
  if (typeof window === 'undefined') return { width: 1280, height: 720 };
  return {
    width: window.innerWidth || 1280,
    height: window.innerHeight || 720,
  };
}

export function normalizeDrawerLayout(layout: DrawerLayoutState): DrawerLayoutState {
  const viewport = getViewportSize();
  const maxWidth = Math.max(320, viewport.width - 24);
  const maxHeight = Math.max(320, viewport.height - 24);
  const minWidth = Math.min(MIN_DRAWER_WIDTH, maxWidth);
  const minHeight = Math.min(MIN_DRAWER_HEIGHT, maxHeight);
  const width = clamp(layout.width, minWidth, maxWidth);
  const height = clamp(layout.height, minHeight, maxHeight);
  return {
    width,
    height,
    left: clamp(layout.left, 12, Math.max(12, viewport.width - 180)),
    top: clamp(layout.top, 12, Math.max(12, viewport.height - 84)),
  };
}

export function isDrawerLayoutState(value: unknown): value is DrawerLayoutState {
  if (!value || typeof value !== 'object') return false;
  const layout = value as Record<string, unknown>;
  return ['left', 'top', 'width', 'height'].every((key) => Number.isFinite(layout[key]));
}

export function drawerLayoutStorageKey(
  runtime: SchemaRuntime,
  block: BlockConfig,
  context: Record<string, any>,
): string {
  const schema = (runtime as any).getSchema?.() || {};
  const pageKey =
    context?.$page?.pageKey ||
    context?.$page?.id ||
    schema.pageKey ||
    schema.id ||
    schema.modelCode ||
    'global';
  const modelKey = context?.$page?.modelCode || schema.modelCode || 'model';
  const blockKey = block.id || block.blockType || 'review-drawer';
  return `${DRAWER_STORAGE_PREFIX}:${modelKey}:${pageKey}:${blockKey}`;
}

export function readStoredDrawerLayout(
  storageKey: string,
  fallbackLayout: DrawerLayoutState = DEFAULT_DRAWER_LAYOUT,
  constrainFallbackToViewport = false,
): DrawerLayoutState {
  const resolvedFallback = constrainFallbackToViewport
    ? normalizeDrawerLayout(fallbackLayout)
    : fallbackLayout;
  if (typeof window === 'undefined') return resolvedFallback;
  try {
    const raw = window.localStorage.getItem(storageKey);
    if (!raw) return resolvedFallback;
    const parsed = JSON.parse(raw);
    if (!isDrawerLayoutState(parsed)) return resolvedFallback;
    return normalizeDrawerLayout(parsed);
  } catch {
    return resolvedFallback;
  }
}

export function persistDrawerLayout(storageKey: string, layout: DrawerLayoutState): void {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(storageKey, JSON.stringify(normalizeDrawerLayout(layout)));
  } catch {
    // localStorage may be unavailable in private browsing or strict test environments.
  }
}
