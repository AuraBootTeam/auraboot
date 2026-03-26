import React, { useState, useEffect, useRef, useCallback } from 'react';
import type { PageSchema } from '~/studio/domain/schema/types';
import type { Version } from '~/studio/domain/metadata/types';
import {
  CollaborationProvider,
  useCollaborationStatus,
} from '~/studio/workbench/providers/CollaborationProvider';
import {
  CommandToolbar,
  useCommandShortcuts,
} from '~/studio/workbench/components/toolbar/CommandToolbar';
import { VersionPanel } from '~/studio/workbench/panels/version/VersionPanel';
import { AutoSave } from '~/studio/workbench/components/system/AutoSave';
import {
  ConflictResolver,
  type Conflict,
  type ConflictResolution,
} from '~/studio/workbench/components/system/ConflictResolver';
import {
  MultiSelectManager,
  BatchOperationToolbar,
} from '~/studio/workbench/components/system/MultiSelectManager';

export interface DesignerWorkflowProps {
  pageId: string;
  initialSchema: PageSchema;
  onSchemaChange: (schema: PageSchema) => void;
  children: React.ReactNode;
  enableAutoSave?: boolean;
  collaborationConfig?: {
    enabled: boolean;
    websocketUrl?: string;
  };
  initialConflicts?: Conflict[];
  onConflictsChange?: (conflicts: Conflict[]) => void;
}

interface DesignerWorkflowContentProps extends DesignerWorkflowProps {}

const DesignerWorkflowContent: React.FC<DesignerWorkflowContentProps> = ({
  pageId,
  initialSchema,
  onSchemaChange,
  children,
  enableAutoSave = true,
  initialConflicts = [],
  onConflictsChange,
}) => {
  const [schema, setSchema] = useState<PageSchema>(initialSchema);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [showVersionPanel, setShowVersionPanel] = useState(false);
  const [showPreview, setShowPreview] = useState(false);
  const [showConflictResolver, setShowConflictResolver] = useState(false);
  const [conflicts, setConflicts] = useState<Conflict[]>(initialConflicts);

  const canvasAreaRef = useRef<HTMLDivElement>(null);

  const collaborationStatus = useCollaborationStatus();

  useCommandShortcuts();

  useEffect(() => {
    setSchema(initialSchema);
  }, [initialSchema]);

  useEffect(() => {
    setConflicts(initialConflicts);
  }, [initialConflicts]);

  const handleSchemaUpdate = useCallback(
    (nextSchema: PageSchema) => {
      setSchema(nextSchema);
      onSchemaChange(nextSchema);
    },
    [onSchemaChange],
  );

  const handleVersionChange = useCallback(
    (version: Version) => {
      if (version.schema) {
        handleSchemaUpdate(version.schema);
      }
      setShowVersionPanel(false);
    },
    [handleSchemaUpdate],
  );

  const handleExport = useCallback(() => {
    const blob = new Blob([JSON.stringify(schema, null, 2)], {
      type: 'application/json',
    });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${schema.name || 'page'}-schema.json`;
    link.click();
    URL.revokeObjectURL(url);
  }, [schema]);

  const handleResolveConflict = useCallback(
    (resolution: ConflictResolution) => {
      setConflicts((prev) => {
        const next = prev.filter((conflict) => conflict.id !== resolution.conflictId);
        onConflictsChange?.(next);
        return next;
      });
      if (conflicts.length <= 1) {
        setShowConflictResolver(false);
      }
    },
    [conflicts.length, onConflictsChange],
  );

  const handleResolveAll = useCallback(
    (resolutions: ConflictResolution[]) => {
      const resolvedIds = new Set(resolutions.map((item) => item.conflictId));
      setConflicts((prev) => {
        const next = prev.filter((conflict) => !resolvedIds.has(conflict.id));
        onConflictsChange?.(next);
        return next;
      });
      setShowConflictResolver(false);
    },
    [onConflictsChange],
  );

  const workflowContent = (
    <div className="flex h-full flex-col bg-gray-50" data-testid="designer-workflow">
      <header className="flex flex-col gap-4 border-b border-gray-200 bg-white px-6 py-4 lg:flex-row lg:items-center lg:justify-between">
        <div>
          <h1 className="text-lg font-semibold text-gray-900">Studio 设计工作流</h1>
          <p className="text-sm text-gray-500">协作状态：{collaborationStatus.connectionStatus}</p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {enableAutoSave && <AutoSave pageId={pageId} schema={schema} className="mr-2" />}
          <button
            onClick={() => setShowPreview(true)}
            className="rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-700 hover:bg-gray-100"
          >
            预览
          </button>
          <button
            onClick={handleExport}
            className="rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-700 hover:bg-gray-100"
          >
            导出
          </button>
          <button
            onClick={() => setShowVersionPanel(true)}
            className="rounded-md bg-indigo-600 px-3 py-2 text-sm text-white hover:bg-indigo-700"
          >
            版本管理
          </button>
          <button
            onClick={() => setShowConflictResolver(true)}
            disabled={conflicts.length === 0}
            className={`rounded-md px-3 py-2 text-sm ${
              conflicts.length > 0
                ? 'bg-amber-500 text-white hover:bg-amber-600'
                : 'cursor-not-allowed bg-gray-200 text-gray-500'
            }`}
          >
            解决冲突
            {conflicts.length > 0 && <span className="ml-1">({conflicts.length})</span>}
          </button>
        </div>
      </header>

      <div className="border-b border-gray-200 bg-white px-6 py-2">
        <CommandToolbar />
      </div>

      <div className="relative flex-1 overflow-hidden">
        <MultiSelectManager
          containerRef={canvasAreaRef as React.RefObject<HTMLElement>}
          selectedIds={selectedIds}
          onSelectionChange={setSelectedIds}
        >
          <div ref={canvasAreaRef} className="h-full overflow-auto bg-gray-100">
            {children}
          </div>
        </MultiSelectManager>

        {selectedIds.length > 0 && (
          <BatchOperationToolbar
            selectedIds={selectedIds}
            onClearSelection={() => setSelectedIds([])}
          />
        )}
      </div>

      {showVersionPanel && (
        <VersionPanel
          pageId={pageId}
          onVersionChange={handleVersionChange}
          onClose={() => setShowVersionPanel(false)}
        />
      )}

      {showPreview && <PreviewModal schema={schema} onClose={() => setShowPreview(false)} />}

      {showConflictResolver && conflicts.length > 0 && (
        <ConflictResolver
          conflicts={conflicts}
          onResolve={handleResolveConflict}
          onResolveAll={handleResolveAll}
        />
      )}
    </div>
  );

  return workflowContent;
};

export const DesignerWorkflow: React.FC<DesignerWorkflowProps> = ({
  collaborationConfig,
  ...rest
}) => (
  <CollaborationProvider
    enabled={collaborationConfig?.enabled}
    websocketUrl={collaborationConfig?.websocketUrl}
  >
    <DesignerWorkflowContent {...rest} collaborationConfig={collaborationConfig} />
  </CollaborationProvider>
);

interface PreviewModalProps {
  schema: PageSchema;
  onClose: () => void;
}

const PreviewModal: React.FC<PreviewModalProps> = ({ schema, onClose }) => (
  <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
    <div className="flex h-full max-h-[90vh] w-full max-w-5xl flex-col overflow-hidden rounded-xl bg-white shadow-2xl">
      <div className="flex items-center justify-between border-b border-gray-200 px-4 py-3">
        <div>
          <p className="text-base font-semibold text-gray-900">页面预览</p>
          <p className="text-sm text-gray-500">{schema.meta?.title || schema.name}</p>
        </div>
        <button
          onClick={onClose}
          className="rounded-md p-2 text-gray-500 hover:bg-gray-100 hover:text-gray-700"
          aria-label="关闭预览"
        >
          ✕
        </button>
      </div>

      <div className="flex-1 overflow-auto bg-gray-50 p-6">
        <div className="mx-auto max-w-3xl rounded-lg bg-white p-6 shadow">
          <div className="text-center text-gray-500">
            <p className="mb-2 text-lg font-medium">预览占位符</p>
            <p className="text-sm">在此渲染基于 Schema 的实时页面预览</p>
          </div>
          <div className="mt-6">
            <h3 className="text-sm font-medium text-gray-700">Schema 数据</h3>
            <pre className="mt-2 max-h-64 overflow-auto rounded bg-gray-100 p-3 text-xs text-gray-700">
              {JSON.stringify(schema, null, 2)}
            </pre>
          </div>
        </div>
      </div>

      <div className="border-t border-gray-200 bg-gray-50 px-4 py-3 text-right">
        <button
          onClick={onClose}
          className="rounded-md border border-gray-300 px-4 py-2 text-sm text-gray-700 hover:bg-gray-100"
        >
          关闭
        </button>
      </div>
    </div>
  </div>
);

export default DesignerWorkflow;
