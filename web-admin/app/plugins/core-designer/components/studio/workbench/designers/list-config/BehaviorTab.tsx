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

/**
 * Sentinel used in the defaultSortField <Select.Item> to represent "no sort
 * field selected". Radix Select.Item forbids empty-string values, so we map:
 *   VM undefined/'' → display '__none__'
 *   display '__none__' → VM undefined
 */
const SORT_FIELD_NONE = '__none__';

/** Convert VM BehaviorConfig → panel display shape (sentinel for empty sort field). */
function behaviorToDisplay(behavior: BehaviorConfig): Record<string, unknown> {
  return {
    ...(behavior as unknown as Record<string, unknown>),
    defaultSortField: behavior.defaultSortField || SORT_FIELD_NONE,
  };
}

/** Convert panel display shape → VM BehaviorConfig (strip sentinel back to undefined). */
function displayToBehavior(display: Record<string, unknown>): BehaviorConfig {
  const next = { ...display } as Record<string, unknown>;
  if (next.defaultSortField === SORT_FIELD_NONE) {
    delete next.defaultSortField;
  }
  return next as unknown as BehaviorConfig;
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
        value={behaviorToDisplay(vm.behavior)}
        onChange={(next) =>
          setVm({ ...vm, behavior: displayToBehavior(next) })
        }
        readonly={readonly}
      />
    </div>
  );
};
