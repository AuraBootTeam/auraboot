import { useEffect, useState } from 'react';
import type { WizardState } from './VirtualModelWizard';

export function SourceRefStep({
  state,
  setState,
}: {
  state: WizardState;
  setState: React.Dispatch<React.SetStateAction<WizardState>>;
}) {
  if (!state.sourceType) return <div className="text-gray-500">请先完成上一步</div>;

  return (
    <div>
      <h2 className="mb-6 text-xl font-semibold">配置数据源</h2>
      {state.sourceType === 'namedQuery' && <NamedQueryPicker state={state} setState={setState} />}
      {state.sourceType === 'endpoint' && <EndpointConfigForm state={state} setState={setState} />}
      {state.sourceType === 'sqlView' && <SqlViewInput state={state} setState={setState} />}
    </div>
  );
}

function NamedQueryPicker({
  state,
  setState,
}: {
  state: WizardState;
  setState: React.Dispatch<React.SetStateAction<WizardState>>;
}) {
  const [options, setOptions] = useState<Array<{ code: string; title?: string }>>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | undefined>();

  useEffect(() => {
    fetch('/api/meta/named-queries?status=published&pageSize=200')
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((body) => {
        const list = body?.data?.records ?? body?.data ?? body?.records ?? [];
        setOptions(list);
      })
      .catch((e) => setError(String(e.message ?? e)))
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="text-gray-500">加载已发布 Named Queries...</div>;
  if (error) {
    return (
      <div>
        <div className="mb-4 rounded bg-amber-50 p-3 text-sm text-amber-800">
          无法加载 Named Query 列表: {error}。你可以手工输入 Query Code。
        </div>
        <input
          type="text"
          value={state.sourceRef ?? ''}
          onChange={(e) => setState({ ...state, sourceRef: e.target.value })}
          placeholder="Query Code"
          className="w-full rounded border px-3 py-2"
          data-testid="sourceref-manual-input"
        />
      </div>
    );
  }
  return (
    <div>
      <label className="mb-2 block text-sm font-medium">选择 Named Query</label>
      <select
        value={state.sourceRef ?? ''}
        onChange={(e) => setState({ ...state, sourceRef: e.target.value })}
        className="w-full rounded border px-3 py-2"
        data-testid="sourceref-namedquery-select"
      >
        <option value="">-- 请选择 --</option>
        {options.map((o) => (
          <option key={o.code} value={o.code}>
            {o.title ? `${o.title} (${o.code})` : o.code}
          </option>
        ))}
      </select>
    </div>
  );
}

function EndpointConfigForm({
  state,
  setState,
}: {
  state: WizardState;
  setState: React.Dispatch<React.SetStateAction<WizardState>>;
}) {
  const adapter = state.endpointAdapter ?? {};
  const updateList = (patch: Partial<NonNullable<WizardState['endpointAdapter']>['list']>) =>
    setState({
      ...state,
      endpointAdapter: { ...adapter, list: { ...(adapter.list ?? {}), ...patch } },
    });
  const updateDetail = (patch: Partial<NonNullable<WizardState['endpointAdapter']>['detail']>) =>
    setState({
      ...state,
      endpointAdapter: { ...adapter, detail: { ...(adapter.detail ?? {}), ...patch } },
    });

  const listEndpoint = adapter.list?.endpoint;
  useEffect(() => {
    if (listEndpoint && state.sourceRef !== listEndpoint) {
      setState((s) => ({ ...s, sourceRef: listEndpoint }));
    }
  }, [listEndpoint]);

  return (
    <div className="space-y-6">
      <section className="rounded border p-4">
        <h3 className="mb-3 font-medium">List Channel</h3>
        <div className="grid grid-cols-2 gap-3">
          <LabeledInput
            label="Endpoint"
            value={adapter.list?.endpoint ?? ''}
            onChange={(v) => updateList({ endpoint: v })}
            required
            placeholder="https://api.example.com/orders/page"
            testid="endpoint-list-url"
          />
          <LabeledInput
            label="Method"
            value={adapter.list?.method ?? 'GET'}
            onChange={(v) => updateList({ method: v })}
            testid="endpoint-list-method"
          />
          <LabeledInput
            label="Items path"
            value={adapter.list?.responseItemsPath ?? 'data.items'}
            onChange={(v) => updateList({ responseItemsPath: v })}
            testid="endpoint-list-items"
          />
          <LabeledInput
            label="Total path"
            value={adapter.list?.responseTotalPath ?? 'data.total'}
            onChange={(v) => updateList({ responseTotalPath: v })}
            testid="endpoint-list-total"
          />
          <LabeledInput
            label="Page param"
            value={adapter.list?.pageParam ?? 'pageNum'}
            onChange={(v) => updateList({ pageParam: v })}
          />
          <LabeledInput
            label="Page size param"
            value={adapter.list?.pageSizeParam ?? 'pageSize'}
            onChange={(v) => updateList({ pageSizeParam: v })}
          />
          <LabeledInput
            label="Sort field param"
            value={adapter.list?.sortFieldParam ?? 'sortField'}
            onChange={(v) => updateList({ sortFieldParam: v })}
          />
          <LabeledInput
            label="Sort order param"
            value={adapter.list?.sortOrderParam ?? 'sortOrder'}
            onChange={(v) => updateList({ sortOrderParam: v })}
          />
        </div>
      </section>

      <section className="rounded border p-4">
        <h3 className="mb-3 font-medium">Detail Channel (可选)</h3>
        <div className="grid grid-cols-2 gap-3">
          <LabeledInput
            label="Endpoint"
            value={adapter.detail?.endpoint ?? ''}
            onChange={(v) => updateDetail({ endpoint: v })}
            placeholder="https://api.example.com/orders/{id}"
            testid="endpoint-detail-url"
          />
          <LabeledInput
            label="Method"
            value={adapter.detail?.method ?? 'GET'}
            onChange={(v) => updateDetail({ method: v })}
          />
          <LabeledInput
            label="Item path"
            value={adapter.detail?.responseItemPath ?? 'data'}
            onChange={(v) => updateDetail({ responseItemPath: v })}
          />
          <LabeledInput
            label="Path params (逗号分隔)"
            value={(adapter.detail?.pathParams ?? []).join(',')}
            onChange={(v) =>
              updateDetail({
                pathParams: v
                  .split(',')
                  .map((s) => s.trim())
                  .filter(Boolean),
              })
            }
            placeholder="id"
          />
        </div>
      </section>
    </div>
  );
}

function SqlViewInput({
  state,
  setState,
}: {
  state: WizardState;
  setState: React.Dispatch<React.SetStateAction<WizardState>>;
}) {
  const value = state.sourceRef ?? '';
  const valid = !value || /^[a-zA-Z_][a-zA-Z0-9_]{0,62}$/.test(value);
  return (
    <div>
      <label className="mb-2 block text-sm font-medium">View Name</label>
      <input
        type="text"
        value={value}
        onChange={(e) => setState({ ...state, sourceRef: e.target.value })}
        placeholder="v_sales_summary"
        className={`w-full rounded border px-3 py-2 ${valid ? '' : 'border-red-400'}`}
        data-testid="sourceref-sqlview-input"
      />
      {!valid && (
        <div className="mt-2 text-xs text-red-600">
          View name 必须以字母或下划线开头,仅允许字母数字下划线,长度 ≤ 63
        </div>
      )}
    </div>
  );
}

function LabeledInput({
  label,
  value,
  onChange,
  placeholder,
  required,
  testid,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  required?: boolean;
  testid?: string;
}) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium text-gray-600">
        {label}
        {required && <span className="ml-1 text-red-500">*</span>}
      </label>
      <input
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="w-full rounded border px-2 py-1.5 text-sm"
        data-testid={testid}
      />
    </div>
  );
}
