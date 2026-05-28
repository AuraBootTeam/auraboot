/**
 * Bespoke property editors plugged through the SDK G2 injection point
 * (`FlowNodeDefinition.propertyEditor`). Each editor receives the SDK's
 * `NodePropertyEditorProps` and merges patches into `node.data.config`.
 */

import type {
  NodePropertyEditorProps,
} from '~/plugins/core-designer/components/flow-designer-sdk';
import type { EdgePropertyEditorProps } from '~/plugins/core-designer/components/flow-designer-sdk';

const fieldClass =
  'w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none';

function readString(config: Record<string, unknown>, key: string): string {
  const v = config[key];
  return typeof v === 'string' ? v : '';
}

export function ServiceTaskEditor({ config, onChange, readOnly }: NodePropertyEditorProps) {
  return (
    <div className="space-y-3">
      <div>
        <label className="mb-1 block text-xs font-medium text-gray-700">name</label>
        <input
          data-testid="svc-task-name"
          className={fieldClass}
          disabled={readOnly}
          value={readString(config, 'name')}
          onChange={(e) => onChange({ name: e.target.value })}
        />
      </div>
      <div>
        <label className="mb-1 block text-xs font-medium text-gray-700">implementation</label>
        <input
          data-testid="svc-task-impl"
          className={fieldClass}
          disabled={readOnly}
          placeholder="bean:emailSender / ${expr}"
          value={readString(config, 'implementation')}
          onChange={(e) => onChange({ implementation: e.target.value })}
        />
      </div>
    </div>
  );
}

export function ExclusiveGatewayEditor({ config, onChange, readOnly }: NodePropertyEditorProps) {
  return (
    <div className="space-y-3">
      <div>
        <label className="mb-1 block text-xs font-medium text-gray-700">name</label>
        <input
          data-testid="gw-name"
          className={fieldClass}
          disabled={readOnly}
          value={readString(config, 'name')}
          onChange={(e) => onChange({ name: e.target.value })}
        />
      </div>
      <div>
        <label className="mb-1 block text-xs font-medium text-gray-700">defaultFlow (edge id)</label>
        <input
          data-testid="gw-default-flow"
          className={fieldClass}
          disabled={readOnly}
          value={readString(config, 'defaultFlow')}
          onChange={(e) => onChange({ defaultFlow: e.target.value })}
        />
      </div>
    </div>
  );
}

export function StartEventEditor({ config, onChange, readOnly }: NodePropertyEditorProps) {
  return (
    <div className="space-y-3">
      <div>
        <label className="mb-1 block text-xs font-medium text-gray-700">name</label>
        <input
          data-testid="start-name"
          className={fieldClass}
          disabled={readOnly}
          value={readString(config, 'name')}
          onChange={(e) => onChange({ name: e.target.value })}
        />
      </div>
    </div>
  );
}

export function EndEventEditor({ config, onChange, readOnly }: NodePropertyEditorProps) {
  return (
    <div className="space-y-3">
      <div>
        <label className="mb-1 block text-xs font-medium text-gray-700">name</label>
        <input
          data-testid="end-name"
          className={fieldClass}
          disabled={readOnly}
          value={readString(config, 'name')}
          onChange={(e) => onChange({ name: e.target.value })}
        />
      </div>
    </div>
  );
}

export function BpmConditionalEdgeEditor({ data, onChange, readOnly }: EdgePropertyEditorProps) {
  const condition = (data as any)?.condition?.content ?? '';
  const isDefault = !!(data as any)?.isDefault;
  return (
    <div className="space-y-3">
      <div>
        <label className="mb-1 block text-xs font-medium text-gray-700">label</label>
        <input
          data-testid="edge-label"
          className={fieldClass}
          disabled={readOnly}
          value={data.label ?? ''}
          onChange={(e) => onChange({ label: e.target.value })}
        />
      </div>
      <div>
        <label className="mb-1 block text-xs font-medium text-gray-700">condition (expression)</label>
        <input
          data-testid="edge-cond"
          className={fieldClass}
          disabled={readOnly || isDefault}
          placeholder="${amount > 1000}"
          value={condition}
          onChange={(e) =>
            onChange({ condition: { type: 'expression', content: e.target.value } } as any)
          }
        />
      </div>
      <label className="flex items-center gap-2 text-sm text-gray-700">
        <input
          data-testid="edge-default"
          type="checkbox"
          checked={isDefault}
          disabled={readOnly}
          onChange={(e) => onChange({ isDefault: e.target.checked } as any)}
        />
        default flow
      </label>
    </div>
  );
}
