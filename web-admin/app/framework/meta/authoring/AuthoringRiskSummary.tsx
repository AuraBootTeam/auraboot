import React from 'react';
import { AlertTriangle, ShieldCheck } from 'lucide-react';
import type { AuthoringSession } from './types';

export function AuthoringRiskSummary({ session }: { session: AuthoringSession }) {
  const copy = riskCopy(session.riskLevel, session.publishPolicy);
  const elevated = session.riskLevel === 'L2' || session.riskLevel === 'L3';
  const Icon = elevated ? AlertTriangle : ShieldCheck;

  return (
    <section
      className={`rounded-md border px-3 py-2 text-sm ${
        elevated
          ? 'border-amber-300 bg-amber-50 text-amber-950'
          : 'border-sky-200 bg-sky-50 text-sky-950'
      }`}
      aria-label="ChangeSet 风险与发布策略"
      data-testid="authoring-risk-summary"
      role="note"
    >
      <div className="flex items-start gap-2">
        <Icon className="mt-0.5 h-4 w-4 shrink-0" />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2 font-semibold">
            <span className={riskBadge(session.riskLevel)}>{session.riskLevel}</span>
            <span>{copy.title}</span>
            <span className="font-normal text-current/75">· {publishLabel(session.publishPolicy)}</span>
          </div>
          <p className="mt-1 text-xs leading-5">{copy.description}</p>
          <p className="mt-1 text-xs leading-5 text-current/75">
            ChangeSet 始终按全部变更项中的最高风险治理；新增低风险项不会降低既有等级。
          </p>
        </div>
      </div>
    </section>
  );
}

function riskCopy(riskLevel: string, publishPolicy: string) {
  if (riskLevel === 'L3') {
    return {
      title: '专业变更，必须批准后发布',
      description:
        '数据源、命令或业务语义等任一 L3 变更会使整个 ChangeSet 失去低风险直发资格。只有依赖独立时，才能在专业工作台拆分后分别治理。',
    };
  }
  if (riskLevel === 'L2') {
    return {
      title: '页面级影响，强制他人评审',
      description:
        '默认筛选、默认排序、关键动作显隐或关键格式会改变用户看到或能够执行的内容；即使只影响当前页面，也不能直发。',
    };
  }
  if (riskLevel === 'L1' || publishPolicy === 'DEFAULT_REVIEW') {
    return {
      title: '普通展示变更，默认进入评审',
      description: '标题、图标、分页或顺序等展示调整保留完整 diff，并按默认评审策略提交。',
    };
  }
  return {
    title: '低风险局部变更',
    description: '当前变更只涉及低风险局部展示；提交、校验和发布权限仍会由服务端重新判定。',
  };
}

function publishLabel(policy: string): string {
  return (
    {
      DIRECT_ALLOWED: '校验后可直发',
      DEFAULT_REVIEW: '默认评审',
      REQUIRED_REVIEW: '强制评审',
      STUDIO_APPROVAL: '专业批准',
      DENIED: '禁止发布',
    }[policy] ?? policy
  );
}

function riskBadge(riskLevel: string): string {
  return riskLevel === 'L3'
    ? 'rounded bg-red-100 px-1.5 py-0.5 text-xs font-bold text-red-800'
    : riskLevel === 'L2'
      ? 'rounded bg-amber-100 px-1.5 py-0.5 text-xs font-bold text-amber-800'
      : 'rounded bg-sky-100 px-1.5 py-0.5 text-xs font-bold text-sky-800';
}
