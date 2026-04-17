/**
 * BpmStatusSection - Task 11 of the OSS BPM closure spec.
 *
 * Renders the status slot of a {@link BpmPanelBlock}. Given a resolved
 * {@link BpmInstanceForRecord} (or `null`), it surfaces:
 *   - a colored status badge with i18n label (running / approved / rejected /
 *     withdrawn / suspended, plus a neutral fallback for unrecognised values),
 *   - the list of current nodes with assignee (if the instance is still
 *     running),
 *   - instanceId + processDefinitionId as small print for debug/support.
 *
 * When `instance === null` the section renders an empty state with guidance
 * text but NO call-to-action button - the "start process" affordance lives in
 * the Operations section (Task 13) so we do not duplicate it here.
 *
 * This component intentionally does not invent backend fields: the
 * per-task prompt mentioned `startTime` / `endTime`, but
 * `BpmInstanceForRecord` does not carry those values, so they are omitted.
 *
 * @since BPM closure spec 1 (Task 11)
 */
import type { BpmInstanceForRecord } from '~/plugins/core-bpm/services/bpmWorkbenchService';

/**
 * Status values for which we ship an explicit colour + label fallback. Any
 * value not in this map degrades to a neutral badge showing the raw string -
 * we never silently remap an unknown status to `running` or any other known
 * value.
 */
const KNOWN_STATUS_STYLES: Record<string, { badge: string; fallback: string }> = {
  running: { badge: 'bg-blue-100 text-blue-800', fallback: '运行中' },
  approved: { badge: 'bg-green-100 text-green-800', fallback: '已通过' },
  rejected: { badge: 'bg-red-100 text-red-800', fallback: '已驳回' },
  withdrawn: { badge: 'bg-gray-100 text-gray-700', fallback: '已撤回' },
  suspended: { badge: 'bg-yellow-100 text-yellow-800', fallback: '已挂起' },
};

/** Neutral badge style used when `status` is not in {@link KNOWN_STATUS_STYLES}. */
const UNKNOWN_STATUS_STYLE = 'bg-gray-100 text-gray-600';

/**
 * Translator signature compatible with {@link useI18n}'s `t`. Kept as a prop
 * rather than imported via hook so this component stays trivially unit-
 * testable without having to wrap every test render in an {@link I18nProvider}.
 */
type Translator = (
  key: string,
  params?: Record<string, unknown>,
  fallback?: string,
) => string;

export interface BpmStatusSectionProps {
  instance: BpmInstanceForRecord | null;
  t: Translator;
}

export function BpmStatusSection({ instance, t }: BpmStatusSectionProps) {
  if (instance === null) {
    return (
      <div
        data-testid="bpm-status-empty"
        className="rounded border border-gray-200 bg-white p-4 text-sm text-gray-600"
      >
        <p className="font-medium text-gray-800">
          {t('bpm.status.empty.title', undefined, '暂无审批流程')}
        </p>
        <p className="mt-1 text-xs text-gray-500">
          {t('bpm.status.empty.hint', undefined, '点击下方"启动流程"开始审批')}
        </p>
      </div>
    );
  }

  const style = KNOWN_STATUS_STYLES[instance.status];
  const isKnown = style !== undefined;
  const badgeClass = isKnown ? style.badge : UNKNOWN_STATUS_STYLE;
  // For unknown status we render the raw backend string verbatim so nothing is
  // fabricated. For known values we still defer to i18n with a Chinese
  // fallback (never hardcode the label in JSX).
  const label = isKnown
    ? t(`bpm.status.label.${instance.status}`, undefined, style.fallback)
    : instance.status;

  return (
    <div
      data-testid="bpm-status-card"
      className="rounded border border-gray-200 bg-white p-4 text-sm text-gray-700"
    >
      <div className="flex items-center gap-2">
        <span className="text-xs font-medium text-gray-500">
          {t('bpm.status.label', undefined, '状态')}
        </span>
        <span
          data-testid="bpm-status-badge"
          data-status={instance.status}
          className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${badgeClass}`}
        >
          {label}
        </span>
      </div>

      {instance.currentNodes.length > 0 && (
        <div className="mt-3" data-testid="bpm-status-current-nodes">
          <div className="text-xs font-medium text-gray-500">
            {t('bpm.status.currentNode', undefined, '当前节点')}
          </div>
          <ul className="mt-1 space-y-1">
            {instance.currentNodes.map((node) => {
              const name = node.name ?? node.nodeId;
              const assigneeText = node.assignee ? ` · ${node.assignee}` : '';
              return (
                <li
                  key={node.nodeId}
                  data-testid={`bpm-status-current-node-${node.nodeId}`}
                  className="text-sm text-gray-700"
                >
                  {name}
                  {assigneeText}
                </li>
              );
            })}
          </ul>
        </div>
      )}

      <div className="mt-3 text-xs text-gray-400">
        <span data-testid="bpm-status-instance-id">
          {t('bpm.status.instanceId', undefined, '实例 ID')}: {instance.instanceId}
        </span>
        <span className="ml-2">
          {t('bpm.status.processDefinitionId', undefined, '流程定义')}:{' '}
          {instance.processDefinitionId}
        </span>
      </div>
    </div>
  );
}
