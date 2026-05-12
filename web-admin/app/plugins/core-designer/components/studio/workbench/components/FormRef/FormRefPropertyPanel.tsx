/**
 * FormRef 属性配置面板
 *
 * 为 FormRef 组件提供可视化的属性配置界面
 */

import React, { useState, useCallback, useEffect } from 'react';
import type {
  FormRefProps,
  FormRefMode,
  FormDataSource,
} from '~/plugins/core-designer/components/studio/workbench/components/FormRef/types';

/**
 * 属性面板的属性
 */
interface FormRefPropertyPanelProps {
  /** 当前 FormRef 组件的属性 */
  value: Partial<FormRefProps>;
  /** 属性变化回调 */
  onChange: (props: Partial<FormRefProps>) => void;
  /** 可用的表单列表 */
  availableForms?: Array<{
    id: string;
    title: string;
    description?: string;
    lastModified?: string;
  }>;
  /** 是否只读 */
  readonly?: boolean;
}

const TOP_LEVEL_PROPERTY_PATHS = new Set<string>([
  'formId',
  'mode',
  'id',
  'disabled',
  'readonly',
  'initialValues',
  'validateOnChange',
  'validateOnBlur',
  'showErrorsOnSubmit',
  'validationDebounce',
  'customValidator',
  'className',
  'style',
  'styleOverrides',
  'onSubmitSuccess',
  'onSubmitError',
  'onFieldChange',
  'onFormLoad',
  'debug',
]);

const DATA_SOURCE_PROPERTY_PATHS = new Set<string>([
  'type',
  'endpoint',
  'method',
  'headers',
  'data',
  'contextPath',
]);

function resolveDataSourcePath(path: string): string | null {
  if (!path.startsWith('dataSource.')) {
    return null;
  }

  const key = path.slice('dataSource.'.length);
  return DATA_SOURCE_PROPERTY_PATHS.has(key) ? key : null;
}

/**
 * FormRef 属性配置面板组件
 */
export const FormRefPropertyPanel: React.FC<FormRefPropertyPanelProps> = ({
  value,
  onChange,
  availableForms = [],
  readonly = false,
}) => {
  const [activeTab, setActiveTab] = useState<
    'basic' | 'data' | 'validation' | 'layout' | 'advanced'
  >('basic');
  const [formPreview, setFormPreview] = useState<any>(null);

  // 处理属性更新
  const handlePropertyChange = useCallback(
    (path: string, newValue: any) => {
      if (readonly) return;

      const dataSourceKey = resolveDataSourcePath(path);
      if (dataSourceKey) {
        const currentValue = value as Record<string, any>;
        const nextValue = {
          ...value,
          dataSource: {
            ...currentValue.dataSource,
            [dataSourceKey]: newValue,
          },
        } as Partial<FormRefProps>;
        onChange(nextValue);
        return;
      }

      if (!TOP_LEVEL_PROPERTY_PATHS.has(path)) {
        return;
      }
      onChange({ ...value, [path]: newValue });
    },
    [value, onChange, readonly],
  );

  // 获取嵌套属性值
  const getNestedValue = useCallback(
    (path: string, defaultValue: any = '') => {
      const dataSourceKey = resolveDataSourcePath(path);
      if (dataSourceKey) {
        const currentValue = value as Record<string, any>;
        return currentValue.dataSource?.[dataSourceKey] ?? defaultValue;
      }

      if (!TOP_LEVEL_PROPERTY_PATHS.has(path)) return defaultValue;

      return (value as Record<string, any>)[path] ?? defaultValue;
    },
    [value],
  );

  // 加载表单预览
  const loadFormPreview = useCallback(
    async (formId: string) => {
      if (!formId) {
        setFormPreview(null);
        return;
      }

      try {
        // 这里应该调用实际的 API 获取表单预览
        // 暂时使用模拟数据
        const mockPreview = {
          id: formId,
          title: availableForms.find((f) => f.id === formId)?.title || 'Unknown Form',
          fieldCount: 1,
          lastModified: new Date().toISOString(),
        };
        setFormPreview(mockPreview);
      } catch (error) {
        console.error('Failed to load form preview:', error);
        setFormPreview(null);
      }
    },
    [availableForms],
  );

  // 当表单ID变化时加载预览
  useEffect(() => {
    const formId = getNestedValue('formId');
    if (formId) {
      loadFormPreview(formId);
    }
  }, [getNestedValue, loadFormPreview]);

  // 渲染标签页导航
  const renderTabNavigation = () => {
    const tabs = [
      { key: 'basic', label: '基础设置' },
      { key: 'data', label: '数据源' },
      { key: 'validation', label: '验证规则' },
      { key: 'layout', label: '布局样式' },
      { key: 'advanced', label: '高级选项' },
    ];

    return (
      <div className="mb-4 border-b border-gray-200">
        <nav className="-mb-px flex space-x-8">
          {tabs.map((tab) => (
            <button
              key={tab.key}
              onClick={() => setActiveTab(tab.key as any)}
              className={`border-b-2 px-1 py-2 text-sm font-medium ${
                activeTab === tab.key
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </nav>
      </div>
    );
  };

  // 渲染基础设置面板
  const renderBasicPanel = () => (
    <div className="space-y-4">
      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">表单选择 *</label>
        <select
          value={getNestedValue('formId')}
          onChange={(e) => handlePropertyChange('formId', e.target.value)}
          disabled={readonly}
          className="block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50"
        >
          <option value="">请选择表单</option>
          {availableForms.map((form) => (
            <option key={form.id} value={form.id}>
              {form.title}
            </option>
          ))}
        </select>
        {formPreview && (
          <div className="mt-2 rounded bg-gray-50 p-2 text-sm text-gray-600">
            <div>字段数量: {formPreview.fieldCount}</div>
            <div>最后修改: {new Date(formPreview.lastModified).toLocaleString()}</div>
          </div>
        )}
      </div>

      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">引用模式</label>
        <select
          value={getNestedValue('mode', 'pointer')}
          onChange={(e) => handlePropertyChange('mode', e.target.value as FormRefMode)}
          disabled={readonly}
          className="block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50"
        >
          <option value="pointer">指针引用 (实时同步)</option>
          <option value="snapshot">快照引用 (独立副本)</option>
        </select>
        <p className="mt-1 text-sm text-gray-500">
          {getNestedValue('mode', 'pointer') === 'pointer'
            ? '表单内容会实时同步原始表单的变化'
            : '表单内容是创建时的快照，不会受原始表单变化影响'}
        </p>
      </div>

      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">组件标识</label>
        <input
          type="text"
          value={getNestedValue('id')}
          onChange={(e) => handlePropertyChange('id', e.target.value)}
          placeholder="自动生成"
          disabled={readonly}
          className="block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50"
        />
      </div>

      <div className="flex items-center">
        <input
          type="checkbox"
          id="disabled"
          checked={getNestedValue('disabled', false)}
          onChange={(e) => handlePropertyChange('disabled', e.target.checked)}
          disabled={readonly}
          className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
        />
        <label htmlFor="disabled" className="ml-2 block text-sm text-gray-900">
          禁用表单
        </label>
      </div>

      <div className="flex items-center">
        <input
          type="checkbox"
          id="readonly"
          checked={getNestedValue('readonly', false)}
          onChange={(e) => handlePropertyChange('readonly', e.target.checked)}
          disabled={readonly}
          className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
        />
        <label htmlFor="readonly" className="ml-2 block text-sm text-gray-900">
          只读模式
        </label>
      </div>
    </div>
  );

  // 渲染数据源面板
  const renderDataPanel = () => (
    <div className="space-y-4">
      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">数据源类型</label>
        <select
          value={getNestedValue('dataSource.type', 'api')}
          onChange={(e) =>
            handlePropertyChange('dataSource.type', e.target.value as FormDataSource['type'])
          }
          disabled={readonly}
          className="block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50"
        >
          <option value="api">API 接口</option>
          <option value="static">静态数据</option>
          <option value="context">上下文数据</option>
        </select>
      </div>

      {getNestedValue('dataSource.type', 'api') === 'api' && (
        <>
          <div>
            <label className="mb-2 block text-sm font-medium text-gray-700">API 端点</label>
            <input
              type="url"
              value={getNestedValue('dataSource.endpoint')}
              onChange={(e) => handlePropertyChange('dataSource.endpoint', e.target.value)}
              placeholder="https://api.example.com/form-data"
              disabled={readonly}
              className="block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50"
            />
          </div>

          <div>
            <label className="mb-2 block text-sm font-medium text-gray-700">请求方法</label>
            <select
              value={getNestedValue('dataSource.method', 'get')}
              onChange={(e) => handlePropertyChange('dataSource.method', e.target.value)}
              disabled={readonly}
              className="block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50"
            >
              <option value="get">GET</option>
              <option value="post">POST</option>
            </select>
          </div>

          <div>
            <label className="mb-2 block text-sm font-medium text-gray-700">请求头 (JSON)</label>
            <textarea
              value={JSON.stringify(getNestedValue('dataSource.headers', {}), null, 2)}
              onChange={(e) => {
                try {
                  const headers = JSON.parse(e.target.value);
                  handlePropertyChange('dataSource.headers', headers);
                } catch {
                  // 忽略无效的 JSON
                }
              }}
              placeholder='{\n  "Authorization": "Bearer token",\n  "Content-Type": "application/json"\n}'
              disabled={readonly}
              rows={4}
              className="block w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50"
            />
          </div>
        </>
      )}

      {getNestedValue('dataSource.type', 'api') === 'static' && (
        <div>
          <label className="mb-2 block text-sm font-medium text-gray-700">静态数据 (JSON)</label>
          <textarea
            value={JSON.stringify(getNestedValue('dataSource.data', {}), null, 2)}
            onChange={(e) => {
              try {
                const data = JSON.parse(e.target.value);
                handlePropertyChange('dataSource.data', data);
              } catch {
                // 忽略无效的 JSON
              }
            }}
            placeholder='{\n  "name": "张三",\n  "email": "zhangsan@example.com"\n}'
            disabled={readonly}
            rows={6}
            className="block w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50"
          />
        </div>
      )}

      {getNestedValue('dataSource.type', 'api') === 'context' && (
        <div>
          <label className="mb-2 block text-sm font-medium text-gray-700">上下文路径</label>
          <input
            type="text"
            value={getNestedValue('dataSource.contextPath')}
            onChange={(e) => handlePropertyChange('dataSource.contextPath', e.target.value)}
            placeholder="user.profile"
            disabled={readonly}
            className="block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50"
          />
          <p className="mt-1 text-sm text-gray-500">
            使用点号分隔的路径访问上下文数据，如: user.profile.name
          </p>
        </div>
      )}

      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">初始值 (JSON)</label>
        <textarea
          value={JSON.stringify(getNestedValue('initialValues', {}), null, 2)}
          onChange={(e) => {
            try {
              const initialValues = JSON.parse(e.target.value);
              handlePropertyChange('initialValues', initialValues);
            } catch {
              // 忽略无效的 JSON
            }
          }}
          placeholder='{\n  "field1": "default value",\n  "field2": true\n}'
          disabled={readonly}
          rows={4}
          className="block w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50"
        />
      </div>
    </div>
  );

  // 渲染验证规则面板
  const renderValidationPanel = () => (
    <div className="space-y-4">
      <div className="flex items-center">
        <input
          type="checkbox"
          id="validateOnChange"
          checked={getNestedValue('validateOnChange', true)}
          onChange={(e) => handlePropertyChange('validateOnChange', e.target.checked)}
          disabled={readonly}
          className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
        />
        <label htmlFor="validateOnChange" className="ml-2 block text-sm text-gray-900">
          输入时验证
        </label>
      </div>

      <div className="flex items-center">
        <input
          type="checkbox"
          id="validateOnBlur"
          checked={getNestedValue('validateOnBlur', true)}
          onChange={(e) => handlePropertyChange('validateOnBlur', e.target.checked)}
          disabled={readonly}
          className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
        />
        <label htmlFor="validateOnBlur" className="ml-2 block text-sm text-gray-900">
          失去焦点时验证
        </label>
      </div>

      <div className="flex items-center">
        <input
          type="checkbox"
          id="showErrorsOnSubmit"
          checked={getNestedValue('showErrorsOnSubmit', true)}
          onChange={(e) => handlePropertyChange('showErrorsOnSubmit', e.target.checked)}
          disabled={readonly}
          className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
        />
        <label htmlFor="showErrorsOnSubmit" className="ml-2 block text-sm text-gray-900">
          提交时显示所有错误
        </label>
      </div>

      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">验证延迟 (毫秒)</label>
        <input
          type="number"
          value={getNestedValue('validationDebounce', 300)}
          onChange={(e) =>
            handlePropertyChange('validationDebounce', parseInt(e.target.value) || 300)
          }
          min="0"
          max="2000"
          disabled={readonly}
          className="block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50"
        />
        <p className="mt-1 text-sm text-gray-500">输入后延迟多长时间开始验证，避免频繁验证</p>
      </div>

      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">自定义验证函数</label>
        <textarea
          value={getNestedValue('customValidator', '')}
          onChange={(e) => handlePropertyChange('customValidator', e.target.value)}
          placeholder="function validate(data) {\n  // 返回错误对象或 null\n  return null;\n}"
          disabled={readonly}
          rows={6}
          className="block w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50"
        />
        <p className="mt-1 text-sm text-gray-500">
          JavaScript 函数，接收表单数据，返回错误对象或 null
        </p>
      </div>
    </div>
  );

  // 渲染布局样式面板
  const renderLayoutPanel = () => (
    <div className="space-y-4">
      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">CSS 类名</label>
        <input
          type="text"
          value={getNestedValue('className')}
          onChange={(e) => handlePropertyChange('className', e.target.value)}
          placeholder="custom-form-class"
          disabled={readonly}
          className="block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50"
        />
      </div>

      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">自定义样式 (CSS)</label>
        <textarea
          value={JSON.stringify(getNestedValue('style', {}), null, 2)}
          onChange={(e) => {
            try {
              const style = JSON.parse(e.target.value);
              handlePropertyChange('style', style);
            } catch {
              // 忽略无效的 JSON
            }
          }}
          placeholder='{\n  "padding": "20px",\n  "backgroundColor": "#f5f5f5"\n}'
          disabled={readonly}
          rows={6}
          className="block w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50"
        />
      </div>

      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">样式覆盖</label>
        <textarea
          value={JSON.stringify(getNestedValue('styleOverrides', {}), null, 2)}
          onChange={(e) => {
            try {
              const styleOverrides = JSON.parse(e.target.value);
              handlePropertyChange('styleOverrides', styleOverrides);
            } catch {
              // 忽略无效的 JSON
            }
          }}
          placeholder='{\n  "container": {"margin": "10px"},\n  "field": {"marginBottom": "15px"}\n}'
          disabled={readonly}
          rows={8}
          className="block w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50"
        />
        <p className="mt-1 text-sm text-gray-500">
          可覆盖 container、field、label、input、button 等元素的样式
        </p>
      </div>
    </div>
  );

  // 渲染高级选项面板
  const renderAdvancedPanel = () => (
    <div className="space-y-4">
      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">提交成功回调</label>
        <textarea
          value={getNestedValue('onSubmitSuccess', '')}
          onChange={(e) => handlePropertyChange('onSubmitSuccess', e.target.value)}
          placeholder="function onSuccess(data, response) {\n  console.log('Form submitted:', data);\n}"
          disabled={readonly}
          rows={4}
          className="block w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50"
        />
      </div>

      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">提交失败回调</label>
        <textarea
          value={getNestedValue('onSubmitError', '')}
          onChange={(e) => handlePropertyChange('onSubmitError', e.target.value)}
          placeholder="function onError(error) {\n  console.error('Form error:', error);\n}"
          disabled={readonly}
          rows={4}
          className="block w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50"
        />
      </div>

      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">字段变化回调</label>
        <textarea
          value={getNestedValue('onFieldChange', '')}
          onChange={(e) => handlePropertyChange('onFieldChange', e.target.value)}
          placeholder="function onChange(fieldName, value, allData) {\n  console.log('Field changed:', fieldName, value);\n}"
          disabled={readonly}
          rows={4}
          className="block w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50"
        />
      </div>

      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">表单加载回调</label>
        <textarea
          value={getNestedValue('onFormLoad', '')}
          onChange={(e) => handlePropertyChange('onFormLoad', e.target.value)}
          placeholder="function onLoad(schema, data) {\n  console.log('Form loaded:', schema);\n}"
          disabled={readonly}
          rows={4}
          className="block w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50"
        />
      </div>

      <div className="flex items-center">
        <input
          type="checkbox"
          id="enableDebug"
          checked={getNestedValue('debug', false)}
          onChange={(e) => handlePropertyChange('debug', e.target.checked)}
          disabled={readonly}
          className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
        />
        <label htmlFor="enableDebug" className="ml-2 block text-sm text-gray-900">
          启用调试模式
        </label>
      </div>
    </div>
  );

  // 渲染当前活动的面板
  const renderActivePanel = () => {
    switch (activeTab) {
      case 'basic':
        return renderBasicPanel();
      case 'data':
        return renderDataPanel();
      case 'validation':
        return renderValidationPanel();
      case 'layout':
        return renderLayoutPanel();
      case 'advanced':
        return renderAdvancedPanel();
      default:
        return renderBasicPanel();
    }
  };

  return (
    <div className="formref-property-panel rounded-lg border border-gray-200 bg-white p-4">
      <div className="mb-4">
        <h3 className="text-lg font-medium text-gray-900">FormRef 属性配置</h3>
        <p className="text-sm text-gray-500">配置表单引用组件的各项属性</p>
      </div>

      {renderTabNavigation()}

      <div className="panel-content">{renderActivePanel()}</div>

      {readonly && (
        <div className="mt-4 rounded-md border border-yellow-200 bg-yellow-50 p-3">
          <div className="flex">
            <div className="flex-shrink-0">
              <svg className="h-5 w-5 text-yellow-400" viewBox="0 0 20 20" fill="currentColor">
                <path
                  fillRule="evenodd"
                  d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z"
                  clipRule="evenodd"
                />
              </svg>
            </div>
            <div className="ml-3">
              <p className="text-sm text-yellow-700">当前处于只读模式，无法修改属性配置</p>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default FormRefPropertyPanel;
