/**
 * Property editor for Gateway nodes (Exclusive, Parallel, Inclusive).
 */

import type {
  ExclusiveGatewayConfig,
  ParallelGatewayConfig,
  InclusiveGatewayConfig,
} from '~/bpmn-designer/types';

type GatewayConfig = ExclusiveGatewayConfig | ParallelGatewayConfig | InclusiveGatewayConfig;

export function GatewayEditor({
  config,
  onChange,
}: {
  config?: GatewayConfig;
  onChange: (config: GatewayConfig) => void;
}) {
  const handleChange = (field: keyof GatewayConfig, value: any) => {
    onChange({ ...config, [field]: value } as GatewayConfig);
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
        <label className="mb-1 block text-sm font-medium text-gray-700">默认流向ID</label>
        <input
          type="text"
          value={config?.defaultFlow || ''}
          onChange={(e) => handleChange('defaultFlow', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          placeholder="edge-xxx"
        />
      </div>
    </>
  );
}
