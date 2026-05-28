/**
 * B2b batch3 — final BPMN property editor batch:
 *   - CallActivityEditor (G2 NodePropertyEditorProps)
 *   - BpmSequenceFlowEdgeEditor (G1 EdgePropertyEditorProps — edge editor)
 *
 * Plus a thin internal helper (VariableMappingTable) extracted from the legacy
 * CallActivityEditor file so it can be unit-tested in isolation if needed.
 *
 * After this batch the SDK editor surface covers every legacy property editor
 * that bpmn-designer/components/property-editors/ exposed.
 */

import { useState } from 'react';
import {
  type NodePropertyEditorProps,
} from '~/plugins/core-designer/components/flow-designer-sdk';
import type { EdgePropertyEditorProps } from '~/plugins/core-designer/components/flow-designer-sdk';
import type {
  CallActivityConfig,
  BPMNEdgeData,
  ConditionExpression,
} from '~/plugins/core-designer/components/bpmn-designer/types';
import { useI18n } from '~/contexts/I18nContext';
import { ProcessPicker } from './pickers/ProcessPicker';
// ConditionExpressionBody was ported in batch2.
import { ConditionExpressionBody } from './BpmSdkBatch2Editors';

// ===========================================================================
// VariableMappingTable — private helper for CallActivityEditor
// ===========================================================================

interface VariableMappingRow {
  source: string;
  target: string;
}

function VariableMappingTable({
  label,
  mappings,
  onChange,
  sourcePlaceholder,
  targetPlaceholder,
  testIdPrefix,
}: {
  label: string;
  mappings: Record<string, string>;
  onChange: (mappings: Record<string, string>) => void;
  sourcePlaceholder: string;
  targetPlaceholder: string;
  testIdPrefix: string;
}) {
  const { t } = useI18n();
  const rows: VariableMappingRow[] = Object.entries(mappings).map(([source, target]) => ({
    source,
    target,
  }));

  const updateRow = (index: number, field: 'source' | 'target', value: string) => {
    const updated = [...rows];
    updated[index] = { ...updated[index], [field]: value };
    const result: Record<string, string> = {};
    updated.forEach((r) => {
      if (r.source) result[r.source] = r.target;
    });
    onChange(result);
  };

  const addRow = () => {
    const result = { ...mappings, '': '' };
    onChange(result);
  };

  const removeRow = (index: number) => {
    const updated = [...rows];
    updated.splice(index, 1);
    const result: Record<string, string> = {};
    updated.forEach((r) => {
      if (r.source) result[r.source] = r.target;
    });
    onChange(result);
  };

  return (
    <div className="mb-3">
      <label className="mb-1 block text-xs font-medium text-gray-600">{label}</label>
      {rows.length > 0 && (
        <div className="space-y-1">
          {rows.map((row, index) => (
            <div
              key={index}
              className="flex items-center gap-1"
              data-testid={`${testIdPrefix}-row-${index}`}
            >
              <input
                type="text"
                value={row.source}
                onChange={(e) => updateRow(index, 'source', e.target.value)}
                className="w-[40%] rounded border border-gray-300 px-1.5 py-1 text-xs"
                placeholder={sourcePlaceholder}
              />
              <span className="text-xs text-gray-400">&rarr;</span>
              <input
                type="text"
                value={row.target}
                onChange={(e) => updateRow(index, 'target', e.target.value)}
                className="w-[40%] rounded border border-gray-300 px-1.5 py-1 text-xs"
                placeholder={targetPlaceholder}
              />
              <button
                type="button"
                onClick={() => removeRow(index)}
                className="text-xs text-red-400 hover:text-red-600"
              >
                &times;
              </button>
            </div>
          ))}
        </div>
      )}
      <button
        type="button"
        onClick={addRow}
        className="mt-1 text-xs text-blue-600 hover:text-blue-800"
        data-testid={`${testIdPrefix}-add`}
      >
        {t('bpmn.callactivity.addMapping')}
      </button>
    </div>
  );
}

// ===========================================================================
// CallActivityEditor — G2 NodePropertyEditorProps adapter
// ===========================================================================
//
// Legacy contract:  ({ config, onChange(full config) })
// G2 contract:      ({ nodeId, config, onChange(patch) })
//
// We translate by spreading the existing config alongside the patch — same
// "patch into full config" technique as batch1/2 editors. ProcessPicker is
// embedded directly (real remote data). JSON for CallActivityConfig is
// unchanged from the legacy editor.
// ===========================================================================

export function CallActivityEditor({ config, onChange, readOnly }: NodePropertyEditorProps) {
  const { t } = useI18n();
  const c = (config ?? {}) as unknown as CallActivityConfig;
  const [mappingExpanded, setMappingExpanded] = useState(
    Boolean(
      (c.inputMappings && Object.keys(c.inputMappings).length > 0) ||
        (c.outputMappings && Object.keys(c.outputMappings).length > 0),
    ),
  );

  // patch-style writer — translates legacy "full config" semantics into the G2
  // partial patch contract without changing on-disk JSON.
  const patch = (next: Partial<CallActivityConfig>) =>
    onChange(next as Record<string, unknown>);

  return (
    <>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.common.description')}
        </label>
        <textarea
          value={c.description || ''}
          onChange={(e) => patch({ description: e.target.value })}
          disabled={readOnly}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          rows={2}
          data-testid="bpm-sdk-callactivity-description"
        />
      </div>

      <div className="mb-4" data-testid="bpm-sdk-callactivity-process-key">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.callactivity.calledProcess')}
        </label>
        <ProcessPicker
          value={c.calledProcessKey || ''}
          onChange={(processKey) => patch({ calledProcessKey: processKey })}
          placeholder={t('bpmn.callactivity.selectProcess')}
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.callactivity.calledProcessVersion')}
        </label>
        <select
          value={c.calledProcessVersion || 'latest'}
          onChange={(e) =>
            patch({ calledProcessVersion: e.target.value as CallActivityConfig['calledProcessVersion'] })
          }
          disabled={readOnly}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          data-testid="bpm-sdk-callactivity-version-mode"
        >
          <option value="latest">{t('bpmn.callactivity.versionLatest')}</option>
          <option value="fixed">{t('bpmn.callactivity.versionFixed')}</option>
        </select>
        {c.calledProcessVersion === 'fixed' && (
          <input
            type="text"
            value={c.calledProcessVersion || ''}
            onChange={(e) =>
              patch({ calledProcessVersion: e.target.value as CallActivityConfig['calledProcessVersion'] })
            }
            disabled={readOnly}
            className="mt-1 w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
            placeholder={t('bpmn.callactivity.versionPlaceholder')}
          />
        )}
      </div>

      {/* Variable Mapping */}
      <div className="mb-4 rounded-md border border-gray-200">
        <button
          type="button"
          onClick={() => setMappingExpanded(!mappingExpanded)}
          className="flex w-full items-center justify-between px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
          data-testid="bpm-sdk-callactivity-mapping-toggle"
        >
          <span>{t('bpmn.callactivity.variableMapping')}</span>
          <span className="text-gray-400">{mappingExpanded ? '▾' : '▸'}</span>
        </button>
        {mappingExpanded && (
          <div className="px-3 pb-3">
            <VariableMappingTable
              label={t('bpmn.callactivity.inputMapping')}
              mappings={c.inputMappings || {}}
              onChange={(inputMappings) => patch({ inputMappings })}
              sourcePlaceholder={t('bpmn.callactivity.parentVariable')}
              targetPlaceholder={t('bpmn.callactivity.childVariable')}
              testIdPrefix="callactivity-input"
            />
            <VariableMappingTable
              label={t('bpmn.callactivity.outputMapping')}
              mappings={c.outputMappings || {}}
              onChange={(outputMappings) => patch({ outputMappings })}
              sourcePlaceholder={t('bpmn.callactivity.childVariable')}
              targetPlaceholder={t('bpmn.callactivity.parentVariable')}
              testIdPrefix="callactivity-output"
            />
          </div>
        )}
      </div>
    </>
  );
}

// ===========================================================================
// BpmSequenceFlowEdgeEditor — G1 EdgePropertyEditorProps adapter
// ===========================================================================
//
// Legacy bpmn-designer EdgeEditor signature:
//   ({ edgeId, data, onUpdate(edgeId, patch) })
// SDK G1 EdgePropertyEditorProps signature:
//   ({ edgeId, data, onChange(patch) })
//
// We adapt by treating `onChange` as the partial-patch writer (data shape is
// the same: BPMNEdgeData). ConditionExpressionBody (batch2 port) is embedded
// directly so the edge editor surfaces the full simple/advanced expression UX.
// ===========================================================================

export function BpmSequenceFlowEdgeEditor({
  edgeId,
  data,
  onChange,
  readOnly,
}: EdgePropertyEditorProps) {
  const { t } = useI18n();
  const d = data as BPMNEdgeData;
  return (
    <>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.prop.edge.label')}
        </label>
        <input
          type="text"
          value={d?.label || ''}
          onChange={(e) => onChange({ label: e.target.value })}
          disabled={readOnly}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          data-testid="bpm-sdk-edge-label-input"
        />
      </div>

      <ConditionExpressionBody
        key={edgeId}
        condition={d?.condition}
        onChange={(condition: ConditionExpression) => onChange({ condition })}
      />

      <div className="mb-4">
        <label className="flex items-center">
          <input
            type="checkbox"
            checked={d?.isDefault || false}
            onChange={(e) => onChange({ isDefault: e.target.checked })}
            disabled={readOnly}
            className="mr-2"
            data-testid="bpm-sdk-edge-default-checkbox"
          />
          <span className="text-sm font-medium text-gray-700">
            {t('bpmn.prop.edge.defaultFlow')}
          </span>
        </label>
        <p className="mt-1 text-xs text-gray-400">{t('bpmn.prop.edge.defaultFlowHint')}</p>
      </div>
    </>
  );
}
