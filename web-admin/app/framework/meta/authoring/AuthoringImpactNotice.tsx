import React from 'react';
import { Network, TriangleAlert } from 'lucide-react';
import type { AuthoringSession } from './types';

export function AuthoringImpactNotice({ session }: { session: AuthoringSession }) {
  if (session.revision <= 1 || session.impactState === 'KNOWN') return null;
  const failed = session.impactState === 'FAILED';
  const stale = session.impactState === 'STALE';
  const Icon = failed || stale ? TriangleAlert : Network;
  const tone = failed || stale
    ? 'border-amber-300 bg-amber-50 text-amber-950'
    : 'border-blue-200 bg-blue-50 text-blue-950';

  return (
    <section
      className={`rounded-md border px-3 py-3 text-sm ${tone}`}
      aria-label="ChangeSet 影响分析状态"
      data-testid="authoring-impact-notice"
      role={failed || stale ? 'alert' : 'status'}
    >
      <div className="flex items-start gap-2">
        <Icon className="mt-0.5 h-4 w-4 shrink-0" />
        <div className="min-w-0">
          <div className="font-semibold">{impactTitle(session)}</div>
          <p className="mt-1 text-xs leading-5">{impactGuidance(session)}</p>
          {failed && session.impact?.failureCode ? (
            <div className="mt-2 font-mono text-xs">code: {session.impact.failureCode}</div>
          ) : null}
        </div>
      </div>
    </section>
  );
}

function impactTitle(session: AuthoringSession): string {
  if (session.impactState === 'FAILED') return '影响分析失败，不能提交评审或发布';
  if (session.impactState === 'STALE') return '依赖已变化，当前校验与影响结果已失效';
  return '当前 revision 尚未完成影响分析';
}

function impactGuidance(session: AuthoringSession): string {
  if (session.impactState === 'FAILED') {
    return session.impact?.failureCode === 'DEPENDENCY_MISSING'
      ? '引用的模型不存在或已停用。请修复数据源并保存新 revision；临时故障或超时可重试分析。'
      : '未得到完整影响范围，系统保持 fail-closed。可重试；持续失败时进入专业工作台检查依赖。';
  }
  if (session.impactState === 'STALE') {
    return '请刷新并重新建立基线，保存为新 revision 后再校验；旧批准和发布资格不会沿用。';
  }
  return '先执行“校验与分析”；只有服务端返回 VALID + KNOWN 后，“提交评审”才可用。';
}
