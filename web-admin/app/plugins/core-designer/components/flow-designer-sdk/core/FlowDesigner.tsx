// web-admin/app/flow-designer-sdk/core/FlowDesigner.tsx
import React, { useCallback, useEffect, useRef } from 'react';
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
import { cn } from '~/utils/cn';

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
  const { importData, exportData, nodes, edges, setDirty, undo, redo, isDirty, bumpRegistryVersion, setMonitorMode, setMonitorData } = useFlowStore();
  const { validate } = useFlowValidation();
  const containerRef = useRef<HTMLDivElement>(null);
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
  useEffect(() => {
    nodeRegistry.clear();
    nodeRegistry.registerAll(config.nodeDefinitions);
    bumpRegistryVersion();
  }, [config.nodeDefinitions, bumpRegistryVersion]);

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

        <div className="flex flex-1 overflow-hidden">
          {!readOnly && <FlowPalette categoryOrder={config.categoryOrder} />}

          <FlowCanvas
            readOnly={readOnly}
            showMinimap={config.showMinimap}
            showControls={config.showControls}
          />

          <FlowPropertyPanel readOnly={readOnly} />
        </div>
      </div>
    </NodeRuntimeStatusProvider>
  );
}

export default FlowDesigner;
