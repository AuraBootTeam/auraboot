// web-admin/app/flow-designer-sdk/core/FlowDesigner.tsx
import React, { useCallback, useEffect, useRef } from 'react';
import { useFlowStore } from '../store/useFlowStore';
import { nodeRegistry } from '../nodes/NodeRegistry';
import { FlowToolbar } from './FlowToolbar';
import { FlowPalette } from './FlowPalette';
import { FlowCanvas } from './FlowCanvas';
import { FlowPropertyPanel } from './FlowPropertyPanel';
import { useFlowAutoSave } from './useFlowAutoSave';
import type { FlowNodeDefinition } from '../nodes/types';
import type { FlowData } from '../store/types';
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
}: FlowDesignerProps) {
  const { importData, exportData, nodes, edges, setDirty, undo, redo, isDirty, bumpRegistryVersion } = useFlowStore();
  const containerRef = useRef<HTMLDivElement>(null);
  // Track whether we are currently importing data so we can suppress the
  // onChange notification that would otherwise fire for every importData call.
  // Without this guard, importing pre-populated node config triggers:
  //   onChange → parent updates initialData → importData → onChange → ...
  const isImportingRef = useRef(false);

  // Register node definitions and bump registryVersion so FlowPalette/FlowCanvas re-render
  useEffect(() => {
    nodeRegistry.clear();
    nodeRegistry.registerAll(config.nodeDefinitions);
    bumpRegistryVersion();
  }, [config.nodeDefinitions, bumpRegistryVersion]);

  // Load initial data
  useEffect(() => {
    if (initialData) {
      isImportingRef.current = true;
      importData(initialData);
      // Reset the flag on the next microtask so that the onChange effect
      // (which runs synchronously in the same render cycle) sees it as false
      // only after this import batch has settled.
      Promise.resolve().then(() => {
        isImportingRef.current = false;
      });
    }
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
    const data = exportData();
    await onSave(data);
    setDirty(false);
  }, [onSave, exportData, setDirty]);

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
  );
}

export default FlowDesigner;
