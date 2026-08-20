import { useEffect, useState } from 'react';
import {
  ArrowPathIcon,
  ArrowUturnLeftIcon,
  PauseCircleIcon,
  ShieldExclamationIcon,
} from '@heroicons/react/24/outline';

export type PromotionDriftDecision = 'REBASE' | 'BACKPORT' | 'KEEP_OVERRIDE' | 'OVERWRITE';

export interface PromotionDrift {
  unitPid: string;
  resourceType: string;
  resourcePid: string;
  targetResourcePid: string;
  pageKey: string;
  kind: 'TENANT_OVERRIDE' | 'PRODUCTION_CONTEXTUAL_HOTFIX';
  status: 'PENDING' | 'RESOLVED' | 'STALE' | 'APPLIED';
  fingerprint: string;
  decision: PromotionDriftDecision | null;
  executionStatus?: 'NONE' | 'PREPARED' | 'DEFERRED' | 'BACKPORTED' | 'APPLIED';
  executionPid?: string | null;
  applyReady: boolean;
  nextAction: string;
  activeReleasePid: string;
  channelVersion: number;
  overridePid: string | null;
  sourceVersion: number | null;
  targetVersion: number | null;
  options: PromotionDriftDecision[];
}

const OPTIONS: Array<{
  value: PromotionDriftDecision;
  title: string;
  description: string;
  icon: typeof ArrowPathIcon;
}> = [
  {
    value: 'REBASE',
    title: '重放本地变更',
    description: '服务端按稳定 ID 做三方合并；同路径冲突会失败关闭。',
    icon: ArrowPathIcon,
  },
  {
    value: 'BACKPORT',
    title: '回迁源环境',
    description: '立即创建目标到源的反向发布计划；当前晋升保持暂停。',
    icon: ArrowUturnLeftIcon,
  },
  {
    value: 'KEEP_OVERRIDE',
    title: '保留租户覆盖',
    description: '保留现场活动版本并持久延期；可改选其他处置恢复流程。',
    icon: PauseCircleIcon,
  },
  {
    value: 'OVERWRITE',
    title: '用发布版本覆盖',
    description: '显式废止目标活动覆盖；重新校验通过后才允许 apply。',
    icon: ShieldExclamationIcon,
  },
];

export function PromotionDriftDecisionPanel({
  drift,
  submitting = false,
  onResolve,
}: {
  drift: PromotionDrift;
  submitting?: boolean;
  onResolve: (input: {
    expectedFingerprint: string;
    decision: PromotionDriftDecision;
    reason: string;
  }) => Promise<void> | void;
}) {
  const [decision, setDecision] = useState<PromotionDriftDecision | ''>(drift.decision ?? '');
  const [reason, setReason] = useState('');

  useEffect(() => {
    setDecision(drift.decision ?? '');
    setReason('');
  }, [drift.fingerprint, drift.decision]);

  return (
    <section
      className="rounded-lg border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950 dark:border-amber-800 dark:bg-amber-950/30 dark:text-amber-100"
      aria-labelledby={`promotion-drift-${drift.unitPid}`}
      data-testid={`promotion-drift-${drift.unitPid}`}
    >
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h4 id={`promotion-drift-${drift.unitPid}`} className="font-semibold">
            目标环境存在现场配置漂移
          </h4>
          <p className="mt-1 text-xs leading-5 text-amber-800 dark:text-amber-300">
            {drift.pageKey} · {drift.kind} · active release {drift.activeReleasePid} · channel v
            {drift.channelVersion}
          </p>
        </div>
        <span className="rounded-full bg-amber-100 px-2 py-1 text-xs font-medium text-amber-800 dark:bg-amber-900 dark:text-amber-200">
          {drift.status}
        </span>
      </div>

      <div className="mt-3 grid gap-2 sm:grid-cols-2" role="radiogroup" aria-label="漂移处理方式">
        {OPTIONS.filter((option) => drift.options.includes(option.value)).map((option) => {
          const Icon = option.icon;
          const selected = decision === option.value;
          return (
            <label
              key={option.value}
              className={`cursor-pointer rounded-md border p-3 transition-colors ${
                selected
                  ? 'border-indigo-500 bg-white ring-1 ring-indigo-500 dark:bg-gray-900'
                  : 'border-amber-200 bg-amber-50/50 hover:border-amber-400 dark:border-amber-900 dark:bg-gray-900/40'
              }`}
            >
              <input
                type="radio"
                name={`promotion-drift-decision-${drift.unitPid}`}
                value={option.value}
                checked={selected}
                onChange={() => setDecision(option.value)}
                className="sr-only"
              />
              <span className="flex items-center gap-2 font-medium">
                <Icon className="h-4 w-4" />
                {option.title}
              </span>
              <span className="mt-1 block text-xs leading-5 text-amber-800 dark:text-amber-300">
                {option.description}
              </span>
            </label>
          );
        })}
      </div>

      <label className="mt-3 block text-xs font-medium" htmlFor={`promotion-drift-reason-${drift.unitPid}`}>
        决策原因（必填）
      </label>
      <textarea
        id={`promotion-drift-reason-${drift.unitPid}`}
        value={reason}
        onChange={(event) => setReason(event.target.value)}
        maxLength={500}
        rows={2}
        className="mt-1 w-full rounded-md border border-amber-300 bg-white px-3 py-2 text-sm text-gray-900 dark:border-amber-800 dark:bg-gray-900 dark:text-white"
        placeholder="说明为什么选择该命运，便于评审与审计"
      />

      <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
        <p className="text-xs text-amber-800 dark:text-amber-300">
          {drift.applyReady
            ? `处置已准备${drift.executionStatus ? `（${drift.executionStatus}）` : ''}；apply 前仍会重算指纹。`
            : '三方合并冲突或执行器未准备完成时，服务端会继续阻止 apply。'}
        </p>
        <button
          type="button"
          disabled={submitting || decision === '' || reason.trim().length === 0}
          onClick={() => {
            if (decision === '') return;
            onResolve({
              expectedFingerprint: drift.fingerprint,
              decision,
              reason: reason.trim(),
            });
          }}
          className="rounded-md bg-indigo-600 px-3 py-2 text-xs font-medium text-white hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-50"
          data-testid={`promotion-drift-submit-${drift.unitPid}`}
        >
          {submitting ? '记录中…' : drift.decision ? '更新决策' : '记录决策'}
        </button>
      </div>
    </section>
  );
}
