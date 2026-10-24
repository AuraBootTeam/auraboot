import React, { useCallback, useEffect, useRef, useState } from 'react';
import { PanelLeft, PanelRight, X } from 'lucide-react';
import { cn } from '~/utils/cn';

const COMPACT_FLOW_DESIGNER_QUERY = '(max-width: 1599px)';
const WIDE_FLOW_WORKSPACE_MIN_WIDTH = 1440;
const DOCKED_INSPECTOR_MIN_WIDTH = 960;

function readCompactFlowDesignerViewport(): boolean {
  if (typeof window === 'undefined') return false;
  if (typeof window.matchMedia === 'function') {
    return window.matchMedia(COMPACT_FLOW_DESIGNER_QUERY).matches;
  }
  return window.innerWidth < 1600;
}

export interface DesignerWorkspaceProps {
  palette?: React.ReactNode;
  canvas: React.ReactNode;
  propertyPanel: React.ReactNode;
  labels: {
    components: string;
    properties: string;
    close: string;
  };
  inspectorFocusKey?: string | null;
  compactWidth?: number;
  testIdPrefix?: string;
}

export function DesignerWorkspace({
  palette,
  canvas,
  propertyPanel,
  labels,
  inspectorFocusKey,
  compactWidth = WIDE_FLOW_WORKSPACE_MIN_WIDTH,
  testIdPrefix = 'flow',
}: DesignerWorkspaceProps) {
  const hasPalette = Boolean(palette);
  const initialCompact = readCompactFlowDesignerViewport();
  const [isCompact, setIsCompact] = useState(initialCompact);
  const [workspaceWidth, setWorkspaceWidth] = useState(() =>
    typeof window === 'undefined' ? WIDE_FLOW_WORKSPACE_MIN_WIDTH : window.innerWidth - 256,
  );
  const [paletteOpen, setPaletteOpen] = useState(() => !initialCompact && hasPalette);
  const [inspectorOpen, setInspectorOpen] = useState(() => !initialCompact);
  const workspaceRef = useRef<HTMLDivElement | null>(null);
  const layoutModeRef = useRef(initialCompact);
  const previousInspectorFocusKey = useRef<string | null | undefined>(inspectorFocusKey);

  const applyViewportMode = useCallback(
    (compact: boolean) => {
      if (layoutModeRef.current === compact) {
        setIsCompact(compact);
        if (!compact) {
          setPaletteOpen(hasPalette);
          setInspectorOpen(true);
        }
        return;
      }

      layoutModeRef.current = compact;
      setIsCompact(compact);
      setPaletteOpen(!compact && hasPalette);
      setInspectorOpen(!compact);
    },
    [hasPalette],
  );

  useEffect(() => {
    if (typeof window === 'undefined') return;

    if (typeof window.matchMedia !== 'function') {
      applyViewportMode(window.innerWidth < 1600);
      return;
    }

    const media = window.matchMedia(COMPACT_FLOW_DESIGNER_QUERY);
    applyViewportMode(media.matches);

    const handleChange = (event: MediaQueryListEvent) => applyViewportMode(event.matches);
    if (typeof media.addEventListener === 'function') {
      media.addEventListener('change', handleChange);
      return () => media.removeEventListener('change', handleChange);
    }

    media.addListener(handleChange);
    return () => media.removeListener(handleChange);
  }, [applyViewportMode, compactWidth]);

  useEffect(() => {
    if (typeof window === 'undefined' || typeof ResizeObserver === 'undefined') return;
    const workspace = workspaceRef.current;
    if (!workspace) return;

    const observer = new ResizeObserver((entries) => {
      const width = entries[0]?.contentRect.width ?? 0;
      if (width > 0) {
        setWorkspaceWidth(width);
        applyViewportMode(width < compactWidth);
      }
    });

    observer.observe(workspace);
    return () => observer.disconnect();
  }, [applyViewportMode, compactWidth]);

  useEffect(() => {
    const previous = previousInspectorFocusKey.current;
    previousInspectorFocusKey.current = inspectorFocusKey;

    if (!isCompact || !inspectorFocusKey || inspectorFocusKey === previous) return;
    setPaletteOpen(false);
    setInspectorOpen(true);
  }, [inspectorFocusKey, isCompact]);

  useEffect(() => {
    if (!isCompact || (!paletteOpen && !inspectorOpen)) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !event.defaultPrevented) {
        setPaletteOpen(false);
        setInspectorOpen(false);
      }
    };
    window.addEventListener('keydown', closeOnEscape);
    return () => window.removeEventListener('keydown', closeOnEscape);
  }, [isCompact, paletteOpen, inspectorOpen]);

  const paletteVisible = hasPalette && (!isCompact || paletteOpen);
  const inspectorVisible = !isCompact || inspectorOpen;
  const inspectorOverlay = isCompact && workspaceWidth < DOCKED_INSPECTOR_MIN_WIDTH;
  const closeDrawers = () => {
    setPaletteOpen(false);
    setInspectorOpen(false);
  };

  return (
    <div
      ref={workspaceRef}
      className="relative flex min-h-0 min-w-0 flex-1 overflow-hidden"
      data-testid={`${testIdPrefix}-designer-workspace`}
      data-layout={isCompact ? 'compact' : 'wide'}
    >
      {isCompact && (
        <div className="pointer-events-none absolute top-3 left-3 z-50 flex gap-2">
          {hasPalette && (
            <button
              type="button"
              onClick={() => {
                setPaletteOpen((open) => !open);
                setInspectorOpen(false);
              }}
              className={cn(
                'pointer-events-auto inline-flex h-9 items-center gap-1.5 rounded-md border px-3 text-sm font-medium shadow-sm',
                paletteOpen
                  ? 'border-blue-300 bg-blue-50 text-blue-700'
                  : 'border-gray-200 bg-white text-gray-700 hover:bg-gray-50',
              )}
              title={labels.components}
              data-testid={`${testIdPrefix}-toggle-palette`}
            >
              <PanelLeft className="h-4 w-4" />
              <span>{labels.components}</span>
            </button>
          )}
          <button
            type="button"
            onClick={() => {
              setInspectorOpen((open) => !open);
              setPaletteOpen(false);
            }}
            className={cn(
              'pointer-events-auto inline-flex h-9 items-center gap-1.5 rounded-md border px-3 text-sm font-medium shadow-sm',
              inspectorOpen
                ? 'border-blue-300 bg-blue-50 text-blue-700'
                : 'border-gray-200 bg-white text-gray-700 hover:bg-gray-50',
            )}
            title={labels.properties}
            data-testid={`${testIdPrefix}-toggle-inspector`}
          >
            <PanelRight className="h-4 w-4" />
            <span>{labels.properties}</span>
          </button>
        </div>
      )}

      {isCompact && (paletteOpen || (inspectorOverlay && inspectorOpen)) && (
        <button
          type="button"
          aria-label={labels.close}
          onClick={closeDrawers}
          className="absolute inset-0 z-30 bg-gray-900/20"
          data-testid={`${testIdPrefix}-drawer-backdrop`}
        />
      )}

      {hasPalette && (
        <div
          className={cn(
            isCompact
              ? 'absolute inset-y-0 left-0 z-40 flex w-64 max-w-full bg-white pt-12 shadow-2xl'
              : 'relative z-20 flex shrink-0',
            !paletteVisible && 'hidden',
          )}
          data-testid={`${testIdPrefix}-palette-shell`}
          data-open={paletteVisible ? 'true' : 'false'}
        >
          {isCompact && (
            <button
              type="button"
              onClick={() => setPaletteOpen(false)}
              className="absolute top-3 right-3 z-10 inline-flex h-8 w-8 items-center justify-center rounded-md border border-gray-200 bg-white text-gray-500 shadow-sm hover:bg-gray-50 hover:text-gray-700"
              title={labels.close}
              data-testid={`${testIdPrefix}-close-palette`}
            >
              <X className="h-4 w-4" />
            </button>
          )}
          {palette}
        </div>
      )}

      <div
        className={cn('flex min-w-0 flex-1 overflow-hidden', isCompact && 'pt-14')}
        data-testid={`${testIdPrefix}-canvas-shell`}
      >
        {canvas}
      </div>

      <div
        className={cn(
          inspectorOverlay
            ? 'absolute inset-y-0 right-0 z-40 flex max-w-full bg-white pt-12 shadow-2xl'
            : 'relative z-20 flex shrink-0',
          !inspectorVisible && 'hidden',
        )}
        data-testid={`${testIdPrefix}-inspector-shell`}
        data-presentation={inspectorOverlay ? 'overlay' : 'docked'}
        data-open={inspectorVisible ? 'true' : 'false'}
      >
        {isCompact && (
          <button
            type="button"
            onClick={() => setInspectorOpen(false)}
            className="absolute top-3 right-3 z-30 inline-flex h-8 w-8 items-center justify-center rounded-md border border-gray-200 bg-white text-gray-500 shadow-sm hover:bg-gray-50 hover:text-gray-700"
            title={labels.close}
            data-testid={`${testIdPrefix}-close-inspector`}
          >
            <X className="h-4 w-4" />
          </button>
        )}
        {propertyPanel}
      </div>
    </div>
  );
}
