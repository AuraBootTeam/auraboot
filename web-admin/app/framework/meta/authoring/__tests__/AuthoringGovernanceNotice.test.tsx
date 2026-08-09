import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AuthoringGovernanceNotice } from '../AuthoringGovernanceNotice';
import type { AuthoringGovernanceAction, AuthoringSession } from '../types';

describe('AuthoringGovernanceNotice', () => {
  it('lets only the owner withdraw a frozen review with an audited reason', () => {
    const onAction = vi.fn<(action: AuthoringGovernanceAction, reason: string) => void>();
    renderNotice(reviewSession(), { currentUserId: '1', onAction });

    expect(screen.getByText('评审中 · revision r7 已冻结')).toBeInTheDocument();
    expect(screen.queryByTestId('authoring-governance-approve')).not.toBeInTheDocument();
    expect(screen.getByTestId('authoring-governance-withdraw')).toBeDisabled();
    fireEvent.change(screen.getByTestId('authoring-governance-reason'), {
      target: { value: '补充异常订单筛选' },
    });
    fireEvent.click(screen.getByTestId('authoring-governance-withdraw'));

    expect(onAction).toHaveBeenCalledWith('withdraw', '补充异常订单筛选');
  });

  it('separates reviewer approval from mandatory-reason rejection', () => {
    const onAction = vi.fn<(action: AuthoringGovernanceAction, reason: string) => void>();
    renderNotice(reviewSession(), { currentUserId: '2', onAction });

    expect(screen.queryByTestId('authoring-governance-withdraw')).not.toBeInTheDocument();
    expect(screen.getByTestId('authoring-governance-reject')).toBeDisabled();
    fireEvent.click(screen.getByTestId('authoring-governance-approve'));
    expect(onAction).toHaveBeenCalledWith('approve', '');

    fireEvent.change(screen.getByTestId('authoring-governance-reason'), {
      target: { value: '默认筛选会隐藏异常订单' },
    });
    fireEvent.click(screen.getByTestId('authoring-governance-reject'));
    expect(onAction).toHaveBeenCalledWith('reject', '默认筛选会隐藏异常订单');
  });

  it('makes approval invalidation explicit before the owner reopens editing', () => {
    const onAction = vi.fn<(action: AuthoringGovernanceAction, reason: string) => void>();
    renderNotice(
      reviewSession({
        changeSetStatus: 'APPROVED',
        approvalState: 'APPROVED',
        publishState: 'READY',
      }),
      { currentUserId: '1', onAction },
    );

    expect(screen.getByText('revision r7 已批准')).toBeInTheDocument();
    expect(screen.getByText(/把本次批准标记为 STALE/)).toBeInTheDocument();
    fireEvent.change(screen.getByTestId('authoring-governance-reason'), {
      target: { value: '批准后发现遗漏说明' },
    });
    fireEvent.click(screen.getByTestId('authoring-governance-reopen'));
    expect(onAction).toHaveBeenCalledWith('reopen', '批准后发现遗漏说明');
  });
});

function renderNotice(
  session: AuthoringSession,
  overrides: {
    currentUserId: string;
    onAction: (action: AuthoringGovernanceAction, reason: string) => void;
  },
) {
  return render(
    <AuthoringGovernanceNotice
      session={session}
      currentUserId={overrides.currentUserId}
      canManage
      canReview
      pendingAction={null}
      onAction={overrides.onAction}
    />,
  );
}

function reviewSession(overrides: Partial<AuthoringSession> = {}): AuthoringSession {
  return {
    sessionPid: 'session-1',
    changeSetPid: 'changeset-1',
    pagePid: 'page-1',
    ownerUserId: 1,
    changeSetStatus: 'IN_REVIEW',
    workspaceMode: 'AUTHORING',
    state: 'READ_ONLY',
    revision: 7,
    riskLevel: 'L2',
    route: 'GUIDED_INLINE',
    publishPolicy: 'REQUIRED_REVIEW',
    validationState: 'VALID',
    approvalState: 'PENDING',
    publishState: 'DRAFT',
    manifestChecksum: 'registry-1',
    snapshot: {},
    interactionContext: {},
    expiresAt: '2026-08-09T12:00:00Z',
    ...overrides,
  };
}
