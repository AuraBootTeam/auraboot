import type { WizardState, VirtualSourceType } from './VirtualModelWizard';

const TYPES: Array<{ key: VirtualSourceType; title: string; icon: string; description: string }> = [
  {
    key: 'namedQuery',
    title: 'Named Query',
    icon: '🔍',
    description: '引用已注册的 SQL 查询。适合复杂聚合或跨表关联。',
  },
  {
    key: 'endpoint',
    title: 'HTTP Endpoint',
    icon: '🌐',
    description: '外部 REST API。支持独立的 list 和 detail 通道。',
  },
  {
    key: 'sqlView',
    title: 'SQL View',
    icon: '👁️',
    description: '数据库视图。直接查询,支持排序/筛选白名单。',
  },
];

export function SourceTypeStep({
  state,
  setState,
}: {
  state: WizardState;
  setState: React.Dispatch<React.SetStateAction<WizardState>>;
}) {
  return (
    <div>
      <h2 className="mb-2 text-xl font-semibold">选择数据源类型</h2>
      <p className="mb-6 text-sm text-gray-500">
        虚拟 Model 的数据来源。一期不支持 projection (跨 model 字段映射),留到后续版本。
      </p>
      <div className="grid grid-cols-3 gap-4">
        {TYPES.map((t) => (
          <button
            key={t.key}
            onClick={() =>
              setState({
                ...state,
                sourceType: t.key,
                sourceRef: undefined,
                endpointAdapter: undefined,
                detectedFields: undefined,
              })
            }
            data-testid={`sourcetype-card-${t.key}`}
            className={`rounded-lg border-2 p-5 text-left transition-all hover:border-blue-300 ${
              state.sourceType === t.key
                ? 'border-blue-500 bg-blue-50'
                : 'border-gray-200 bg-white'
            }`}
          >
            <div className="mb-2 text-3xl">{t.icon}</div>
            <div className="font-medium text-gray-900">{t.title}</div>
            <div className="mt-2 text-xs leading-relaxed text-gray-500">{t.description}</div>
          </button>
        ))}
      </div>
    </div>
  );
}
