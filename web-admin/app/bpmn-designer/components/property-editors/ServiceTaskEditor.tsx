/**
 * Property editor for ServiceTask nodes.
 */

import type { ServiceTaskConfig } from '~/bpmn-designer/types';
import { HookConfigSection } from './shared';

export function ServiceTaskEditor({
  config,
  onChange,
}: {
  config?: ServiceTaskConfig;
  onChange: (config: ServiceTaskConfig) => void;
}) {
  const handleChange = (field: keyof ServiceTaskConfig, value: any) => {
    onChange({ ...config, [field]: value } as ServiceTaskConfig);
  };

  return (
    <>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">描述</label>
        <textarea
          value={config?.description || ''}
          onChange={(e) => handleChange('description', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          rows={2}
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">服务类型</label>
        <select
          value={config?.serviceType || 'http'}
          onChange={(e) => handleChange('serviceType', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
        >
          <option value="http">HTTP服务</option>
          <option value="java">Java类</option>
          <option value="script">脚本</option>
        </select>
      </div>

      {config?.serviceType === 'http' && (
        <div className="mb-4">
          <label className="mb-1 block text-sm font-medium text-gray-700">服务URL</label>
          <input
            type="text"
            value={config?.serviceUrl || ''}
            onChange={(e) => handleChange('serviceUrl', e.target.value)}
            className="w-full rounded-md border border-gray-300 px-3 py-2"
            placeholder="https://api.example.com/service"
          />
        </div>
      )}

      {config?.serviceType === 'java' && (
        <div className="mb-4">
          <label className="mb-1 block text-sm font-medium text-gray-700">Java类名</label>
          <input
            type="text"
            value={config?.className || ''}
            onChange={(e) => handleChange('className', e.target.value)}
            className="w-full rounded-md border border-gray-300 px-3 py-2"
            placeholder="com.example.MyService"
          />
        </div>
      )}

      {config?.serviceType === 'script' && (
        <>
          <div className="mb-4">
            <label className="mb-1 block text-sm font-medium text-gray-700">脚本类型</label>
            <select
              value={config?.scriptType || 'javascript'}
              onChange={(e) => handleChange('scriptType', e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2"
            >
              <option value="javascript">JavaScript</option>
              <option value="groovy">Groovy</option>
            </select>
          </div>
          <div className="mb-4">
            <label className="mb-1 block text-sm font-medium text-gray-700">脚本内容</label>
            <textarea
              value={config?.scriptContent || ''}
              onChange={(e) => handleChange('scriptContent', e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm"
              rows={6}
            />
          </div>
        </>
      )}

      <div className="mb-4">
        <label className="flex items-center">
          <input
            type="checkbox"
            checked={config?.async || false}
            onChange={(e) => handleChange('async', e.target.checked)}
            className="mr-2"
          />
          <span className="text-sm font-medium text-gray-700">异步执行</span>
        </label>
      </div>

      {/* Hook configuration */}
      <HookConfigSection
        hooks={config?.hooks || []}
        onChange={(hooks) => handleChange('hooks', hooks)}
      />
    </>
  );
}
