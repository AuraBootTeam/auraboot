/**
 * /semantic/lineage — IDA TRUST layer data-lineage explorer page.
 *
 * Layout: left panel = LineageNodePicker, right panel = LineageGraph.
 * URL supports a `?pid=` + `?type=` query parameter so deep-links work.
 */

import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';
import { LineageGraph } from '~/components/semantic/LineageGraph';
import { LineageNodePicker } from '~/components/semantic/LineageNodePicker';

export default function SemanticLineagePage() {
  const { t, locale } = useI18n();
  const [searchParams, setSearchParams] = useSearchParams();

  // Derive selected node from URL so links are shareable.
  const [selectedPid, setSelectedPid] = useState<string>(
    searchParams.get('pid') ?? '',
  );
  const [selectedType, setSelectedType] = useState<string>(
    searchParams.get('type') ?? '',
  );

  function handleSelect(pid: string, nodeType: string) {
    setSelectedPid(pid);
    setSelectedType(nodeType);
    setSearchParams({ pid, type: nodeType }, { replace: true });
  }

  return (
    <div
      data-testid="semantic-lineage-page"
      className="flex h-full flex-col overflow-hidden"
    >
      {/* Page header */}
      <header className="flex flex-shrink-0 items-center border-b border-gray-200 bg-white px-4 py-3">
        <h1 className="text-sm font-semibold text-gray-800">
          {t('semantic.lineage.title', undefined, 'Data Lineage')}
        </h1>
        {selectedPid && (
          <span className="ml-3 rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-500">
            {selectedType} · {selectedPid}
          </span>
        )}
      </header>

      {/* Body: picker + graph */}
      <div className="flex min-h-0 flex-1">
        {/* Left — node picker (fixed width) */}
        <div className="w-56 flex-shrink-0">
          <LineageNodePicker
            selectedPid={selectedPid || undefined}
            onChange={handleSelect}
            t={t}
            locale={locale}
          />
        </div>

        {/* Right — lineage graph */}
        <div className="flex-1 overflow-hidden p-4">
          {selectedPid ? (
            <LineageGraph nodePid={selectedPid} nodeType={selectedType} t={t} />
          ) : (
            <div
              data-testid="semantic-lineage-empty-state"
              className="flex h-full flex-col items-center justify-center text-sm text-gray-400"
            >
              <span className="mb-2 text-3xl text-gray-300">⬡</span>
              <span>{t('semantic.lineage.empty', undefined, 'Select a node to view its lineage')}</span>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
