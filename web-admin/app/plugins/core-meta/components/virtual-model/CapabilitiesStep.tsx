import type { WizardState } from './VirtualModelWizard';

export function CapabilitiesStep({
  state,
  setState,
}: {
  state: WizardState;
  setState: React.Dispatch<React.SetStateAction<WizardState>>;
}) {
  const caps = state.capabilities ?? {
    list: true,
    detail: true,
    sort: true,
    filter: true,
    paginate: true,
    export: true,
  };
  const update = (key: keyof NonNullable<WizardState['capabilities']>, value: boolean) => {
    setState({ ...state, capabilities: { ...caps, [key]: value } });
  };

  return (
    <div>
      <h2 className="mb-2 text-xl font-semibold">能力声明</h2>
      <p className="mb-6 text-sm text-gray-500">
        一期虚拟 Model 仅支持读操作。create / update / delete 二期启用 (Virtual Writable Model)。
      </p>
      <div className="max-w-md space-y-3">
        <Toggle label="可列表 (list)" value={caps.list ?? true} onChange={(v) => update('list', v)} />
        <Toggle
          label="可详情 (detail)"
          value={caps.detail ?? true}
          onChange={(v) => update('detail', v)}
        />
        <Toggle label="可排序 (sort)" value={caps.sort ?? true} onChange={(v) => update('sort', v)} />
        <Toggle
          label="可筛选 (filter)"
          value={caps.filter ?? true}
          onChange={(v) => update('filter', v)}
        />
        <Toggle
          label="可分页 (paginate)"
          value={caps.paginate ?? true}
          onChange={(v) => update('paginate', v)}
        />
        <Toggle
          label="可导出 (export)"
          value={caps.export ?? true}
          onChange={(v) => update('export', v)}
        />
        <div className="mt-3 space-y-3 border-t pt-3">
          <Toggle label="可新增 (create)" value={false} disabled tooltip="一期虚拟 Model 只读" />
          <Toggle label="可编辑 (update)" value={false} disabled tooltip="一期虚拟 Model 只读" />
          <Toggle label="可删除 (delete)" value={false} disabled tooltip="一期虚拟 Model 只读" />
          <Toggle
            label="可批量删除 (bulkDelete)"
            value={false}
            disabled
            tooltip="一期虚拟 Model 只读"
          />
        </div>
      </div>
    </div>
  );
}

function Toggle({
  label,
  value,
  onChange,
  disabled,
  tooltip,
}: {
  label: string;
  value: boolean;
  onChange?: (v: boolean) => void;
  disabled?: boolean;
  tooltip?: string;
}) {
  return (
    <label
      className={`flex items-center justify-between ${disabled ? 'opacity-50' : ''}`}
      title={tooltip}
    >
      <span className="text-sm">{label}</span>
      <input
        type="checkbox"
        checked={value}
        onChange={(e) => !disabled && onChange?.(e.target.checked)}
        disabled={disabled}
      />
    </label>
  );
}
