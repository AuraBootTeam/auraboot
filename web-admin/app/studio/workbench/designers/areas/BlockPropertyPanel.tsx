/**
 * Block Property Panel
 *
 * Right panel for editing block properties.
 * Shows different editors based on block type.
 * When a field is selected, shows field property editor instead.
 */

import React, { useState, useEffect } from 'react';
import type { DslBlock, DslFieldRef, DslFieldOverride } from '~/studio/domain/dsl/types';
import { parseFieldShorthand } from '~/studio/domain/dsl/types';
import { FieldsEditor } from './editors/FieldsEditor';
import { ColumnsEditor } from './editors/ColumnsEditor';
import { ActionsEditor } from './editors/ActionsEditor';
import { BlockSettingsEditor } from './editors/BlockSettingsEditor';
import { FieldPropertyEditor } from './editors/FieldPropertyEditor';
import { DataSourceEditor } from './editors/DataSourceEditor';
import { TabFilterEditor } from './editors/TabFilterEditor';
import { viewModelService } from '~/studio/services/viewmodel/ViewModelService';

/**
 * Selected field info structure
 */
interface SelectedFieldInfo {
  blockId: string;
  fieldIndex: number;
  fieldRef: DslFieldRef;
}

export interface BlockPropertyPanelProps {
  block: DslBlock | null;
  modelCode?: string;
  selectedFieldInfo?: SelectedFieldInfo | null;
  onChange: (updates: Partial<DslBlock>) => void;
  onFieldChange?: (blockId: string, fieldIndex: number, updates: Partial<DslFieldOverride>) => void;
  onFieldDeselect?: () => void;
  readonly?: boolean;
  /** CUSTOM API mode props */
  isCustomApiMode?: boolean;
  dataSource?: Record<string, unknown>;
  onDataSourceChange?: (ds: Record<string, unknown>) => void;
  onTestDetect?: () => void;
  testStatus?: { connected: boolean; recordCount: number | null; error: string | null };
}

/**
 * Block type info
 */
const BLOCK_INFO: Record<string, { name: string; icon: string }> = {
  'filter-form': { name: 'Filter Form', icon: '🔍' },
  'form-section': { name: 'Form Section', icon: '📝' },
  'detail-section': { name: 'Detail Section', icon: '📄' },
  'form-buttons': { name: 'Form Buttons', icon: '✅' },
  'toolbar-buttons': { name: 'Toolbar Buttons', icon: '🔘' },
  'selection-info': { name: 'Selection Info', icon: '☑️' },
  'data-table': { name: 'Data Table', icon: '📊' },
  'stat-card': { name: 'Stat Card', icon: '📈' },
  'chart-card': { name: 'Chart Card', icon: '📉' },
  text: { name: 'Text Content', icon: '📃' },
};

export const BlockPropertyPanel: React.FC<BlockPropertyPanelProps> = ({
  block,
  modelCode,
  selectedFieldInfo,
  onChange,
  onFieldChange,
  onFieldDeselect,
  readonly,
  isCustomApiMode,
  dataSource,
  onDataSourceChange,
  onTestDetect,
  testStatus,
}) => {
  // Resolve field dataType from model metadata
  const [fieldDataType, setFieldDataType] = useState<string>('string');
  useEffect(() => {
    if (!selectedFieldInfo || !modelCode) {
      setFieldDataType('string');
      return;
    }
    const fieldCode =
      typeof selectedFieldInfo.fieldRef === 'string'
        ? selectedFieldInfo.fieldRef.split('|')[0]
        : selectedFieldInfo.fieldRef.field;

    let cancelled = false;
    viewModelService
      .getResolvedFields(modelCode)
      .then((fields) => {
        if (cancelled) return;
        const match = fields.find((f) => f.code === fieldCode);
        setFieldDataType(match?.dataType || 'string');
      })
      .catch(() => {
        if (!cancelled) setFieldDataType('string');
      });
    return () => {
      cancelled = true;
    };
  }, [selectedFieldInfo, modelCode]);

  // If a field is selected, show field property editor
  if (selectedFieldInfo && block) {
    return (
      <FieldPropertyEditor
        fieldRef={selectedFieldInfo.fieldRef}
        blockType={block.blockType}
        dataType={fieldDataType}
        onChange={(updates) => {
          onFieldChange?.(selectedFieldInfo.blockId, selectedFieldInfo.fieldIndex, updates);
        }}
        onClose={() => onFieldDeselect?.()}
        readonly={readonly}
      />
    );
  }

  if (!block) {
    return <EmptyState />;
  }

  const info = BLOCK_INFO[block.blockType] || { name: block.blockType, icon: '📦' };

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="border-b border-gray-200 px-4 py-3">
        <div className="flex items-center gap-2">
          <span className="text-xl">{info.icon}</span>
          <div>
            <h3 className="text-sm font-medium text-gray-900">{info.name}</h3>
            <p className="font-mono text-xs text-gray-400">{block.id}</p>
          </div>
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-auto">
        {/* DataSource editor for CUSTOM API mode (page-level, above block settings) */}
        {isCustomApiMode && onDataSourceChange && (
          <EditorSection title="Data Source">
            <DataSourceEditor
              dataSource={(dataSource || {}) as any}
              onChange={onDataSourceChange as any}
              onTestDetect={onTestDetect}
              testStatus={testStatus}
              readonly={readonly}
            />
          </EditorSection>
        )}

        <BlockEditors block={block} modelCode={modelCode} onChange={onChange} readonly={readonly} />
      </div>
    </div>
  );
};

/**
 * Empty state when no block is selected
 */
const EmptyState: React.FC = () => {
  return (
    <div className="flex h-full items-center justify-center p-6" data-testid="properties-empty">
      <div className="text-center text-gray-400">
        <svg
          className="mx-auto mb-3 h-12 w-12 text-gray-300"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M15 15l-2 5L9 9l11 4-5 2zm0 0l5 5M7.188 2.239l.777 2.897M5.136 7.965l-2.898-.777M13.95 4.05l-2.122 2.122m-5.657 5.656l-2.12 2.122"
          />
        </svg>
        <p className="text-sm">Select a block</p>
        <p className="mt-1 text-xs">to edit its properties</p>
      </div>
    </div>
  );
};

/**
 * Render appropriate editors based on block type
 */
interface BlockEditorsProps {
  block: DslBlock;
  modelCode?: string;
  onChange: (updates: Partial<DslBlock>) => void;
  readonly?: boolean;
}

const BlockEditors: React.FC<BlockEditorsProps> = ({ block, modelCode, onChange, readonly }) => {
  const sections: React.ReactNode[] = [];

  // Basic settings for all blocks
  sections.push(
    <EditorSection key="settings" title="Settings">
      <BlockSettingsEditor block={block} onChange={onChange} readonly={readonly} />
    </EditorSection>,
  );

  // Fields editor for filter-form, form-section, detail-section
  if (
    block.blockType === 'filter-form' ||
    block.blockType === 'form-section' ||
    block.blockType === 'detail-section'
  ) {
    sections.push(
      <EditorSection key="fields" title="Fields" hint="Drag fields from left panel">
        <FieldsEditor
          fields={block.fields || []}
          modelCode={modelCode}
          blockId={block.id}
          onChange={(fields) => onChange({ fields })}
          readonly={readonly}
          showAdvanced={block.blockType === 'filter-form'}
        />
      </EditorSection>,
    );
  }

  // Columns editor for data-table
  if (block.blockType === 'data-table') {
    sections.push(
      <EditorSection key="columns" title="Columns" hint="Drag fields from left panel">
        <ColumnsEditor
          columns={block.columns || []}
          modelCode={modelCode}
          blockId={block.id}
          onChange={(columns) => onChange({ columns })}
          readonly={readonly}
        />
      </EditorSection>,
    );
  }

  // Tab filter editor for list-tabs blocks
  if ((block as any).blockType === 'list-tabs') {
    sections.push(
      <EditorSection key="tabs" title="Tabs">
        <TabFilterEditor
          tabs={(block as any).tabs || []}
          onChange={(tabs) => onChange({ tabs } as any)}
          readonly={readonly}
        />
      </EditorSection>,
    );
  }

  // Actions editor for buttons blocks
  if (
    block.blockType === 'toolbar-buttons' ||
    block.blockType === 'form-buttons' ||
    block.blockType === 'filter-form'
  ) {
    sections.push(
      <EditorSection key="actions" title="Actions">
        <ActionsEditor
          buttons={block.buttons || []}
          actions={block.actions || []}
          onChange={(buttons, actions) => onChange({ buttons, actions })}
          readonly={readonly}
          showQuickActions={block.blockType !== 'toolbar-buttons'}
        />
      </EditorSection>,
    );
  }

  return <div className="divide-y divide-gray-100">{sections}</div>;
};

/**
 * Editor section wrapper
 */
interface EditorSectionProps {
  title: string;
  hint?: string;
  children: React.ReactNode;
}

const EditorSection: React.FC<EditorSectionProps> = ({ title, hint, children }) => {
  return (
    <div className="p-4">
      <div className="mb-3 flex items-center justify-between">
        <h4 className="text-xs font-semibold tracking-wider text-gray-500 uppercase">{title}</h4>
        {hint && <span className="text-[10px] text-blue-500">{hint}</span>}
      </div>
      {children}
    </div>
  );
};

export default BlockPropertyPanel;
