import type { WizardState } from './VirtualModelWizard';

export function MetaInfoStep({
  state,
  setState,
}: {
  state: WizardState;
  setState: React.Dispatch<React.SetStateAction<WizardState>>;
}) {
  const meta = state.meta ?? {};
  const update = (patch: Partial<NonNullable<WizardState['meta']>>) =>
    setState({ ...state, meta: { ...meta, ...patch } });

  return (
    <div className="max-w-xl">
      <h2 className="mb-6 text-xl font-semibold">元信息</h2>
      <div className="space-y-4">
        <div>
          <label className="mb-1 block text-sm font-medium">
            Code <span className="text-red-500">*</span>
          </label>
          <input
            type="text"
            value={meta.code ?? ''}
            onChange={(e) =>
              update({ code: e.target.value.toLowerCase().replace(/[^a-z0-9_]/g, '_') })
            }
            placeholder="sales_summary"
            className="w-full rounded border px-3 py-2"
            data-testid="meta-code"
          />
          <p className="mt-1 text-xs text-gray-500">小写字母 / 数字 / 下划线。保存后不可改。</p>
        </div>
        <div>
          <label className="mb-1 block text-sm font-medium">
            显示名称 <span className="text-red-500">*</span>
          </label>
          <input
            type="text"
            value={meta.displayName ?? ''}
            onChange={(e) => update({ displayName: e.target.value })}
            placeholder="销售汇总视图"
            className="w-full rounded border px-3 py-2"
            data-testid="meta-displayname"
          />
        </div>
        <div>
          <label className="mb-1 block text-sm font-medium">描述</label>
          <textarea
            value={meta.description ?? ''}
            onChange={(e) => update({ description: e.target.value })}
            rows={3}
            className="w-full rounded border px-3 py-2"
            data-testid="meta-description"
          />
        </div>
      </div>
    </div>
  );
}
