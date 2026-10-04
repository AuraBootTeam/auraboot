/**
 * Block Settings Editor
 *
 * Editor for block-specific settings based on block type.
 * Provides different property editors for each block type.
 */

import React, { useEffect, useState } from 'react';
import type { DslBlock } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { LocalizedTextInput, type LocalizedTextValue } from '~/shared/designer';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/utils/i18n';
import BLOCK_SETTINGS_TEXT from './BlockSettings.i18n.json';

function useBlockSettingsText() {
  const { locale } = useI18n();
  return (key: keyof typeof BLOCK_SETTINGS_TEXT, count?: number) =>
    getLocalizedText(BLOCK_SETTINGS_TEXT[key], locale).replace('{count}', String(count ?? ''));
}

export interface BlockSettingsEditorProps {
  block: DslBlock;
  onChange: (updates: Partial<DslBlock>) => void;
  readonly?: boolean;
}

/**
 * Property group type
 */
type PropertyGroup = 'basic' | 'layout' | 'data' | 'appearance' | 'behavior';

/**
 * Group configuration
 */
const GROUP_CONFIG: Record<PropertyGroup, { label: Record<'zh-CN' | 'en-US', string>; icon: string }> = {
  basic: { label: { 'zh-CN': '基本', 'en-US': 'Basic' }, icon: '📝' },
  layout: { label: { 'zh-CN': '布局', 'en-US': 'Layout' }, icon: '📐' },
  data: { label: { 'zh-CN': '数据', 'en-US': 'Data' }, icon: '📊' },
  appearance: { label: { 'zh-CN': '外观', 'en-US': 'Appearance' }, icon: '🎨' },
  behavior: { label: { 'zh-CN': '行为', 'en-US': 'Behavior' }, icon: '⚡' },
};

export const BlockSettingsEditor: React.FC<BlockSettingsEditorProps> = ({
  block,
  onChange,
  readonly,
}) => {
  const [activeGroup, setActiveGroup] = useState<PropertyGroup>('basic');
  const { locale } = useI18n();
  const localeKey = locale === 'zh-CN' ? 'zh-CN' : 'en-US';

  // Get available groups for this block type
  const availableGroups = getAvailableGroups(block.blockType);

  return (
    <div className="space-y-3">
      {/* Group tabs */}
      {availableGroups.length > 1 && (
        <div className="flex gap-1 rounded-lg bg-gray-100 p-1" data-testid="property-group-tabs">
          {availableGroups.map((group) => (
            <button
              key={group}
              onClick={() => setActiveGroup(group)}
              className={`flex-1 rounded-md px-2 py-1.5 text-xs font-medium transition-colors ${
                activeGroup === group
                  ? 'bg-white text-blue-600 shadow-sm'
                  : 'text-gray-600 hover:text-gray-800'
              }`}
              data-testid={`property-group-${group}`}
            >
              <span className="mr-1">{GROUP_CONFIG[group].icon}</span>
              {GROUP_CONFIG[group].label[localeKey]}
            </button>
          ))}
        </div>
      )}

      {/* Property editors based on active group */}
      <div className="space-y-4">
        {activeGroup === 'basic' && (
          <BasicProperties block={block} onChange={onChange} readonly={readonly} />
        )}
        {activeGroup === 'layout' && (
          <LayoutProperties block={block} onChange={onChange} readonly={readonly} />
        )}
        {activeGroup === 'data' && (
          <DataProperties block={block} onChange={onChange} readonly={readonly} />
        )}
        {activeGroup === 'appearance' && (
          <AppearanceProperties block={block} onChange={onChange} readonly={readonly} />
        )}
        {activeGroup === 'behavior' && (
          <BehaviorProperties block={block} onChange={onChange} readonly={readonly} />
        )}
      </div>
    </div>
  );
};

/**
 * Get available property groups for a block type
 */
function getAvailableGroups(blockType: string): PropertyGroup[] {
  switch (blockType) {
    case 'filters':
      return ['basic', 'layout', 'behavior'];
    case 'form-section':
    case 'detail-section':
      return ['basic', 'layout', 'behavior'];
    case 'table':
      return ['basic', 'layout', 'data', 'appearance', 'behavior'];
    case 'stat-card':
      return ['basic', 'data', 'appearance'];
    case 'chart-card':
      return ['basic', 'data', 'appearance'];
    case 'custom':
      return ['basic', 'data', 'behavior'];
    case 'text':
      return ['basic', 'appearance'];
    case 'toolbar':
    case 'form-buttons':
      return ['basic', 'layout'];
    default:
      return ['basic'];
  }
}

/**
 * Props type helper
 */
interface PropertyEditorProps {
  block: DslBlock;
  onChange: (updates: Partial<DslBlock>) => void;
  readonly?: boolean;
}

interface DataSourceReferenceFieldProps extends PropertyEditorProps {
  fieldTestId: string;
  inputTestId: string;
  placeholder: string;
}

const DataSourceReferenceField: React.FC<DataSourceReferenceFieldProps> = ({
  block,
  onChange,
  readonly,
  fieldTestId,
  inputTestId,
  placeholder,
}) => {
  const text = useBlockSettingsText();
  const value = typeof block.dataSource === 'string' ? block.dataSource : '';

  return (
    <PropertyField label={text('dataSource')} testId={fieldTestId}>
      <input
        type="text"
        value={value}
        onChange={(e) => onChange({ dataSource: e.target.value || undefined })}
        disabled={readonly}
        className="property-input font-mono text-xs"
        placeholder={placeholder}
        data-testid={inputTestId}
      />
    </PropertyField>
  );
};

/**
 * Basic properties (title, id, visibility)
 */
const BasicProperties: React.FC<PropertyEditorProps> = ({ block, onChange, readonly }) => {
  const text = useBlockSettingsText();
  const showTitle =
    block.blockType === 'form-section' ||
    block.blockType === 'detail-section' ||
    block.blockType === 'stat-card' ||
    block.blockType === 'chart-card' ||
    block.blockType === 'custom';

  return (
    <div className="space-y-4">
      {/* Title — accepts LocalizedText (zh-CN + en-US) */}
      {showTitle && (
        <PropertyField label={text('title')} testId="block-title">
          <LocalizedTextInput
            value={block.title as LocalizedTextValue}
            onChange={(next) => onChange({ title: (next ?? undefined) as DslBlock['title'] })}
            disabled={readonly}
            placeholder={text('enterTitle')}
            testId="block-title-input"
          />
        </PropertyField>
      )}

      {block.blockType === 'custom' && (
        <PropertyField label={text('component')} hint={text('componentHint')} testId="custom-component">
          <input
            type="text"
            value={block.component || ''}
            onChange={(e) => onChange({ component: e.target.value || undefined })}
            disabled={readonly}
            className="property-input font-mono text-xs"
            placeholder="decision-field-impact"
            data-testid="custom-component-input"
          />
        </PropertyField>
      )}

      {/* Text content */}
      {block.blockType === 'text' && (
        <PropertyField label={text('content')} testId="text-content">
          <textarea
            value={(block.props as any)?.content || ''}
            onChange={(e) => onChange({ props: { ...block.props, content: e.target.value } })}
            disabled={readonly}
            className="property-input min-h-[80px] resize-y"
            placeholder={text('enterContent')}
            data-testid="text-content-input"
          />
        </PropertyField>
      )}

      {/* Visibility condition */}
      <PropertyField
        label={text('visibility')}
        hint={text('expressionHint')}
        testId="block-visible"
      >
        <input
          type="text"
          value={block.visible || ''}
          onChange={(e) => onChange({ visible: e.target.value || undefined })}
          disabled={readonly}
          className="property-input font-mono text-xs"
          placeholder="{{ true }}"
          data-testid="block-visible-input"
        />
      </PropertyField>
    </div>
  );
};

/**
 * Layout properties (span, columns, gap)
 */
const LayoutProperties: React.FC<PropertyEditorProps> = ({ block, onChange, readonly }) => {
  const text = useBlockSettingsText();
  const props = (block.props || {}) as Record<string, any>;

  return (
    <div className="space-y-4">
      {/* Span */}
      <PropertyField label={text('gridWidth')} testId="block-span">
        <select
          value={block.span || ''}
          onChange={(e) => onChange({ span: e.target.value ? Number(e.target.value) : undefined })}
          disabled={readonly}
          className="property-input"
          data-testid="block-span-select"
        >
          <option value="">{text('auto')}</option>
          {[1, 2, 3, 4, 6, 8, 12].map((n) => (
            <option key={n} value={n}>
              {text('columns', n)}
            </option>
          ))}
        </select>
      </PropertyField>

      {/* Columns (for form sections) */}
      {(block.blockType === 'form-section' ||
        block.blockType === 'detail-section' ||
        block.blockType === 'filters') && (
        <PropertyField label={text('formColumns')} testId="block-columns">
          <select
            value={props.columns || 2}
            onChange={(e) => onChange({ props: { ...props, columns: Number(e.target.value) } })}
            disabled={readonly}
            className="property-input"
            data-testid="block-columns-select"
          >
            <option value={1}>{text('columns', 1)}</option>
            <option value={2}>{text('columns', 2)}</option>
            <option value={3}>{text('columns', 3)}</option>
            <option value={4}>{text('columns', 4)}</option>
          </select>
        </PropertyField>
      )}

      {/* Gutter */}
      {(block.blockType === 'form-section' || block.blockType === 'detail-section') && (
        <PropertyField label={text('gutter')} testId="block-gutter">
          <select
            value={props.gutter || 16}
            onChange={(e) => onChange({ props: { ...props, gutter: Number(e.target.value) } })}
            disabled={readonly}
            className="property-input"
            data-testid="block-gutter-select"
          >
            <option value={8}>{text('compact8')}</option>
            <option value={16}>{text('standard16')}</option>
            <option value={24}>{text('relaxed24')}</option>
            <option value={32}>{text('wide32')}</option>
          </select>
        </PropertyField>
      )}

      {/* Button layout */}
      {(block.blockType === 'toolbar' || block.blockType === 'form-buttons') && (
        <PropertyField label={text('buttonAlign')} testId="button-align">
          <select
            value={props.align || 'left'}
            onChange={(e) => onChange({ props: { ...props, align: e.target.value } })}
            disabled={readonly}
            className="property-input"
            data-testid="button-align-select"
          >
            <option value="left">{text('left')}</option>
            <option value="center">{text('center')}</option>
            <option value="right">{text('right')}</option>
          </select>
        </PropertyField>
      )}
    </div>
  );
};

interface CustomPropsJsonEditorProps extends PropertyEditorProps {
  props: Record<string, any>;
}

const formatJson = (value: Record<string, any>) => JSON.stringify(value || {}, null, 2);

const CustomPropsJsonEditor: React.FC<CustomPropsJsonEditorProps> = ({
  block,
  onChange,
  readonly,
  props,
}) => {
  const text = useBlockSettingsText();
  const [draft, setDraft] = useState(() => formatJson(props));
  const [error, setError] = useState<'invalidJsonObject' | 'invalidJson' | null>(null);

  useEffect(() => {
    setDraft(formatJson(props));
    setError(null);
  }, [block.id, props]);

  return (
    <PropertyField label={text('propsJson')} hint={text('propsHint')} testId="custom-props-json">
      <textarea
        value={draft}
        onChange={(event) => {
          const next = event.target.value;
          setDraft(next);
          try {
            const parsed = next.trim() ? JSON.parse(next) : {};
            if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
              setError('invalidJsonObject');
              return;
            }
            setError(null);
            onChange({ props: parsed });
          } catch {
            setError('invalidJson');
          }
        }}
        disabled={readonly}
        className="property-input min-h-[120px] resize-y font-mono text-xs"
        placeholder={'{\n  "initialCurrentDataType": "string"\n}'}
        data-testid="custom-props-json-input"
      />
      {error && (
        <div className="mt-1 text-xs text-red-600" data-testid="custom-props-json-error">
          {text(error)}
        </div>
      )}
    </PropertyField>
  );
};

/**
 * Data properties (data source, bindings)
 */
const DataProperties: React.FC<PropertyEditorProps> = ({ block, onChange, readonly }) => {
  const text = useBlockSettingsText();
  const props = (block.props || {}) as Record<string, any>;
  const blockRefreshInterval =
    block.refreshInterval ??
    (typeof props.refreshInterval === 'number' ? props.refreshInterval : undefined);

  return (
    <div className="space-y-4">
      {/* Data source (for table) */}
      {block.blockType === 'table' && (
        <>
          <DataSourceReferenceField
            block={block}
            onChange={onChange}
            readonly={readonly}
            fieldTestId="data-source"
            inputTestId="data-source-input"
            placeholder="tableData"
          />

          <PropertyField label={text('selectionBind')} hint={text('selectionHint')} testId="selection-bind">
            <input
              type="text"
              value={(block.selection as any)?.bind || ''}
              onChange={(e) =>
                onChange({
                  selection: e.target.value ? { bind: e.target.value } : undefined,
                })
              }
              disabled={readonly}
              className="property-input font-mono text-xs"
              placeholder="selectedIds"
              data-testid="selection-bind-input"
            />
          </PropertyField>

          <PropertyField label={text('rowKey')} hint={text('rowKeyHint')} testId="row-key">
            <input
              type="text"
              value={props.rowKey || 'id'}
              onChange={(e) => onChange({ props: { ...props, rowKey: e.target.value } })}
              disabled={readonly}
              className="property-input font-mono text-xs"
              placeholder="id"
              data-testid="row-key-input"
            />
          </PropertyField>
        </>
      )}

      {/* Stat card data */}
      {block.blockType === 'stat-card' && (
        <>
          <DataSourceReferenceField
            block={block}
            onChange={onChange}
            readonly={readonly}
            fieldTestId="stat-data-source"
            inputTestId="stat-data-source-input"
            placeholder="ds_stats"
          />

          <PropertyField label={text('valueField')} testId="stat-value-field">
            <input
              type="text"
              value={props.valueField || ''}
              onChange={(e) => onChange({ props: { ...props, valueField: e.target.value } })}
              disabled={readonly}
              className="property-input font-mono text-xs"
              placeholder="count"
              data-testid="stat-value-field-input"
            />
          </PropertyField>

          <PropertyField label={text('changeField')} testId="stat-change-field">
            <input
              type="text"
              value={props.changeField || ''}
              onChange={(e) => onChange({ props: { ...props, changeField: e.target.value } })}
              disabled={readonly}
              className="property-input font-mono text-xs"
              placeholder="changeRate"
              data-testid="stat-change-field-input"
            />
          </PropertyField>

          <PropertyField label={text('refreshInterval')} testId="stat-refresh-interval">
            <input
              type="number"
              value={blockRefreshInterval ?? ''}
              onChange={(e) => {
                const next = Number(e.target.value);
                onChange({
                  refreshInterval: Number.isFinite(next) && next > 0 ? next : undefined,
                });
              }}
              disabled={readonly}
              className="property-input"
              min={0}
              step={500}
              placeholder="0"
              data-testid="stat-refresh-interval-input"
            />
          </PropertyField>
        </>
      )}

      {/* Chart data */}
      {block.blockType === 'chart-card' && (
        <>
          <DataSourceReferenceField
            block={block}
            onChange={onChange}
            readonly={readonly}
            fieldTestId="chart-data-source"
            inputTestId="chart-data-source-input"
            placeholder="ds_chart"
          />

          <PropertyField label={text('chartType')} testId="chart-type">
            <select
              value={props.chartType || 'bar'}
              onChange={(e) => onChange({ props: { ...props, chartType: e.target.value } })}
              disabled={readonly}
              className="property-input"
              data-testid="chart-type-select"
            >
              <option value="bar">{text('bar')}</option>
              <option value="line">{text('line')}</option>
              <option value="pie">{text('pie')}</option>
              <option value="area">{text('area')}</option>
            </select>
          </PropertyField>

          <PropertyField label={text('xField')} testId="chart-x-field">
            <input
              type="text"
              value={props.xField || ''}
              onChange={(e) => onChange({ props: { ...props, xField: e.target.value } })}
              disabled={readonly}
              className="property-input font-mono text-xs"
              placeholder="category"
              data-testid="chart-x-field-input"
            />
          </PropertyField>

          <PropertyField label={text('yField')} testId="chart-y-field">
            <input
              type="text"
              value={props.yField || ''}
              onChange={(e) => onChange({ props: { ...props, yField: e.target.value } })}
              disabled={readonly}
              className="property-input font-mono text-xs"
              placeholder="value"
              data-testid="chart-y-field-input"
            />
          </PropertyField>

          <PropertyField label={text('refreshInterval')} testId="chart-refresh-interval">
            <input
              type="number"
              value={blockRefreshInterval ?? ''}
              onChange={(e) => {
                const next = Number(e.target.value);
                onChange({
                  refreshInterval: Number.isFinite(next) && next > 0 ? next : undefined,
                });
              }}
              disabled={readonly}
              className="property-input"
              min={0}
              step={500}
              placeholder="0"
              data-testid="chart-refresh-interval-input"
            />
          </PropertyField>
        </>
      )}

      {block.blockType === 'custom' && (
        <>
          <PropertyField label={text('customValueField')} hint={text('customValueHint')} testId="custom-value-field">
            <input
              type="text"
              value={props.valueField || ''}
              onChange={(e) =>
                onChange({
                  props: {
                    ...props,
                    valueField: e.target.value || undefined,
                  },
                })
              }
              disabled={readonly}
              className="property-input font-mono text-xs"
              placeholder="pid"
              data-testid="custom-value-field-input"
            />
          </PropertyField>

          <CustomPropsJsonEditor
            block={block}
            onChange={onChange}
            readonly={readonly}
            props={props}
          />
        </>
      )}
    </div>
  );
};

/**
 * Appearance properties (style, theme)
 */
const AppearanceProperties: React.FC<PropertyEditorProps> = ({ block, onChange, readonly }) => {
  const text = useBlockSettingsText();
  const props = (block.props || {}) as Record<string, any>;

  return (
    <div className="space-y-4">
      {/* Data table appearance */}
      {block.blockType === 'table' && (
        <>
          <PropertySwitch
            label={text('bordered')}
            checked={props.bordered ?? true}
            onChange={(checked) => onChange({ props: { ...props, bordered: checked } })}
            disabled={readonly}
            testId="table-bordered"
          />

          <PropertySwitch
            label={text('striped')}
            checked={props.striped ?? false}
            onChange={(checked) => onChange({ props: { ...props, striped: checked } })}
            disabled={readonly}
            testId="table-striped"
          />

          <PropertySwitch
            label={text('showIndex')}
            checked={props.showIndex ?? false}
            onChange={(checked) => onChange({ props: { ...props, showIndex: checked } })}
            disabled={readonly}
            testId="table-show-index"
          />

          <PropertyField label={text('tableSize')} testId="table-size">
            <select
              value={props.size || 'middle'}
              onChange={(e) => onChange({ props: { ...props, size: e.target.value } })}
              disabled={readonly}
              className="property-input"
              data-testid="table-size-select"
            >
              <option value="small">{text('compact')}</option>
              <option value="middle">{text('standard')}</option>
              <option value="large">{text('relaxed')}</option>
            </select>
          </PropertyField>
        </>
      )}

      {/* Stat card appearance */}
      {block.blockType === 'stat-card' && (
        <>
          <PropertyField label={text('prefix')} testId="stat-prefix">
            <input
              type="text"
              value={props.prefix || ''}
              onChange={(e) => onChange({ props: { ...props, prefix: e.target.value } })}
              disabled={readonly}
              className="property-input"
              placeholder="¥"
              data-testid="stat-prefix-input"
            />
          </PropertyField>

          <PropertyField label={text('suffix')} testId="stat-suffix">
            <input
              type="text"
              value={props.suffix || ''}
              onChange={(e) => onChange({ props: { ...props, suffix: e.target.value } })}
              disabled={readonly}
              className="property-input"
              placeholder={text('currencySuffix')}
              data-testid="stat-suffix-input"
            />
          </PropertyField>

          <PropertyField label={text('themeColor')} testId="stat-color">
            <select
              value={props.color || 'blue'}
              onChange={(e) => onChange({ props: { ...props, color: e.target.value } })}
              disabled={readonly}
              className="property-input"
              data-testid="stat-color-select"
            >
              <option value="blue">{text('blue')}</option>
              <option value="green">{text('green')}</option>
              <option value="orange">{text('orange')}</option>
              <option value="red">{text('red')}</option>
              <option value="purple">{text('purple')}</option>
            </select>
          </PropertyField>
        </>
      )}

      {/* Chart appearance */}
      {block.blockType === 'chart-card' && (
        <>
          <PropertySwitch
            label={text('smooth')}
            checked={props.smooth ?? true}
            onChange={(checked) => onChange({ props: { ...props, smooth: checked } })}
            disabled={readonly}
            testId="chart-smooth"
          />

          <PropertySwitch
            label={text('legend')}
            checked={props.showLegend ?? true}
            onChange={(checked) => onChange({ props: { ...props, showLegend: checked } })}
            disabled={readonly}
            testId="chart-legend"
          />

          <PropertyField label={text('chartHeight')} testId="chart-height">
            <input
              type="number"
              value={props.height || 200}
              onChange={(e) => onChange({ props: { ...props, height: Number(e.target.value) } })}
              disabled={readonly}
              className="property-input"
              min={100}
              max={600}
              step={20}
              data-testid="chart-height-input"
            />
          </PropertyField>
        </>
      )}

      {/* Text appearance */}
      {block.blockType === 'text' && (
        <>
          <PropertyField label={text('textSize')} testId="text-size">
            <select
              value={props.size || 'base'}
              onChange={(e) => onChange({ props: { ...props, size: e.target.value } })}
              disabled={readonly}
              className="property-input"
              data-testid="text-size-select"
            >
              <option value="xs">{text('extraSmall')}</option>
              <option value="sm">{text('small')}</option>
              <option value="base">{text('standard')}</option>
              <option value="lg">{text('large')}</option>
              <option value="xl">{text('extraLarge')}</option>
            </select>
          </PropertyField>

          <PropertyField label={text('textColor')} testId="text-color">
            <select
              value={props.color || 'default'}
              onChange={(e) => onChange({ props: { ...props, color: e.target.value } })}
              disabled={readonly}
              className="property-input"
              data-testid="text-color-select"
            >
              <option value="default">{text('default')}</option>
              <option value="secondary">{text('secondary')}</option>
              <option value="success">{text('success')}</option>
              <option value="warning">{text('warning')}</option>
              <option value="danger">{text('danger')}</option>
            </select>
          </PropertyField>

          <PropertySwitch
            label={text('bold')}
            checked={props.bold ?? false}
            onChange={(checked) => onChange({ props: { ...props, bold: checked } })}
            disabled={readonly}
            testId="text-bold"
          />
        </>
      )}
    </div>
  );
};

/**
 * Behavior properties (interactions, states)
 */
const BehaviorProperties: React.FC<PropertyEditorProps> = ({ block, onChange, readonly }) => {
  const text = useBlockSettingsText();
  const props = (block.props || {}) as Record<string, any>;

  return (
    <div className="space-y-4">
      {/* Form section behavior */}
      {(block.blockType === 'form-section' || block.blockType === 'detail-section') && (
        <>
          <PropertySwitch
            label={text('collapsible')}
            checked={block.collapsible ?? false}
            onChange={(checked) => onChange({ collapsible: checked })}
            disabled={readonly}
            testId="section-collapsible"
          />

          {block.collapsible && (
            <PropertySwitch
              label={text('defaultCollapsed')}
              checked={block.defaultCollapsed ?? false}
              onChange={(checked) => onChange({ defaultCollapsed: checked })}
              disabled={readonly}
              testId="section-default-collapsed"
            />
          )}
        </>
      )}

      {/* Filter form behavior */}
      {block.blockType === 'filters' && (
        <>
          <PropertySwitch
            label={text('advancedExpanded')}
            checked={props.defaultExpanded ?? false}
            onChange={(checked) => onChange({ props: { ...props, defaultExpanded: checked } })}
            disabled={readonly}
            testId="filter-default-expanded"
          />

          <PropertySwitch
            label={text('searchOnEnter')}
            checked={props.searchOnEnter ?? true}
            onChange={(checked) => onChange({ props: { ...props, searchOnEnter: checked } })}
            disabled={readonly}
            testId="filter-search-on-enter"
          />
        </>
      )}

      {/* Data table behavior */}
      {block.blockType === 'table' && (
        <>
          <PropertySwitch
            label={text('pagination')}
            checked={props.pagination ?? true}
            onChange={(checked) => onChange({ props: { ...props, pagination: checked } })}
            disabled={readonly}
            testId="table-pagination"
          />

          {props.pagination !== false && (
            <PropertyField label={text('pageSize')} testId="table-page-size">
              <select
                value={props.pageSize || 10}
                onChange={(e) =>
                  onChange({ props: { ...props, pageSize: Number(e.target.value) } })
                }
                disabled={readonly}
                className="property-input"
                data-testid="table-page-size-select"
              >
                <option value={10}>{text('rows', 10)}</option>
                <option value={20}>{text('rows', 20)}</option>
                <option value={50}>{text('rows', 50)}</option>
                <option value={100}>{text('rows', 100)}</option>
              </select>
            </PropertyField>
          )}

          <PropertySwitch
            label={text('rowSelection')}
            checked={props.rowSelection ?? false}
            onChange={(checked) => onChange({ props: { ...props, rowSelection: checked } })}
            disabled={readonly}
            testId="table-row-selection"
          />

          {props.rowSelection && (
            <PropertyField label={text('selectionType')} testId="table-selection-type">
              <select
                value={props.selectionType || 'checkbox'}
                onChange={(e) => onChange({ props: { ...props, selectionType: e.target.value } })}
                disabled={readonly}
                className="property-input"
                data-testid="table-selection-type-select"
              >
                <option value="checkbox">{text('multiple')}</option>
                <option value="radio">{text('single')}</option>
              </select>
            </PropertyField>
          )}

          <PropertySwitch
            label={text('sortable')}
            checked={props.sortable ?? false}
            onChange={(checked) => onChange({ props: { ...props, sortable: checked } })}
            disabled={readonly}
            testId="table-sortable"
          />

          <PropertySwitch
            label={text('exportable')}
            checked={props.exportable ?? false}
            onChange={(checked) => onChange({ props: { ...props, exportable: checked } })}
            disabled={readonly}
            testId="table-exportable"
          />
        </>
      )}
    </div>
  );
};

/**
 * Property field wrapper component
 */
interface PropertyFieldProps {
  label: string;
  hint?: string;
  testId?: string;
  children: React.ReactNode;
}

const PropertyField: React.FC<PropertyFieldProps> = ({ label, hint, testId, children }) => {
  return (
    <div data-testid={testId}>
      <label className="mb-1.5 block text-xs text-gray-500">
        {label}
        {hint && <span className="ml-1 text-gray-400">({hint})</span>}
      </label>
      {children}
    </div>
  );
};

/**
 * Property switch component
 */
interface PropertySwitchProps {
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
  testId?: string;
}

const PropertySwitch: React.FC<PropertySwitchProps> = ({
  label,
  checked,
  onChange,
  disabled,
  testId,
}) => {
  return (
    <div className="flex items-center justify-between" data-testid={testId}>
      <label className="text-xs text-gray-500">{label}</label>
      <button
        type="button"
        role="switch"
        aria-label={label}
        aria-checked={checked}
        onClick={() => onChange(!checked)}
        disabled={disabled}
        className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${
          checked ? 'bg-blue-500' : 'bg-gray-200'
        } ${disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer'}`}
        data-testid={testId ? `${testId}-switch` : undefined}
      >
        <span
          className={`inline-block h-4 w-4 transform rounded-full bg-white shadow-sm transition-transform ${
            checked ? 'translate-x-4' : 'translate-x-0.5'
          }`}
        />
      </button>
    </div>
  );
};

// Add global styles for property inputs
const styleSheet = `
.property-input {
  width: 100%;
  padding: 0.5rem 0.75rem;
  font-size: 0.875rem;
  border: 1px solid #e5e7eb;
  border-radius: 0.375rem;
  background-color: white;
}
.property-input:focus {
  outline: none;
  ring: 2px;
  ring-color: #3b82f6;
  border-color: transparent;
}
.property-input:disabled {
  background-color: #f9fafb;
  cursor: not-allowed;
}
`;

// Inject styles
if (typeof document !== 'undefined') {
  const existingStyle = document.getElementById('block-settings-editor-styles');
  if (!existingStyle) {
    const style = document.createElement('style');
    style.id = 'block-settings-editor-styles';
    style.textContent = styleSheet;
    document.head.appendChild(style);
  }
}

export default BlockSettingsEditor;
