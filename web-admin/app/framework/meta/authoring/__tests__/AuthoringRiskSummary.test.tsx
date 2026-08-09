import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AuthoringRiskSummary } from '../AuthoringRiskSummary';
import type { AuthoringSession } from '../types';

describe('AuthoringRiskSummary', () => {
  it('explains why page-local L2 changes still require another reviewer', () => {
    render(<AuthoringRiskSummary session={session('L2', 'REQUIRED_REVIEW')} />);

    expect(screen.getByText('页面级影响，强制他人评审')).toBeInTheDocument();
    expect(screen.getByText(/默认筛选、默认排序、关键动作显隐或关键格式/)).toBeInTheDocument();
    expect(screen.getByText(/即使只影响当前页面，也不能直发/)).toBeInTheDocument();
  });

  it('makes whole-ChangeSet L3 escalation and governed split explicit', () => {
    render(<AuthoringRiskSummary session={session('L3', 'STUDIO_APPROVAL')} />);

    expect(screen.getByText('专业变更，必须批准后发布')).toBeInTheDocument();
    expect(screen.getByText(/整个 ChangeSet 失去低风险直发资格/)).toBeInTheDocument();
    expect(screen.getByText(/只有依赖独立时，才能在专业工作台拆分/)).toBeInTheDocument();
  });

  it('does not imply that a later low-risk item can downgrade governance', () => {
    render(<AuthoringRiskSummary session={session('L1', 'DEFAULT_REVIEW')} />);

    expect(screen.getByText(/新增低风险项不会降低既有等级/)).toBeInTheDocument();
    expect(screen.getByText('普通展示变更，默认进入评审')).toBeInTheDocument();
  });
});

function session(riskLevel: string, publishPolicy: string): AuthoringSession {
  return {
    sessionPid: 'session-1',
    changeSetPid: 'changeset-1',
    pagePid: 'page-1',
    ownerUserId: 1,
    changeSetStatus: 'DRAFT',
    workspaceMode: 'AUTHORING',
    state: 'ACTIVE',
    revision: 3,
    riskLevel,
    route: riskLevel === 'L3' ? 'HANDOFF_STUDIO' : 'GUIDED_INLINE',
    publishPolicy,
    validationState: 'UNVALIDATED',
    approvalState: 'PENDING',
    publishState: 'DRAFT',
    manifestChecksum: 'registry-1',
    snapshot: {},
    interactionContext: {},
    expiresAt: '2026-08-09T18:00:00Z',
  };
}
