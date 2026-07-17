// web-admin/app/flow-designer-sdk/core/FlowDesigner.tsx
import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { PanelLeft, PanelRight, X } from 'lucide-react';
import { useFlowStore } from '../store/useFlowStore';
import { nodeRegistry } from '../nodes/NodeRegistry';
import { FlowToolbar } from './FlowToolbar';
import { FlowPalette } from './FlowPalette';
import { FlowCanvas } from './FlowCanvas';
import { FlowPropertyPanel } from './FlowPropertyPanel';
import { useFlowAutoSave } from './useFlowAutoSave';
import { useFlowValidation } from '../validation/useFlowValidation';
import type { FlowNodeDefinition } from '../nodes/types';
import type { FlowData } from '../store/types';
import type { FlowMonitorData } from '../store/monitorTypes';
import {
  NodeRuntimeStatusProvider,
  type NodeStatusMap,
} from '../runtime/NodeRuntimeStatusContext';
import { useSmartText } from '~/utils/i18n';
import { cn } from '~/utils/cn';

const COMPACT_FLOW_DESIGNER_QUERY = '(max-width: 1599px)';
const WIDE_FLOW_WORKSPACE_MIN_WIDTH = 1440;

function readCompactFlowDesignerViewport(): boolean {
  if (typeof window === 'undefined') return false;
  if (typeof window.matchMedia === 'function') {
    return window.matchMedia(COMPACT_FLOW_DESIGNER_QUERY).matches;
  }
  return window.innerWidth < 1600;
}

export interface FlowDesignerWorkspaceProps {
  palette?: React.ReactNode;
  canvas: React.ReactNode;
  propertyPanel: React.ReactNode;
  labels: {
    components: string;
    properties: string;
    close: string;
  };
  inspectorFocusKey?: string | null;
}

export function FlowDesignerWorkspace({
  palette,
  canvas,
  propertyPanel,
  labels,
  inspectorFocusKey,
}: FlowDesignerWorkspaceProps) {
  const hasPalette = Boolean(palette);
  const initialCompact = readCompactFlowDesignerViewport();
  const [isCompact, setIsCompact] = useState(initialCompact);
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
  }, [applyViewportMode]);

  useEffect(() => {
    if (typeof window === 'undefined' || typeof ResizeObserver === 'undefined') return;
    const workspace = workspaceRef.current;
    if (!workspace) return;

    const observer = new ResizeObserver((entries) => {
      const width = entries[0]?.contentRect.width ?? 0;
      if (width > 0) {
        applyViewportMode(width < WIDE_FLOW_WORKSPACE_MIN_WIDTH);
      }
    });

    observer.observe(workspace);
    return () => observer.disconnect();
  }, [applyViewportMode]);

  useEffect(() => {
    const previous = previousInspectorFocusKey.current;
    previousInspectorFocusKey.current = inspectorFocusKey;

    if (!isCompact || !inspectorFocusKey || inspectorFocusKey === previous) return;
    setPaletteOpen(false);
    setInspectorOpen(true);
  }, [inspectorFocusKey, isCompact]);

  const paletteVisible = hasPalette && (!isCompact || paletteOpen);
  const inspectorVisible = !isCompact || inspectorOpen;
  const closeDrawers = () => {
    setPaletteOpen(false);
    setInspectorOpen(false);
  };

  return (
    <div
      ref={workspaceRef}
      className="relative flex flex-1 overflow-hidden"
      data-testid="flow-designer-workspace"
      data-layout={isCompact ? 'compact' : 'wide'}
    >
      {isCompact && (
        <div className="pointer-events-none absolute left-3 top-3 z-50 flex gap-2">
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
              data-testid="flow-toggle-palette"
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
            data-testid="flow-toggle-inspector"
          >
            <PanelRight className="h-4 w-4" />
            <span>{labels.properties}</span>
          </button>
        </div>
      )}

      {isCompact && (paletteOpen || inspectorOpen) && (
        <button
          type="button"
          aria-label={labels.close}
          onClick={closeDrawers}
          className="absolute inset-0 z-30 bg-gray-900/20"
          data-testid="flow-drawer-backdrop"
        />
      )}

      {hasPalette && (
        <div
          className={cn(
            isCompact
              ? 'absolute inset-y-0 left-0 z-40 w-64 max-w-[calc(100vw-2rem)] shadow-2xl'
              : 'relative z-20 flex shrink-0',
            !paletteVisible && 'hidden',
          )}
          data-testid="flow-palette-shell"
          data-open={paletteVisible ? 'true' : 'false'}
        >
          {isCompact && (
            <button
              type="button"
              onClick={() => setPaletteOpen(false)}
              className="absolute right-3 top-3 z-10 inline-flex h-8 w-8 items-center justify-center rounded-md border border-gray-200 bg-white text-gray-500 shadow-sm hover:bg-gray-50 hover:text-gray-700"
              title={labels.close}
              data-testid="flow-close-palette"
            >
              <X className="h-4 w-4" />
            </button>
          )}
          {palette}
        </div>
      )}

      <div className="flex min-w-0 flex-1 overflow-hidden" data-testid="flow-canvas-shell">
        {canvas}
      </div>

      <div
        className={cn(
          isCompact
            ? 'absolute inset-y-0 right-0 z-40 max-w-[calc(100vw-2rem)] shadow-2xl'
            : 'relative z-20 flex shrink-0',
          !inspectorVisible && 'hidden',
        )}
        data-testid="flow-inspector-shell"
        data-open={inspectorVisible ? 'true' : 'false'}
      >
        {isCompact && (
          <button
            type="button"
            onClick={() => setInspectorOpen(false)}
            className="absolute right-3 top-3 z-30 inline-flex h-8 w-8 items-center justify-center rounded-md border border-gray-200 bg-white text-gray-500 shadow-sm hover:bg-gray-50 hover:text-gray-700"
            title={labels.close}
            data-testid="flow-close-inspector"
          >
            <X className="h-4 w-4" />
          </button>
        )}
        {propertyPanel}
      </div>
    </div>
  );
}

export interface FlowDesignerConfig {
  nodeDefinitions: FlowNodeDefinition[];
  categoryOrder?: string[];
  showMinimap?: boolean;
  showControls?: boolean;
}

export interface FlowDesignerProps {
  config: FlowDesignerConfig;
  initialData?: FlowData;
  title?: string;
  onSave?: (data: FlowData) => Promise<void>;
  onChange?: (data: FlowData) => void;
  onValidate?: () => void;
  readOnly?: boolean;
  className?: string;
  autoSave?: boolean;
  autoSaveDelay?: number;
  /**
   * G8 — when true, the designer enters monitor mode. Combine with
   * `monitorData` to feed runtime per-node status (pending/running/...).
   * Node renderers and editors read via `useNodeMonitorStatus(nodeId)`.
   */
  monitorMode?: boolean;
  /** G8 — runtime status keyed by node id. Ignored unless monitorMode=true. */
  monitorData?: FlowMonitorData;
  /**
   * G5 — external runtime status overlay (used by AutomationEditor with backend
   * `/api/automation/executions/{instanceId}/node-statuses`). When provided,
   * nodes whose id appears in the map render a coloured ring + badge.
   * Vocabulary: 'pending' | 'running' | 'completed' | 'failed' | 'skipped' —
   * mirrors `ab_automation_node_execution.status`. Bridge to G8 monitor is
   * a follow-up (see B2a report TODO).
   */
  nodeStatuses?: NodeStatusMap | null;
}

export function FlowDesigner({
  config,
  initialData,
  title,
  onSave,
  onChange,
  onValidate,
  readOnly = false,
  className,
  autoSave = false,
  autoSaveDelay,
  monitorMode = false,
  monitorData,
  nodeStatuses,
}: FlowDesignerProps) {
  const {
    importData,
    exportData,
    nodes,
    edges,
    selectedNodeId,
    selectedEdgeId,
    setDirty,
    undo,
    redo,
    isDirty,
    bumpRegistryVersion,
    setMonitorMode,
    setMonitorData,
  } = useFlowStore();
  const { validate } = useFlowValidation();
  const st = useSmartText();
  const containerRef = useRef<HTMLDivElement>(null);
  const [registryReady, setRegistryReady] = useState(false);
  // Track whether we are currently importing data so we can suppress the
  // onChange notification that would otherwise fire for every importData call.
  // Without this guard, importing pre-populated node config triggers:
  //   onChange → parent updates initialData → importData → onChange → ...
  const isImportingRef = useRef(false);

  // G8 — sync monitor props into the store so every node renderer /
  // PropertyEditor that calls useNodeMonitorStatus sees the same data.
  useEffect(() => {
    setMonitorMode(monitorMode);
  }, [monitorMode, setMonitorMode]);

  useEffect(() => {
    setMonitorData(monitorData ?? {});
  }, [monitorData, setMonitorData]);

  // Register node definitions and bump registryVersion so FlowPalette/FlowCanvas re-render
  useLayoutEffect(() => {
    setRegistryReady(false);
    nodeRegistry.clear();
    nodeRegistry.registerAll(config.nodeDefinitions);
    bumpRegistryVersion();
  }, [config.nodeDefinitions, bumpRegistryVersion]);

  useEffect(() => {
    setRegistryReady(true);
  }, [config.nodeDefinitions]);

  // E2E affordance (dev/test only): expose the live flow store so golden specs can
  // read nodes/edges/validation without scraping the DOM. Mirrors the bpmn slice's
  // `window.__bpmnDesignerStore`. Never attached in production builds.
  useEffect(() => {
    if (import.meta.env.PROD) return;
    (window as unknown as { __flowDesignerStore?: typeof useFlowStore }).__flowDesignerStore =
      useFlowStore;
    return () => {
      delete (window as unknown as { __flowDesignerStore?: unknown }).__flowDesignerStore;
    };
  }, []);

  // Load initial data. Always import on mount — even when there is no
  // initialData (a fresh "new" canvas). importData seeds the undo history with
  // the initial snapshot (history:[snapshot], historyIndex:0), so the FIRST
  // edit (e.g. the first dragged node) is undoable; without it the store starts
  // at historyIndex:-1 and canUndo() stays false after the first edit. Importing
  // the empty default also guarantees a clean slate rather than inheriting stale
  // nodes from a previously-mounted designer instance (the store is a singleton).
  useEffect(() => {
    isImportingRef.current = true;
    importData(initialData ?? { nodes: [], edges: [] });
    // Reset the flag on the next microtask so that the onChange effect
    // (which runs synchronously in the same render cycle) sees it as false
    // only after this import batch has settled.
    Promise.resolve().then(() => {
      isImportingRef.current = false;
    });
  }, [initialData, importData]);

  // Notify onChange — skip while an importData call is in progress to prevent
  // the infinite loop: importData → nodes/edges change → onChange → parent
  // updates initialData → importData again.
  useEffect(() => {
    if (onChange && !isImportingRef.current) {
      onChange({ nodes, edges });
    }
  }, [nodes, edges, onChange]);

  const handleSave = useCallback(async () => {
    if (!onSave) return;
    // P0-4: validation gate. Block save on errors; validate() publishes the
    // result (field-level error states via FlowFieldAdapter) and selects the
    // first errored node so the user sees what to fix.
    const result = validate();
    if (!result.valid) return;
    const data = exportData();
    await onSave(data);
    setDirty(false);
  }, [onSave, exportData, setDirty, validate]);

  // Auto-save
  const { saveStatus } = useFlowAutoSave({
    onSave: handleSave,
    enabled: autoSave && !readOnly && !!onSave,
    delay: autoSaveDelay,
    isDirty,
  });

  // Keyboard shortcuts
  useEffect(() => {
    if (readOnly) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      const isMod = e.metaKey || e.ctrlKey;

      // Undo: Ctrl/Cmd+Z (without Shift)
      if (isMod && e.key === 'z' && !e.shiftKey) {
        e.preventDefault();
        undo();
        return;
      }

      // Redo: Ctrl/Cmd+Y or Ctrl/Cmd+Shift+Z
      if (isMod && (e.key === 'y' || (e.key === 'z' && e.shiftKey))) {
        e.preventDefault();
        redo();
        return;
      }

      // Save: Ctrl/Cmd+S
      if (isMod && e.key === 's') {
        e.preventDefault();
        handleSave();
        return;
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [readOnly, undo, redo, handleSave]);

  return (
    <NodeRuntimeStatusProvider statuses={nodeStatuses}>
      <div ref={containerRef} className={cn('flex h-full flex-col bg-gray-50', className)}>
        <FlowToolbar
          title={title}
          onSave={onSave ? handleSave : undefined}
          onValidate={onValidate}
          readOnly={readOnly}
          saveStatus={autoSave ? saveStatus : undefined}
        />

        {registryReady ? (
          <FlowDesignerWorkspace
            labels={{
              components: st('$i18n:flow.palette.title') || 'Components',
              properties: st('$i18n:flow.panel.properties') || 'Properties',
              close: st('$i18n:flow.panel.close') || 'Close panel',
            }}
            inspectorFocusKey={selectedNodeId ?? selectedEdgeId ?? null}
            palette={!readOnly ? <FlowPalette categoryOrder={config.categoryOrder} /> : null}
            canvas={
              <FlowCanvas
                readOnly={readOnly}
                showMinimap={config.showMinimap}
                showControls={config.showControls}
              />
            }
            propertyPanel={<FlowPropertyPanel readOnly={readOnly} />}
          />
        ) : (
          <div className="text-text-3 flex flex-1 items-center justify-center text-sm">
            加载设计器...
          </div>
        )}
      </div>
    </NodeRuntimeStatusProvider>
  );
}

export default FlowDesigner;
