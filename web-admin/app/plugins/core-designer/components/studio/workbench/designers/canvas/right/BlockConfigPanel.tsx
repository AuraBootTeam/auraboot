/**
 * BlockConfigPanel — Right panel for the composite canvas editor.
 *
 * 3-tab layout:
 * - Properties: schema-driven block config via SchemaBlockConfigPanel
 * - Interaction: placeholder for V2 cross-block communication
 * - Page: PageSettingsPanel for page-level title/description
 *
 * When no block is selected, Properties tab shows an empty-state prompt.
 * The tab bar style matches the left panel pattern (simple CSS tabs).
 *
 * blockType routing:
 *   If block.blockType has an entry in BLOCK_CONFIG_SCHEMAS → SchemaBlockConfigPanel
 *   Otherwise → GenericBlockConfig (JSON fallback)
 *
 * @since 4.0.0
 */

import React, { useCallback, useMemo, useState } from 'react';
import type { PropertySchema } from '~/shared/designer/types';
import { PropertyFieldRenderer } from '~/shared/designer/PropertyFieldRenderer';
import type { FieldAdapter } from '~/components/field-adapter';
import type { CanvasBlock } from '~/plugins/core-designer/components/studio/domain/canvas/types';
import { SchemaBlockConfigPanel } from './SchemaBlockConfigPanel';
import { BUTTON_CONFIG_SCHEMA } from './block-schemas';
import { BlockRegistry, buildFieldSchema } from '~/plugins/core-designer/components/studio/registry';
import { PageSettingsPanel } from './PageSettingsPanel';

// ─── LayoutSection — universal col/colSpan/rowSpan inputs ─────────────────────

const LayoutSection: React.FC<{ block: CanvasBlock; onUpdate: (patch: Partial<CanvasBlock>) => void }> = ({ block, onUpdate }) => {
  const layout = block.layout ?? {};
  const col = layout.col ?? 0;
  const colSpan = layout.colSpan ?? 12;
  const rowSpan = layout.rowSpan ?? 1;

  const updateLayout = (key: string, value: number) => {
    onUpdate({ layout: { ...layout, [key]: value } });
  };

  return (
    <div style={{ marginBottom: 16, paddingBottom: 16, borderBottom: '1px solid #f3f4f6' }} data-testid="layout-section">
      <div style={{ fontSize: 10, color: '#9ca3af', fontWeight: 700, textTransform: 'uppercase', letterSpacing: 0.8, marginBottom: 10 }}>
        Layout
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 8 }}>
        <div>
          <label className="mb-1 block text-[10px] text-gray-500">Start Col</label>
          <input
            type="number"
            min={0}
            max={11}
            value={col}
            onChange={(e) => updateLayout('col', Math.max(0, Math.min(11, parseInt(e.target.value) || 0)))}
            className="w-full rounded border border-gray-200 px-2 py-1 text-xs"
            data-testid="layout-col"
          />
        </div>
        <div>
          <label className="mb-1 block text-[10px] text-gray-500">Col Span</label>
          <input
            type="number"
            min={1}
            max={12}
            value={colSpan}
            onChange={(e) => updateLayout('colSpan', Math.max(1, Math.min(12, parseInt(e.target.value) || 1)))}
            className="w-full rounded border border-gray-200 px-2 py-1 text-xs"
            data-testid="layout-colSpan"
          />
        </div>
        <div>
          <label className="mb-1 block text-[10px] text-gray-500">Row Span</label>
          <input
            type="number"
            min={1}
            max={10}
            value={rowSpan}
            onChange={(e) => updateLayout('rowSpan', Math.max(1, Math.min(10, parseInt(e.target.value) || 1)))}
            className="w-full rounded border border-gray-200 px-2 py-1 text-xs"
            data-testid="layout-rowSpan"
          />
        </div>
      </div>
    </div>
  );
};

// ─── Types ────────────────────────────────────────────────────────────────────

export interface BlockConfigPanelProps {
  selectedBlock: CanvasBlock | null;
  onBlockUpdate: (patch: Partial<CanvasBlock>) => void;

  // Page-level props for the Page tab
  pageTitle: string;
  pageKey?: string;
  pageDescription: string;
  onTitleChange: (title: string) => void;
  onDescriptionChange: (description: string) => void;

  // Field-level selection (for form-section fields)
  selectedFieldIndex?: number | null;
  onClearFieldSelection?: () => void;
}

type PanelTab = 'properties' | 'interaction' | 'page';

// ─── Generic fallback ─────────────────────────────────────────────────────────

const GenericBlockConfig: React.FC<{ block: CanvasBlock; onUpdate: (patch: Partial<CanvasBlock>) => void }> = ({ block }) => (
  <div data-testid="generic-block-config">
    <p
      style={{
        fontSize: 10,
        fontWeight: 600,
        letterSpacing: '0.08em',
        textTransform: 'uppercase',
        color: '#9ca3af',
        marginBottom: 8,
      }}
    >
      Block Config ({block.blockType})
    </p>
    <p style={{ fontSize: 11, color: '#6b7280', marginBottom: 12 }}>
      No dedicated config panel for this block type yet.
    </p>
    <pre
      style={{
        fontSize: 10,
        background: '#f9fafb',
        border: '1px solid #e5e7eb',
        borderRadius: 4,
        padding: 8,
        overflow: 'auto',
        color: '#374151',
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-all',
      }}
    >
      {JSON.stringify(block, null, 2)}
    </pre>
  </div>
);

// ─── FieldConfigPanel ─────────────────────────────────────────────────────────

interface FieldConfigPanelProps {
  block: CanvasBlock;
  fieldIndex: number;
  onBlockUpdate: (patch: Partial<CanvasBlock>) => void;
  onBack: () => void;
}

const FieldConfigPanel: React.FC<FieldConfigPanelProps> = ({
  block,
  fieldIndex,
  onBlockUpdate,
  onBack,
}) => {
  const fields = (block.config.fields as Record<string, unknown>[]) ?? [];
  const fieldConfig = fields[fieldIndex] ?? {};
  const fieldLabel = (fieldConfig as any).field || `Field ${fieldIndex}`;

  // Build the field schema dynamically from the registry based on the current component type.
  // This replaces the static FIELD_CONFIG_SCHEMA import.
  const fieldSchemas = useMemo(
    () => buildFieldSchema((fieldConfig as any).component || 'text'),
    [(fieldConfig as any).component],
  );

  // Group schemas by their `group` field
  const groups = useMemo(() => {
    const map = new Map<string, PropertySchema<string>[]>();
    for (const s of fieldSchemas) {
      const g = s.group ?? 'General';
      if (!map.has(g)) map.set(g, []);
      map.get(g)!.push(s);
    }
    return map;
  }, [fieldSchemas]);

  // Build adapter that reads/writes block.config.fields[fieldIndex][key]
  const createAdapter = useCallback(
    (schema: PropertySchema<string>): FieldAdapter<unknown> => ({
      value: (fieldConfig as Record<string, unknown>)[schema.key] ?? schema.defaultValue,
      setValue: (value: unknown) => {
        const newFields = [...fields];
        newFields[fieldIndex] = { ...newFields[fieldIndex], [schema.key]: value };
        onBlockUpdate({ config: { ...block.config, fields: newFields } });
      },
      required: schema.required,
    }),
    [fieldConfig, fields, fieldIndex, block.config, onBlockUpdate],
  );

  // Evaluate dependsOn visibility for each field schema
  const isFieldVisible = useCallback(
    (schema: PropertySchema<string>) => {
      if (!schema.dependsOn) return true;
      let depValue = (fieldConfig as Record<string, unknown>)[schema.dependsOn.field];
      // Fallback to controlling field's defaultValue for new fields with empty config
      if (depValue === undefined) {
        const controllingSchema = fieldSchemas.find(s => s.key === schema.dependsOn!.field);
        if (controllingSchema?.defaultValue !== undefined) {
          depValue = controllingSchema.defaultValue;
        }
      }
      if (schema.dependsOn.value !== undefined) {
        if (Array.isArray(schema.dependsOn.value)) {
          return schema.dependsOn.value.includes(depValue);
        }
        return depValue === schema.dependsOn.value;
      }
      return Boolean(depValue);
    },
    [fieldConfig, fieldSchemas],
  );

  return (
    <div data-testid="field-config-panel">
      {/* Back button */}
      <button
        type="button"
        onClick={onBack}
        className="mb-3 flex items-center gap-1 text-[11px] text-purple-600 hover:text-purple-800 transition-colors"
        data-testid="field-config-back"
      >
        <span>&larr;</span>
        <span>Back to block</span>
      </button>

      {/* Field header */}
      <div className="mb-3 rounded bg-purple-50 px-2 py-1.5">
        <span className="text-[10px] font-semibold uppercase tracking-wider text-purple-500">
          Field Config
        </span>
        <div className="mt-0.5 text-xs font-medium text-purple-700 truncate">
          {fieldLabel}
        </div>
      </div>

      {/* Schema-driven fields grouped with dependsOn filtering */}
      {Array.from(groups.entries()).map(([groupName, groupFields]) => {
        const visibleFields = groupFields.filter(isFieldVisible);
        if (visibleFields.length === 0) return null;
        return (
          <div
            key={groupName}
            style={{ marginBottom: 16, paddingBottom: 16, borderBottom: '1px solid #f3f4f6' }}
          >
            <div
              style={{
                fontSize: 10,
                color: '#9ca3af',
                fontWeight: 700,
                textTransform: 'uppercase' as const,
                letterSpacing: 0.8,
                marginBottom: 10,
              }}
            >
              {groupName}
            </div>
            {visibleFields.map((schema) => (
              <div key={schema.key} style={{ marginBottom: 8 }}>
                <PropertyFieldRenderer schema={schema} adapter={createAdapter(schema)} />
              </div>
            ))}
          </div>
        );
      })}
    </div>
  );
};

// ─── ButtonConfigPanel ───────────────────────────────────────────────────────

interface ButtonConfigPanelProps {
  block: CanvasBlock;
  buttonIndex: number;
  onBlockUpdate: (patch: Partial<CanvasBlock>) => void;
  onBack: () => void;
}

const ButtonConfigPanel: React.FC<ButtonConfigPanelProps> = ({
  block,
  buttonIndex,
  onBlockUpdate,
  onBack,
}) => {
  const buttons = (block.config.buttons as Record<string, unknown>[]) ?? [];
  const buttonConfig = buttons[buttonIndex] ?? {};
  const buttonLabel = (buttonConfig as any).code || (buttonConfig as any).label || `Button ${buttonIndex}`;

  // Group schemas by their `group` field
  const groups = useMemo(() => {
    const map = new Map<string, PropertySchema<string>[]>();
    for (const s of BUTTON_CONFIG_SCHEMA) {
      const g = s.group ?? 'General';
      if (!map.has(g)) map.set(g, []);
      map.get(g)!.push(s);
    }
    return map;
  }, []);

  // Build adapter that reads/writes block.config.buttons[buttonIndex][key]
  // Supports nested keys like 'action.type' → reads config.action.type
  const createAdapter = useCallback(
    (schema: PropertySchema<string>): FieldAdapter<unknown> => {
      const keys = schema.key.split('.');
      const readValue = (obj: Record<string, unknown>): unknown => {
        let current: unknown = obj;
        for (const k of keys) {
          if (current == null || typeof current !== 'object') return undefined;
          current = (current as Record<string, unknown>)[k];
        }
        return current;
      };

      return {
        value: readValue(buttonConfig as Record<string, unknown>) ?? schema.defaultValue,
        setValue: (value: unknown) => {
          const newButtons = [...buttons];
          const updated = { ...newButtons[buttonIndex] } as Record<string, unknown>;
          if (keys.length === 1) {
            updated[keys[0]] = value;
          } else {
            // nested: e.g. action.type → updated.action = { ...updated.action, type: value }
            const parent = keys.slice(0, -1);
            const leaf = keys[keys.length - 1];
            let target = updated;
            for (const k of parent) {
              if (target[k] == null || typeof target[k] !== 'object') {
                target[k] = {};
              } else {
                target[k] = { ...(target[k] as Record<string, unknown>) };
              }
              target = target[k] as Record<string, unknown>;
            }
            target[leaf] = value;
          }
          newButtons[buttonIndex] = updated;
          onBlockUpdate({ config: { ...block.config, buttons: newButtons } });
        },
        required: schema.required,
      };
    },
    [buttonConfig, buttons, buttonIndex, block.config, onBlockUpdate],
  );

  return (
    <div data-testid="button-config-panel">
      {/* Back button */}
      <button
        type="button"
        onClick={onBack}
        className="mb-3 flex items-center gap-1 text-[11px] text-purple-600 hover:text-purple-800 transition-colors"
        data-testid="button-config-back"
      >
        <span>&larr;</span>
        <span>Back to toolbar</span>
      </button>

      {/* Button header */}
      <div className="mb-3 rounded bg-purple-50 px-2 py-1.5">
        <span className="text-[10px] font-semibold uppercase tracking-wider text-purple-500">
          Button Config
        </span>
        <div className="mt-0.5 text-xs font-medium text-purple-700 truncate">
          {buttonLabel}
        </div>
      </div>

      {/* Schema-driven fields grouped */}
      {Array.from(groups.entries()).map(([groupName, groupFields]) => (
        <div
          key={groupName}
          style={{ marginBottom: 16, paddingBottom: 16, borderBottom: '1px solid #f3f4f6' }}
        >
          <div
            style={{
              fontSize: 10,
              color: '#9ca3af',
              fontWeight: 700,
              textTransform: 'uppercase' as const,
              letterSpacing: 0.8,
              marginBottom: 10,
            }}
          >
            {groupName}
          </div>
          {groupFields.map((schema) => (
            <div key={schema.key} style={{ marginBottom: 8 }}>
              <PropertyFieldRenderer schema={schema} adapter={createAdapter(schema)} />
            </div>
          ))}
        </div>
      ))}
    </div>
  );
};

// ─── BlockConfigPanel ─────────────────────────────────────────────────────────

export const BlockConfigPanel: React.FC<BlockConfigPanelProps> = ({
  selectedBlock,
  onBlockUpdate,
  pageTitle,
  pageKey,
  pageDescription,
  onTitleChange,
  onDescriptionChange,
  selectedFieldIndex,
  onClearFieldSelection,
}) => {
  const [activeTab, setActiveTab] = useState<PanelTab>('properties');

  const tabs: { id: PanelTab; label: string }[] = [
    { id: 'properties', label: 'Properties' },
    { id: 'interaction', label: 'Interaction' },
    { id: 'page', label: 'Page' },
  ];

  // Determine whether the selected block has a schema-driven config panel
  const hasSchema = selectedBlock
    ? BlockRegistry.getSchema(selectedBlock.blockType).length > 0
    : false;

  return (
    <div
      className="flex flex-col h-full"
      data-testid="block-config-panel"
    >
      {/* Tab bar */}
      <div
        className="flex border-b border-gray-200 flex-shrink-0"
        data-testid="block-config-tabs"
      >
        {tabs.map((tab) => (
          <button
            key={tab.id}
            type="button"
            onClick={() => setActiveTab(tab.id)}
            data-testid={`block-config-tab-${tab.id}`}
            className={`flex-1 px-1 py-2 text-[11px] font-medium transition-colors ${
              activeTab === tab.id
                ? 'border-b-2 border-purple-500 bg-purple-50 text-purple-700'
                : 'text-gray-500 hover:bg-gray-50 hover:text-gray-700'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Tab content */}
      <div className="flex-1 overflow-y-auto p-3" data-testid="block-config-content">
        {/* Properties tab */}
        {activeTab === 'properties' && (
          <>
            {selectedBlock && selectedBlock.blockType === 'form-section' && selectedFieldIndex != null ? (
              <FieldConfigPanel
                block={selectedBlock}
                fieldIndex={selectedFieldIndex}
                onBlockUpdate={onBlockUpdate}
                onBack={() => onClearFieldSelection?.()}
              />
            ) : selectedBlock && (selectedBlock.blockType === 'toolbar' || selectedBlock.blockType === 'form-buttons') && selectedFieldIndex != null ? (
              <ButtonConfigPanel
                block={selectedBlock}
                buttonIndex={selectedFieldIndex}
                onBlockUpdate={onBlockUpdate}
                onBack={() => onClearFieldSelection?.()}
              />
            ) : selectedBlock && hasSchema ? (
              <>
                <LayoutSection block={selectedBlock} onUpdate={onBlockUpdate} />
                <SchemaBlockConfigPanel
                  block={selectedBlock}
                  onUpdate={onBlockUpdate}
                />
              </>
            ) : selectedBlock ? (
              <>
                <LayoutSection block={selectedBlock} onUpdate={onBlockUpdate} />
                <GenericBlockConfig block={selectedBlock} onUpdate={onBlockUpdate} />
              </>
            ) : (
              <div
                className="flex h-full items-center justify-center"
                data-testid="block-config-empty"
              >
                <div style={{ textAlign: 'center' }}>
                  <div
                    style={{
                      width: 36,
                      height: 36,
                      borderRadius: '50%',
                      background: '#f3f4f6',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      margin: '0 auto 8px',
                      fontSize: 18,
                    }}
                  >
                    ☰
                  </div>
                  <p style={{ fontSize: 12, color: '#9ca3af', margin: 0 }}>
                    Select a block to configure
                  </p>
                  <p style={{ fontSize: 10, color: '#d1d5db', marginTop: 4 }}>
                    Click any block on the canvas
                  </p>
                </div>
              </div>
            )}
          </>
        )}

        {/* Interaction tab */}
        {activeTab === 'interaction' && (
          <div
            style={{ textAlign: 'center', padding: '24px 0' }}
            data-testid="block-config-interaction"
          >
            <div
              style={{
                width: 40,
                height: 40,
                borderRadius: 8,
                background: '#ede9fe',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                margin: '0 auto 10px',
                fontSize: 20,
              }}
            >
              ⚡
            </div>
            <p style={{ fontSize: 12, color: '#6b7280', margin: 0, fontWeight: 600 }}>
              V2 — Cross-block communication
            </p>
            <p
              style={{
                fontSize: 10,
                color: '#9ca3af',
                marginTop: 6,
                lineHeight: 1.6,
                maxWidth: 200,
                margin: '6px auto 0',
              }}
            >
              Connect blocks so that selecting a row in one table filters another block
              automatically.
            </p>
            <div
              style={{
                marginTop: 12,
                display: 'inline-block',
                padding: '3px 10px',
                background: '#ede9fe',
                color: '#7c3aed',
                borderRadius: 100,
                fontSize: 10,
                fontWeight: 600,
              }}
            >
              Coming in v2
            </div>
          </div>
        )}

        {/* Page tab */}
        {activeTab === 'page' && (
          <PageSettingsPanel
            title={pageTitle}
            pageKey={pageKey}
            description={pageDescription}
            onTitleChange={onTitleChange}
            onDescriptionChange={onDescriptionChange}
          />
        )}
      </div>
    </div>
  );
};

export default BlockConfigPanel;
