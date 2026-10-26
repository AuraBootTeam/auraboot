import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { UnifiedSchema } from '~/framework/meta/schemas/types';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import {
  resolveWorkspaceShell,
  useViewportWidth,
  type ResolvedWorkspaceShell,
} from './workspaceShell';

interface PagePresentation {
  owner: string;
  mode: 'workspace';
  section: string;
  title: string;
  workspaceShell: ResolvedWorkspaceShell | null;
}
const PresentationContext = createContext<{
  sidebarWidth: number;
  viewportWidth: number;
  shell: ResolvedWorkspaceShell | null;
  isOverlay: boolean;
  headerHeight: number;
  setSidebarWidth: (width: number) => void;
  presentation: PagePresentation | null;
  setPresentation: (value: PagePresentation) => void;
  clearPresentation: (owner: string) => void;
}>({
  viewportWidth: 1280,
  shell: null,
  isOverlay: false,
  headerHeight: 56,
  sidebarWidth: 216,
  setSidebarWidth: () => {},
  presentation: null,
  setPresentation: () => {},
  clearPresentation: () => {},
});

export function PagePresentationProvider({ children }: { children: React.ReactNode }) {
  const [panelWidth, setSidebarWidth] = useState(216);
  const viewportWidth = useViewportWidth();
  const [presentation, setPresentation] = useState<PagePresentation | null>(null);
  const clearPresentation = useCallback(
    (owner: string) => setPresentation((current) => (current?.owner === owner ? null : current)),
    [],
  );
  const shell = presentation?.workspaceShell ?? null;
  const isOverlay = Boolean(shell && viewportWidth <= shell.sidebar.mobileBreakpoint);
  const sidebarWidth = shell && isOverlay ? 0 : panelWidth;
  const headerHeight = shell ? (isOverlay ? shell.mobileHeaderHeight : shell.headerHeight) : 56;
  const value = useMemo(
    () => ({
      sidebarWidth,
      setSidebarWidth,
      viewportWidth,
      shell,
      isOverlay,
      headerHeight,
      presentation,
      setPresentation,
      clearPresentation,
    }),
    [sidebarWidth, viewportWidth, shell, isOverlay, headerHeight, presentation, clearPresentation],
  );
  return <PresentationContext.Provider value={value}>{children}</PresentationContext.Provider>;
}

export function usePagePresentation() {
  return useContext(PresentationContext).presentation;
}

export function useSchemaPresentation(
  schema: UnifiedSchema | null | undefined,
  titleOverride?: string | Record<string, string>,
  enabled = true,
) {
  const { setPresentation, clearPresentation } = useContext(PresentationContext);
  const { locale, t } = useI18n();
  const owner = schema?.pageKey || '';
  const mode = schema?.extension?.presentation;
  const workspaceShell = useMemo(
    () => resolveWorkspaceShell(schema?.extension?.workspaceShell),
    [schema?.extension?.workspaceShell],
  );
  const section = getLocalizedText(schema?.extension?.workspaceLabel || {}, locale, t);
  const title = getLocalizedText(titleOverride || schema?.title || schema?.name || '', locale, t);
  useEffect(() => {
    if (!enabled || mode !== 'workspace' || !owner) return;
    setPresentation({ owner, mode, section, title, workspaceShell });
    return () => clearPresentation(owner);
  }, [owner, mode, section, title, workspaceShell, setPresentation, clearPresentation, enabled]);
}

export function usePresentationNavigation() {
  const { sidebarWidth, setSidebarWidth, viewportWidth, shell, isOverlay, headerHeight } =
    useContext(PresentationContext);
  return { sidebarWidth, setSidebarWidth, viewportWidth, shell, isOverlay, headerHeight };
}
