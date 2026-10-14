import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { UnifiedSchema } from '~/framework/meta/schemas/types';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';

interface PagePresentation {
  owner: string;
  mode: 'workspace';
  section: string;
  title: string;
}
const PresentationContext = createContext<{
  sidebarWidth: number;
  setSidebarWidth: (width: number) => void;
  presentation: PagePresentation | null;
  setPresentation: (value: PagePresentation) => void;
  clearPresentation: (owner: string) => void;
}>({ sidebarWidth: 216, setSidebarWidth: () => {}, presentation: null, setPresentation: () => {}, clearPresentation: () => {} });

export function PagePresentationProvider({ children }: { children: React.ReactNode }) {
  const [sidebarWidth, setSidebarWidth] = useState(216);
  const [presentation, setPresentation] = useState<PagePresentation | null>(null);
  const clearPresentation = useCallback((owner: string) => setPresentation(current => current?.owner === owner ? null : current), []);
  const value = useMemo(() => ({ sidebarWidth, setSidebarWidth, presentation, setPresentation, clearPresentation }), [sidebarWidth, presentation, clearPresentation]);
  return <PresentationContext.Provider value={value}>{children}</PresentationContext.Provider>;
}

export function usePagePresentation() { return useContext(PresentationContext).presentation; }

export function useSchemaPresentation(schema: UnifiedSchema | null | undefined, titleOverride?: string | Record<string, string>) {
  const { setPresentation, clearPresentation } = useContext(PresentationContext);
  const { locale, t } = useI18n();
  const owner = schema?.pageKey || '';
  const mode = schema?.extension?.presentation;
  const section = getLocalizedText(schema?.extension?.workspaceLabel || {}, locale, t);
  const title = getLocalizedText(titleOverride || schema?.title || schema?.name || '', locale, t);
  useEffect(() => {
    if (mode !== 'workspace' || !owner) return;
    setPresentation({ owner, mode, section, title });
    return () => clearPresentation(owner);
  }, [owner, mode, section, title, setPresentation, clearPresentation]);
}

export function usePresentationNavigation() { const { sidebarWidth, setSidebarWidth } = useContext(PresentationContext); return { sidebarWidth, setSidebarWidth }; }
