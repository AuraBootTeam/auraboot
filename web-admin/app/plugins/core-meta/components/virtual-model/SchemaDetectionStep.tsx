import { useState } from 'react';
import type { WizardState, DetectedField } from './VirtualModelWizard';

export function SchemaDetectionStep({
  state,
  setState,
}: {
  state: WizardState;
  setState: React.Dispatch<React.SetStateAction<WizardState>>;
}) {
  const [detecting, setDetecting] = useState(false);
  const [mode, setMode] = useState<'detected' | 'manual'>(
    state.detectedFields ? 'detected' : 'manual',
  );
  const [error, setError] = useState<string | undefined>();

  const fields = state.detectedFields ?? [];

  const runDetection = async () => {
    setDetecting(true);
    setError(undefined);
    try {
      const resp = await fetch('/api/meta/virtual-models/detect-schema', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sourceType: state.sourceType,
          sourceRef: state.sourceRef,
          endpointAdapter: state.endpointAdapter,
        }),
      });
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const body = await resp.json();
      const detected: DetectedField[] = body?.data?.fields ?? [];
      setState({ ...state, detectedFields: detected });
      setMode('detected');
    } catch (e) {
      setError(
        `自动检测不可用 (${e instanceof Error ? e.message : e})。请切换到手工模式录入字段。`,
      );
      setMode('manual');
    } finally {
      setDetecting(false);
    }
  };

  const updateField = (idx: number, patch: Partial<DetectedField>) => {
    const next = [...fields];
    next[idx] = { ...next[idx], ...patch };
    setState({ ...state, detectedFields: next });
  };

  const addField = () => {
    setState({
      ...state,
      detectedFields: [
        ...fields,
        { code: '', dataType: 'string', sortable: true, filterable: true },
      ],
    });
  };

  const removeField = (idx: number) => {
    const removedCode = fields[idx]?.code;
    const next = fields.filter((_, i) => i !== idx);
    setState({
      ...state,
      detectedFields: next,
      primaryKey: state.primaryKey === removedCode ? undefined : state.primaryKey,
    });
  };

  const setPrimary = (code: string) => {
    setState({ ...state, primaryKey: code });
  };

  return (
    <div>
      <h2 className="mb-2 text-xl font-semibold">字段检测</h2>
      <p className="mb-4 text-sm text-gray-500">
        至少勾选一个主键。Sortable / Filterable 是编辑态输入,保存时会归一化写入 capabilities 白名单。
      </p>
      <div className="mb-4 flex gap-3">
        <button
          onClick={runDetection}
          disabled={detecting}
          className="rounded bg-blue-600 px-4 py-2 text-sm text-white disabled:opacity-50"
          data-testid="detect-schema-btn"
        >
          {detecting ? '检测中...' : '🔍 自动检测 Schema'}
        </button>
        <button
          onClick={addField}
          className="rounded border px-4 py-2 text-sm"
          data-testid="add-field-btn"
        >
          + 手工添加字段
        </button>
      </div>

      {error && (
        <div
          className="mb-4 rounded bg-amber-50 p-3 text-sm text-amber-800"
          data-testid="detect-error"
        >
          {error}
        </div>
      )}

      {fields.length === 0 ? (
        <div className="rounded border border-dashed p-8 text-center text-sm text-gray-400">
          暂无字段。点击自动检测或手工添加。
        </div>
      ) : (
        <table className="w-full border text-sm">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-3 py-2 text-left">主键</th>
              <th className="px-3 py-2 text-left">字段 Code</th>
              <th className="px-3 py-2 text-left">类型</th>
              <th className="px-3 py-2 text-center">Sortable</th>
              <th className="px-3 py-2 text-center">Filterable</th>
              <th className="px-3 py-2"></th>
            </tr>
          </thead>
          <tbody>
            {fields.map((f, i) => (
              <tr key={i} className="border-t" data-testid={`field-row-${i}`}>
                <td className="px-3 py-2">
                  <input
                    type="radio"
                    name="primary"
                    checked={state.primaryKey === f.code}
                    onChange={() => setPrimary(f.code)}
                  />
                </td>
                <td className="px-3 py-2">
                  <input
                    type="text"
                    value={f.code}
                    onChange={(e) => updateField(i, { code: e.target.value })}
                    className="w-full rounded border px-2 py-1 text-sm"
                    disabled={mode === 'detected'}
                  />
                </td>
                <td className="px-3 py-2">
                  <select
                    value={f.dataType}
                    onChange={(e) => updateField(i, { dataType: e.target.value })}
                    className="rounded border px-2 py-1 text-sm"
                    disabled={mode === 'detected'}
                  >
                    {['string', 'integer', 'long', 'decimal', 'boolean', 'date', 'datetime', 'text', 'json'].map(
                      (t) => (
                        <option key={t} value={t}>
                          {t}
                        </option>
                      ),
                    )}
                  </select>
                </td>
                <td className="px-3 py-2 text-center">
                  <input
                    type="checkbox"
                    checked={!!f.sortable}
                    onChange={(e) => updateField(i, { sortable: e.target.checked })}
                  />
                </td>
                <td className="px-3 py-2 text-center">
                  <input
                    type="checkbox"
                    checked={!!f.filterable}
                    onChange={(e) => updateField(i, { filterable: e.target.checked })}
                  />
                </td>
                <td className="px-3 py-2 text-right">
                  <button onClick={() => removeField(i)} className="text-xs text-red-600">
                    删除
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
