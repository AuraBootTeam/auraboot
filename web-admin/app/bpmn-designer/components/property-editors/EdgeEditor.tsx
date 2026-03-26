/**
 * Property editor for sequence flow edges.
 */

import type { BPMNEdgeData } from '~/bpmn-designer/types';

export function EdgeEditor({
  edgeId,
  data,
  onUpdate,
}: {
  edgeId: string;
  data?: BPMNEdgeData;
  onUpdate: (edgeId: string, data: Partial<BPMNEdgeData>) => void;
}) {
  return (
    <>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">连线标签</label>
        <input
          type="text"
          value={data?.label || ''}
          onChange={(e) => onUpdate(edgeId, { label: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">条件表达式</label>
        <textarea
          value={data?.condition?.content || ''}
          onChange={(e) =>
            onUpdate(edgeId, {
              condition: {
                type: 'expression',
                content: e.target.value,
              },
            })
          }
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          rows={3}
          placeholder="例如: ${amount > 1000}"
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">规则引用</label>
        <input
          type="text"
          value={data?.condition?.ruleCode || ''}
          onChange={(e) =>
            onUpdate(edgeId, {
              condition: {
                ...data?.condition,
                type: data?.condition?.type || 'expression',
                content: data?.condition?.content || '',
                ruleCode: e.target.value || undefined,
              },
            })
          }
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          placeholder="BPM规则编码 (可选)"
        />
        <p className="mt-1 text-xs text-gray-500">关联BPM规则引擎中的规则</p>
      </div>

      <div className="mb-4">
        <label className="flex items-center">
          <input
            type="checkbox"
            checked={data?.isDefault || false}
            onChange={(e) => onUpdate(edgeId, { isDefault: e.target.checked })}
            className="mr-2"
          />
          <span className="text-sm font-medium text-gray-700">默认流向</span>
        </label>
      </div>
    </>
  );
}
