/**
 * Property editor for Parallel Gateway nodes.
 * Simple editor with description only — defaultFlow is meaningless for parallel gateways.
 */

import type { ParallelGatewayConfig } from '~/plugins/core-designer/components/bpmn-designer/types';
import { useI18n } from '~/contexts/I18nContext';

interface ParallelGatewayEditorProps {
  config?: ParallelGatewayConfig;
  onChange: (config: ParallelGatewayConfig) => void;
}

export function ParallelGatewayEditor({ config, onChange }: ParallelGatewayEditorProps) {
  const { t } = useI18n();

  const handleChange = (field: keyof ParallelGatewayConfig, value: string) => {
    onChange({ ...config, name: config?.name || '', [field]: value });
  };

  return (
    <>
      {/* Info text */}
      <div className="mb-4 rounded-md border border-blue-100 bg-blue-50 px-3 py-2">
        <p className="text-sm text-blue-700">{t('bpmn.gateway.parallelInfo')}</p>
      </div>

      {/* Description */}
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.common.description')}</label>
        <textarea
          value={config?.description || ''}
          onChange={(e) => handleChange('description', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
          rows={2}
          placeholder={t('bpmn.gateway.parallelDescPlaceholder')}
        />
      </div>
    </>
  );
}
