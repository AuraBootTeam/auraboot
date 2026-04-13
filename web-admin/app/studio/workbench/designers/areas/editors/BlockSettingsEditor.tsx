/**
 * Block Settings Editor
 *
 * Editor for block-specific settings based on block type.
 * Provides different property editors for each block type.
 */

import React, { useState } from 'react';
import type { DslBlock } from '~/studio/domain/dsl/types';

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
const GROUP_CONFIG: Record<PropertyGroup, { label: string; icon: string }> = {
  basic: { label: '基本', icon: '📝' },
  layout: { label: '布局', icon: '📐' },
  data: { label: '数据', icon: '📊' },
  appearance: { label: '外观', icon: '🎨' },
  behavior: { label: '行为', icon: '⚡' },
};

export const BlockSettingsEditor: React.FC<BlockSettingsEditorProps> = ({
  block,
  onChange,
  readonly,
}) => {
  const [activeGroup, setActiveGroup] = useState<PropertyGroup>('basic');

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
              {GROUP_CONFIG[group].label}
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

/**
 * Basic properties (title, id, visibility)
 */
const BasicProperties: React.FC<PropertyEditorProps> = ({ block, onChange, readonly }) => {
  const showTitle =
    block.blockType === 'form-section' ||
    block.blockType === 'detail-section' ||
    block.blockType === 'stat-card' ||
    block.blockType === 'chart-card';

  return (
    <div className="space-y-4">
      {/* Title */}
      {showTitle && (
        <PropertyField label="标题" testId="block-title">
          <input
            type="text"
            value={block.title || ''}
            onChange={(e) => onChange({ title: e.target.value || undefined })}
            disabled={readonly}
            className="property-input"
            placeholder="输入标题"
            data-testid="block-title-input"
          />
        </PropertyField>
      )}

      {/* Text content */}
      {block.blockType === 'text' && (
        <PropertyField label="内容" testId="text-content">
          <textarea
            value={(block.props as any)?.content || ''}
            onChange={(e) => onChange({ props: { ...block.props, content: e.target.value } })}
            disabled={readonly}
            className="property-input min-h-[80px] resize-y"
            placeholder="输入文本内容"
            data-testid="text-content-input"
          />
        </PropertyField>
      )}

      {/* Visibility condition */}
      <PropertyField label="显示条件" hint="SpEL 表达式" testId="block-visible">
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
  const props = (block.props || {}) as Record<string, any>;

  return (
    <div className="space-y-4">
      {/* Span */}
      <PropertyField label="栅格宽度" testId="block-span">
        <select
          value={block.span || ''}
          onChange={(e) => onChange({ span: e.target.value ? Number(e.target.value) : undefined })}
          disabled={readonly}
          className="property-input"
          data-testid="block-span-select"
        >
          <option value="">自动</option>
          {[1, 2, 3, 4, 6, 8, 12].map((n) => (
            <option key={n} value={n}>
              {n} 列
            </option>
          ))}
        </select>
      </PropertyField>

      {/* Columns (for form sections) */}
      {(block.blockType === 'form-section' ||
        block.blockType === 'detail-section' ||
        block.blockType === 'filters') && (
        <PropertyField label="表单列数" testId="block-columns">
          <select
            value={props.columns || 2}
            onChange={(e) => onChange({ props: { ...props, columns: Number(e.target.value) } })}
            disabled={readonly}
            className="property-input"
            data-testid="block-columns-select"
          >
            <option value={1}>1 列</option>
            <option value={2}>2 列</option>
            <option value={3}>3 列</option>
            <option value={4}>4 列</option>
          </select>
        </PropertyField>
      )}

      {/* Gutter */}
      {(block.blockType === 'form-section' || block.blockType === 'detail-section') && (
        <PropertyField label="间距" testId="block-gutter">
          <select
            value={props.gutter || 16}
            onChange={(e) => onChange({ props: { ...props, gutter: Number(e.target.value) } })}
            disabled={readonly}
            className="property-input"
            data-testid="block-gutter-select"
          >
            <option value={8}>紧凑 (8px)</option>
            <option value={16}>标准 (16px)</option>
            <option value={24}>宽松 (24px)</option>
            <option value={32}>超宽 (32px)</option>
          </select>
        </PropertyField>
      )}

      {/* Button layout */}
      {(block.blockType === 'toolbar' || block.blockType === 'form-buttons') && (
        <PropertyField label="按钮对齐" testId="button-align">
          <select
            value={props.align || 'left'}
            onChange={(e) => onChange({ props: { ...props, align: e.target.value } })}
            disabled={readonly}
            className="property-input"
            data-testid="button-align-select"
          >
            <option value="left">左对齐</option>
            <option value="center">居中</option>
            <option value="right">右对齐</option>
          </select>
        </PropertyField>
      )}
    </div>
  );
};

/**
 * Data properties (data source, bindings)
 */
const DataProperties: React.FC<PropertyEditorProps> = ({ block, onChange, readonly }) => {
  const props = (block.props || {}) as Record<string, any>;

  return (
    <div className="space-y-4">
      {/* Data source (for table) */}
      {block.blockType === 'table' && (
        <>
          <PropertyField label="数据源" testId="data-source">
            <input
              type="text"
              value={block.dataSource || ''}
              onChange={(e) => onChange({ dataSource: e.target.value || undefined })}
              disabled={readonly}
              className="property-input font-mono text-xs"
              placeholder="tableData"
              data-testid="data-source-input"
            />
          </PropertyField>

          <PropertyField label="选择绑定" hint="绑定选中行" testId="selection-bind">
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

          <PropertyField label="行键字段" hint="唯一标识字段" testId="row-key">
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
          <PropertyField label="数值字段" testId="stat-value-field">
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

          <PropertyField label="变化率字段" testId="stat-change-field">
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
        </>
      )}

      {/* Chart data */}
      {block.blockType === 'chart-card' && (
        <>
          <PropertyField label="图表类型" testId="chart-type">
            <select
              value={props.chartType || 'bar'}
              onChange={(e) => onChange({ props: { ...props, chartType: e.target.value } })}
              disabled={readonly}
              className="property-input"
              data-testid="chart-type-select"
            >
              <option value="bar">柱状图</option>
              <option value="line">折线图</option>
              <option value="pie">饼图</option>
              <option value="area">面积图</option>
            </select>
          </PropertyField>

          <PropertyField label="X轴字段" testId="chart-x-field">
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

          <PropertyField label="Y轴字段" testId="chart-y-field">
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
        </>
      )}
    </div>
  );
};

/**
 * Appearance properties (style, theme)
 */
const AppearanceProperties: React.FC<PropertyEditorProps> = ({ block, onChange, readonly }) => {
  const props = (block.props || {}) as Record<string, any>;

  return (
    <div className="space-y-4">
      {/* Data table appearance */}
      {block.blockType === 'table' && (
        <>
          <PropertySwitch
            label="显示边框"
            checked={props.bordered ?? true}
            onChange={(checked) => onChange({ props: { ...props, bordered: checked } })}
            disabled={readonly}
            testId="table-bordered"
          />

          <PropertySwitch
            label="斑马纹"
            checked={props.striped ?? false}
            onChange={(checked) => onChange({ props: { ...props, striped: checked } })}
            disabled={readonly}
            testId="table-striped"
          />

          <PropertySwitch
            label="显示序号"
            checked={props.showIndex ?? false}
            onChange={(checked) => onChange({ props: { ...props, showIndex: checked } })}
            disabled={readonly}
            testId="table-show-index"
          />

          <PropertyField label="表格尺寸" testId="table-size">
            <select
              value={props.size || 'middle'}
              onChange={(e) => onChange({ props: { ...props, size: e.target.value } })}
              disabled={readonly}
              className="property-input"
              data-testid="table-size-select"
            >
              <option value="small">紧凑</option>
              <option value="middle">标准</option>
              <option value="large">宽松</option>
            </select>
          </PropertyField>
        </>
      )}

      {/* Stat card appearance */}
      {block.blockType === 'stat-card' && (
        <>
          <PropertyField label="前缀" testId="stat-prefix">
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

          <PropertyField label="后缀" testId="stat-suffix">
            <input
              type="text"
              value={props.suffix || ''}
              onChange={(e) => onChange({ props: { ...props, suffix: e.target.value } })}
              disabled={readonly}
              className="property-input"
              placeholder="元"
              data-testid="stat-suffix-input"
            />
          </PropertyField>

          <PropertyField label="主题色" testId="stat-color">
            <select
              value={props.color || 'blue'}
              onChange={(e) => onChange({ props: { ...props, color: e.target.value } })}
              disabled={readonly}
              className="property-input"
              data-testid="stat-color-select"
            >
              <option value="blue">蓝色</option>
              <option value="green">绿色</option>
              <option value="orange">橙色</option>
              <option value="red">红色</option>
              <option value="purple">紫色</option>
            </select>
          </PropertyField>
        </>
      )}

      {/* Chart appearance */}
      {block.blockType === 'chart-card' && (
        <>
          <PropertySwitch
            label="平滑曲线"
            checked={props.smooth ?? true}
            onChange={(checked) => onChange({ props: { ...props, smooth: checked } })}
            disabled={readonly}
            testId="chart-smooth"
          />

          <PropertySwitch
            label="显示图例"
            checked={props.showLegend ?? true}
            onChange={(checked) => onChange({ props: { ...props, showLegend: checked } })}
            disabled={readonly}
            testId="chart-legend"
          />

          <PropertyField label="图表高度" testId="chart-height">
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
          <PropertyField label="文字大小" testId="text-size">
            <select
              value={props.size || 'base'}
              onChange={(e) => onChange({ props: { ...props, size: e.target.value } })}
              disabled={readonly}
              className="property-input"
              data-testid="text-size-select"
            >
              <option value="xs">超小</option>
              <option value="sm">小</option>
              <option value="base">标准</option>
              <option value="lg">大</option>
              <option value="xl">超大</option>
            </select>
          </PropertyField>

          <PropertyField label="文字颜色" testId="text-color">
            <select
              value={props.color || 'default'}
              onChange={(e) => onChange({ props: { ...props, color: e.target.value } })}
              disabled={readonly}
              className="property-input"
              data-testid="text-color-select"
            >
              <option value="default">默认</option>
              <option value="secondary">次要</option>
              <option value="success">成功</option>
              <option value="warning">警告</option>
              <option value="danger">危险</option>
            </select>
          </PropertyField>

          <PropertySwitch
            label="加粗"
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
  const props = (block.props || {}) as Record<string, any>;

  return (
    <div className="space-y-4">
      {/* Form section behavior */}
      {(block.blockType === 'form-section' || block.blockType === 'detail-section') && (
        <>
          <PropertySwitch
            label="可折叠"
            checked={block.collapsible ?? false}
            onChange={(checked) => onChange({ collapsible: checked })}
            disabled={readonly}
            testId="section-collapsible"
          />

          {block.collapsible && (
            <PropertySwitch
              label="默认收起"
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
            label="展开高级筛选"
            checked={props.defaultExpanded ?? false}
            onChange={(checked) => onChange({ props: { ...props, defaultExpanded: checked } })}
            disabled={readonly}
            testId="filter-default-expanded"
          />

          <PropertySwitch
            label="回车搜索"
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
            label="启用分页"
            checked={props.pagination ?? true}
            onChange={(checked) => onChange({ props: { ...props, pagination: checked } })}
            disabled={readonly}
            testId="table-pagination"
          />

          {props.pagination !== false && (
            <PropertyField label="每页条数" testId="table-page-size">
              <select
                value={props.pageSize || 10}
                onChange={(e) =>
                  onChange({ props: { ...props, pageSize: Number(e.target.value) } })
                }
                disabled={readonly}
                className="property-input"
                data-testid="table-page-size-select"
              >
                <option value={10}>10 条</option>
                <option value={20}>20 条</option>
                <option value={50}>50 条</option>
                <option value={100}>100 条</option>
              </select>
            </PropertyField>
          )}

          <PropertySwitch
            label="行可选择"
            checked={props.rowSelection ?? false}
            onChange={(checked) => onChange({ props: { ...props, rowSelection: checked } })}
            disabled={readonly}
            testId="table-row-selection"
          />

          {props.rowSelection && (
            <PropertyField label="选择模式" testId="table-selection-type">
              <select
                value={props.selectionType || 'checkbox'}
                onChange={(e) => onChange({ props: { ...props, selectionType: e.target.value } })}
                disabled={readonly}
                className="property-input"
                data-testid="table-selection-type-select"
              >
                <option value="checkbox">多选</option>
                <option value="radio">单选</option>
              </select>
            </PropertyField>
          )}

          <PropertySwitch
            label="可排序"
            checked={props.sortable ?? false}
            onChange={(checked) => onChange({ props: { ...props, sortable: checked } })}
            disabled={readonly}
            testId="table-sortable"
          />

          <PropertySwitch
            label="可导出"
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
