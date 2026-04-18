import React, { useEffect, useMemo, useRef, useState } from 'react';
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { useModelCapabilities } from '~/shared/hooks/useModelCapabilities';
import { blocksToDetailVm, detailVmToBlocks, type DetailViewModel } from './detail-config/mapper';
import { SectionsTab } from './detail-config/SectionsTab';
import { ActionsTab } from './detail-config/ActionsTab';

export interface DetailConfigPanelProps {
  schema: PageSchema;
  onSchemaChange: (schema: PageSchema) => void;
  onSave?: (schema: PageSchema) => Promise<void>;
  modelCode?: string;
  readonly?: boolean;
  previewMode?: boolean;
}

type Tab = 'sections' | 'actions';

const TABS: Array<{ id: Tab; label: string; icon: string }> = [
  { id: 'sections', label: 'Sections', icon: '📄' },
  { id: 'actions', label: 'Actions', icon: '⚡' },
];

export const DetailConfigPanel: React.FC<DetailConfigPanelProps> = ({
  schema, onSchemaChange, modelCode, readonly, previewMode,
}) => {
  const effectiveModelCode = modelCode ?? schema.modelCode;
  const { data: capabilities } = useModelCapabilities(effectiveModelCode);

  const fields = useMemo(() => {
    if (!capabilities) return undefined;
    const set = new Set<string>([
      ...capabilities.sortableFields,
      ...capabilities.filterableFields,
    ]);
    return Array.from(set).map((code) => ({ code, displayName: code, dataType: 'unknown' }));
  }, [capabilities]);

  const [tab, setTab] = useState<Tab>('sections');
  const [vm, setVm] = useState<DetailViewModel>(() => blocksToDetailVm(schema.blocks ?? []));
  const lastPushedRef = useRef<string>('');

  useEffect(() => {
    const nextBlocks = detailVmToBlocks(vm);
    const nextSerialized = JSON.stringify(nextBlocks);
    const currentSerialized = JSON.stringify(schema.blocks ?? []);
    if (nextSerialized !== currentSerialized && nextSerialized !== lastPushedRef.current) {
      lastPushedRef.current = nextSerialized;
      onSchemaChange({ ...schema, blocks: nextBlocks });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [vm]);

  return (
    <div className="flex h-full" data-testid="detail-config-panel">
      {!previewMode && (
        <aside className="w-52 shrink-0 border-r bg-gray-50">
          {TABS.map((t) => (
            <button
              key={t.id}
              onClick={() => setTab(t.id)}
              className={`flex w-full items-center gap-2 border-l-2 px-4 py-3 text-sm transition-colors ${
                tab === t.id
                  ? 'border-blue-500 bg-white font-medium text-blue-700'
                  : 'border-transparent text-gray-600 hover:bg-gray-100'
              }`}
              data-testid={`detail-tab-${t.id}`}
            >
              <span>{t.icon}</span>
              <span>{t.label}</span>
            </button>
          ))}
        </aside>
      )}

      <main className="flex-1 overflow-auto bg-white p-6">
        {tab === 'sections' && (
          <SectionsTab vm={vm} setVm={setVm} fields={fields} readonly={readonly} />
        )}
        {tab === 'actions' && (
          <ActionsTab vm={vm} setVm={setVm} capabilities={capabilities} readonly={readonly} />
        )}
      </main>
    </div>
  );
};

export default DetailConfigPanel;
