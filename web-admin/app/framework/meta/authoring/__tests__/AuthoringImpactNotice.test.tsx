import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AuthoringImpactNotice } from '../AuthoringImpactNotice';
import type { AuthoringSession } from '../types';

describe('AuthoringImpactNotice', () => {
  it('explains a fail-closed missing dependency without exposing business data', () => {
    render(
      <AuthoringImpactNotice
        session={session({
          impactState: 'FAILED',
          impact: {
            impactRunPid: 'impact-1',
            revision: 2,
            status: 'FAILED',
            dependencyChecksum: null,
            dependencies: [],
            failureCode: 'DEPENDENCY_MISSING',
            analyzedAt: '2026-08-09T12:00:00Z',
          },
        })}
      />,
    );

    expect(screen.getByText(/不能提交评审或发布/)).toBeInTheDocument();
    expect(screen.getByText(/引用的模型不存在或已停用/)).toBeInTheDocument();
    expect(screen.getByText('code: DEPENDENCY_MISSING')).toBeInTheDocument();
  });

  it('separates unknown analysis from stale dependency recovery', () => {
    const view = render(<AuthoringImpactNotice session={session()} />);

    expect(screen.getByText(/尚未完成影响分析/)).toBeInTheDocument();
    expect(screen.getByText(/VALID \+ KNOWN/)).toBeInTheDocument();

    view.rerender(<AuthoringImpactNotice session={session({ impactState: 'STALE' })} />);

    expect(screen.getByText(/当前校验与影响结果已失效/)).toBeInTheDocument();
    expect(screen.getByText(/保存为新 revision/)).toBeInTheDocument();
  });
});

function session(overrides: Partial<AuthoringSession> = {}): AuthoringSession {
  return {
    sessionPid: 'session-1',
    changeSetPid: 'changeset-1',
    pagePid: 'page-1',
    ownerUserId: 1,
    changeSetStatus: 'DRAFT',
    workspaceMode: 'AUTHORING',
    state: 'ACTIVE',
    revision: 2,
    riskLevel: 'L2',
    route: 'GUIDED_INLINE',
    publishPolicy: 'REQUIRED_REVIEW',
    validationState: 'VALID',
    impactState: 'UNKNOWN',
    approvalState: 'PENDING',
    publishState: 'DRAFT',
    manifestChecksum: 'registry-1',
    snapshot: {},
    interactionContext: {},
    expiresAt: '2026-08-09T18:00:00Z',
    ...overrides,
  };
}
