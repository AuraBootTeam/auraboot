import React, { useMemo } from 'react';
import type { ModelCapabilities } from '~/shared/hooks/useModelCapabilities';
import { SchemaBlockConfigPanel } from '../SchemaBlockConfigPanel';
import { buildBehaviorSchemas } from './schema';
import type { ListViewModel, BehaviorConfig } from './mapper';

export interface BehaviorTabProps {
  vm: ListViewModel;
  setVm: (next: ListViewModel) => void;
  capabilities: ModelCapabilities | undefined;
  readonly?: boolean;
}

export const BehaviorTab: React.FC<BehaviorTabProps> = ({
  vm,
  setVm,
  capabilities,
  readonly,
}) => {
  const schemas = useMemo(
    () =>
      buildBehaviorSchemas(
        capabilities?.sortableFields ?? [],
        capabilities?.filterableFields ?? [],
      ),
    [capabilities],
  );

  if (!capabilities) {
    return <div className="text-sm text-gray-400">加载 capabilities 中...</div>;
  }

  return (
    <div data-testid="behavior-tab">
      <h2 className="mb-4 text-lg font-medium">页面行为</h2>
      <SchemaBlockConfigPanel
        schemas={schemas}
        value={vm.behavior as unknown as Record<string, unknown>}
        onChange={(next) =>
          setVm({ ...vm, behavior: next as unknown as BehaviorConfig })
        }
        readonly={readonly}
      />
    </div>
  );
};
