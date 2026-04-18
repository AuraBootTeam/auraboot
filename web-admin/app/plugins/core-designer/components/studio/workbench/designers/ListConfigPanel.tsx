import React, { useEffect, useMemo, useRef, useState } from 'react';
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { useModelCapabilities } from '~/shared/hooks/useModelCapabilities';
import {
  blocksToViewModel,
  viewModelToBlocks,
  type ListViewModel,
} from './list-config/mapper';
import { ColumnsTab } from './list-config/ColumnsTab';
import { FiltersTab } from './list-config/FiltersTab';
import { ToolbarTab } from './list-config/ToolbarTab';
import { BehaviorTab } from './list-config/BehaviorTab';

export interface ListConfigPanelProps {
  schema: PageSchema;
  onSchemaChange: (schema: PageSchema) => void;
  onSave?: (schema: PageSchema) => Promise<void>;
  modelCode?: string;
  readonly?: boolean;
  previewMode?: boolean;
}

type Tab = 'columns' | 'filters' | 'toolbar' | 'behavior';

const TABS: Array<{ id: Tab; label: string; icon: string }> = [
  { id: 'columns', label: 'Columns', icon: '📊' },
  { id: 'filters', label: 'Filters', icon: '🔍' },
  { id: 'toolbar', label: 'Toolbar', icon: '🔘' },
  { id: 'behavior', label: 'Behavior', icon: '⚙️' },
];

/**
 * Structured config panel for kind=list pages.
 *
 * Renders 4 vertical tabs (Columns / Filters / Toolbar / Behavior); each tab
 * is a thin editor over the single `ListViewModel` state, which round-trips
 * to `PageSchema.blocks` via `blocksToViewModel` / `viewModelToBlocks`.
 *
 * All configuration editors go through `SchemaBlockConfigPanel` — no
 * hand-coded panel JSX (Studio red-line).
 */
export const ListConfigPanel: React.FC<ListConfigPanelProps> = ({
  schema,
  onSchemaChange,
  modelCode,
  readonly,
  previewMode,
}) => {
  const effectiveModelCode = modelCode ?? schema.modelCode;
  const { data: capabilities } = useModelCapabilities(effectiveModelCode);

  // Fields fallback: no dedicated resolved-fields hook in shared/ yet, so we
  // derive the field list from capabilities (sortable ∪ filterable). Detail
  // hook will be introduced when P3-T6/7 lands SampleDataLoader.
  const fields = useMemo(() => {
    if (!capabilities) return undefined;
    const set = new Set<string>([
      ...capabilities.sortableFields,
      ...capabilities.filterableFields,
    ]);
    return Array.from(set).map((code) => ({
      code,
      displayName: code,
      dataType: 'unknown',
    }));
  }, [capabilities]);

  const [tab, setTab] = useState<Tab>('columns');
  const [vm, setVm] = useState<ListViewModel>(() =>
    blocksToViewModel(schema.blocks ?? []),
  );

  // Push VM changes out to schema.blocks. Use a ref guard + JSON compare to
  // avoid echo loops when the parent re-pushes the same schema back in.
  const lastPushedRef = useRef<string>('');
  useEffect(() => {
    const nextBlocks = viewModelToBlocks(vm);
    const serialized = JSON.stringify(nextBlocks);
    if (serialized === lastPushedRef.current) return;
    if (JSON.stringify(schema.blocks ?? []) === serialized) {
      lastPushedRef.current = serialized;
      return;
    }
    lastPushedRef.current = serialized;
    onSchemaChange({ ...schema, blocks: nextBlocks });
    // Intentionally narrow deps to `vm` — outward sync only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [vm]);

  return (
    <div className="flex h-full" data-testid="list-config-panel">
      {!previewMode && (
        <aside className="w-52 shrink-0 border-r bg-gray-50">
          {TABS.map((t) => (
            <button
              key={t.id}
              type="button"
              onClick={() => setTab(t.id)}
              className={`flex w-full items-center gap-2 border-l-2 px-4 py-3 text-sm transition-colors ${
                tab === t.id
                  ? 'border-blue-500 bg-white font-medium text-blue-700'
                  : 'border-transparent text-gray-600 hover:bg-gray-100'
              }`}
              data-testid={`list-tab-${t.id}`}
            >
              <span>{t.icon}</span>
              <span>{t.label}</span>
            </button>
          ))}
        </aside>
      )}

      <main className="flex-1 overflow-auto bg-white p-6">
        {tab === 'columns' && (
          <ColumnsTab vm={vm} setVm={setVm} fields={fields} readonly={readonly} />
        )}
        {tab === 'filters' && (
          <FiltersTab
            vm={vm}
            setVm={setVm}
            fields={fields}
            capabilities={capabilities}
            readonly={readonly}
          />
        )}
        {tab === 'toolbar' && (
          <ToolbarTab
            vm={vm}
            setVm={setVm}
            capabilities={capabilities}
            readonly={readonly}
          />
        )}
        {tab === 'behavior' && (
          <BehaviorTab
            vm={vm}
            setVm={setVm}
            capabilities={capabilities}
            readonly={readonly}
          />
        )}
      </main>
    </div>
  );
};

export default ListConfigPanel;
