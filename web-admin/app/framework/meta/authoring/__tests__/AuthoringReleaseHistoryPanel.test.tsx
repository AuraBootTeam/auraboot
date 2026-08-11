import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthoringReleaseHistoryPanel } from '../AuthoringReleaseHistoryPanel';
import { loadAuthoringReleaseHistory, rollbackAuthoringRelease } from '../authoringService';
import type { AuthoringReleaseHistory } from '../types';

vi.mock('../authoringService', () => ({
  loadAuthoringReleaseHistory: vi.fn(),
  rollbackAuthoringRelease: vi.fn(),
}));

describe('AuthoringReleaseHistoryPanel', () => {
  beforeEach(() => {
    vi.mocked(loadAuthoringReleaseHistory).mockReset();
    vi.mocked(rollbackAuthoringRelease).mockReset();
  });

  it('explains forward-only changes without exposing a fake rollback action', async () => {
    vi.mocked(loadAuthoringReleaseHistory).mockResolvedValue(
      releaseHistory({
        rollbackEligibility: {
          eligible: false,
          reasonCode: 'CONTAINS_FORWARD_ONLY_CHANGES',
          targetReleasePid: 'release_previous',
          reversibleItemCount: 1,
          compensatableItemCount: 0,
          forwardOnlyItemCount: 2,
        },
        items: [
          releaseHistory().items[0]!,
          {
            ...releaseHistory().items[0]!,
            releasePid: 'release_forward',
            reversibility: 'FORWARD_ONLY',
          },
        ],
        total: 2,
      }),
    );

    render(
      <AuthoringReleaseHistoryPanel
        changeSetPid="changeset_2"
        canRollback
        refreshKey="PUBLISHED:2"
      />,
    );

    expect(await screen.findByText('当前不可一键回滚')).toBeInTheDocument();
    expect(screen.getByText(/2 项仅前向变更/)).toBeInTheDocument();
    expect(screen.queryByTestId('authoring-rollback-prepare')).not.toBeInTheDocument();
    expect(rollbackAuthoringRelease).not.toHaveBeenCalled();
  });

  it('requires an audited reason and rechecks the active pointer when rolling back', async () => {
    const afterRollback = releaseHistory({
      activeReleasePid: 'release_previous',
      previousReleasePid: 'release_active',
      channelVersion: 8,
      rollbackEligibility: {
        eligible: false,
        reasonCode: 'PREVIOUS_RELEASE_UNAVAILABLE',
        targetReleasePid: 'release_active',
        reversibleItemCount: 1,
        compensatableItemCount: 0,
        forwardOnlyItemCount: 0,
      },
    });
    vi.mocked(loadAuthoringReleaseHistory)
      .mockResolvedValueOnce(releaseHistory())
      .mockResolvedValue(afterRollback);
    vi.mocked(rollbackAuthoringRelease).mockResolvedValue({
      releasePid: 'release_previous',
      changeSetPid: 'changeset_1',
      changeSetRevision: 1,
      previousReleasePid: 'release_active',
      status: 'ACTIVE',
      manifestChecksum: 'manifest_previous',
      channelVersion: 8,
      activatedAt: '2026-08-09T13:00:00Z',
    });
    const onRolledBack = vi.fn();

    render(
      <AuthoringReleaseHistoryPanel
        changeSetPid="changeset_2"
        canRollback
        refreshKey="PUBLISHED:2"
        onRolledBack={onRolledBack}
      />,
    );

    fireEvent.click(await screen.findByTestId('authoring-rollback-prepare'));
    const confirm = screen.getByTestId('authoring-rollback-confirm');
    expect(confirm).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/回滚原因/), {
      target: { value: '回归验证发现显示错误' },
    });
    expect(confirm).toBeEnabled();
    fireEvent.click(confirm);

    await waitFor(() =>
      expect(rollbackAuthoringRelease).toHaveBeenCalledWith(
        'release_active',
        7,
        '回归验证发现显示错误',
      ),
    );
    expect(await screen.findByText(/回滚已完成/)).toBeInTheDocument();
    expect(await screen.findByText('当前不可一键回滚')).toBeInTheDocument();
    expect(loadAuthoringReleaseHistory).toHaveBeenLastCalledWith('changeset_2', 1, 10);
    expect(onRolledBack).toHaveBeenCalledTimes(1);
  });

  it('loads later pages without truncating the release-history denominator', async () => {
    vi.mocked(loadAuthoringReleaseHistory).mockImplementation(async (_changeSetPid, page) =>
      releaseHistory({ page, total: 11 }),
    );

    render(
      <AuthoringReleaseHistoryPanel
        changeSetPid="changeset_2"
        canRollback={false}
        refreshKey="PUBLISHED:2"
      />,
    );

    fireEvent.click(await screen.findByRole('button', { name: '下一页' }));
    await waitFor(() =>
      expect(loadAuthoringReleaseHistory).toHaveBeenCalledWith('changeset_2', 2, 10),
    );
    expect(await screen.findByText('第 2 / 2 页')).toBeInTheDocument();
  });

  it('keeps the last successful page when loading the next page fails', async () => {
    vi.mocked(loadAuthoringReleaseHistory)
      .mockResolvedValueOnce(releaseHistory({ total: 11 }))
      .mockRejectedValueOnce(new Error('发布历史网络失败'));

    render(
      <AuthoringReleaseHistoryPanel
        changeSetPid="changeset_2"
        canRollback={false}
        refreshKey="PUBLISHED:2"
      />,
    );

    fireEvent.click(await screen.findByRole('button', { name: '下一页' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('发布历史网络失败');
    expect(screen.getByText('第 1 / 2 页')).toBeInTheDocument();
    expect(screen.getByTestId('authoring-release-release_active')).toBeInTheDocument();
  });

  it('ignores a late response from the ChangeSet that was already left', async () => {
    const oldHistory = deferred<AuthoringReleaseHistory>();
    const newHistory = deferred<AuthoringReleaseHistory>();
    vi.mocked(loadAuthoringReleaseHistory).mockImplementation((changeSetPid) =>
      changeSetPid === 'changeset_old' ? oldHistory.promise : newHistory.promise,
    );

    const { rerender } = render(
      <AuthoringReleaseHistoryPanel
        changeSetPid="changeset_old"
        canRollback={false}
        refreshKey="PUBLISHED:1"
      />,
    );
    await waitFor(() =>
      expect(loadAuthoringReleaseHistory).toHaveBeenCalledWith('changeset_old', 1, 10),
    );

    rerender(
      <AuthoringReleaseHistoryPanel
        changeSetPid="changeset_new"
        canRollback={false}
        refreshKey="PUBLISHED:1"
      />,
    );
    await waitFor(() =>
      expect(loadAuthoringReleaseHistory).toHaveBeenCalledWith('changeset_new', 1, 10),
    );
    await act(async () => {
      newHistory.resolve(
        releaseHistory({
          resourcePid: 'page_new',
          activeReleasePid: 'release_new',
          items: [
            {
              ...releaseHistory().items[0]!,
              releasePid: 'release_new',
              changeSetPid: 'changeset_new',
            },
          ],
        }),
      );
    });
    expect(await screen.findByTestId('authoring-release-release_new')).toHaveTextContent(
      '发布版本 · revision r2',
    );
    expect(screen.queryByText('release_new')).not.toBeInTheDocument();

    await act(async () => {
      oldHistory.resolve(releaseHistory({ activeReleasePid: 'release_old' }));
    });
    expect(screen.queryByText('release_old')).not.toBeInTheDocument();
    expect(screen.getByTestId('authoring-release-release_new')).toBeInTheDocument();
  });
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve;
  });
  return { promise, resolve };
}

function releaseHistory(overrides: Partial<AuthoringReleaseHistory> = {}): AuthoringReleaseHistory {
  return {
    resourcePid: 'page_1',
    activeReleasePid: 'release_active',
    previousReleasePid: 'release_previous',
    channelVersion: 7,
    rollbackEligibility: {
      eligible: true,
      reasonCode: 'ELIGIBLE',
      targetReleasePid: 'release_previous',
      reversibleItemCount: 1,
      compensatableItemCount: 0,
      forwardOnlyItemCount: 0,
    },
    items: [
      {
        releasePid: 'release_active',
        changeSetPid: 'changeset_2',
        changeSetRevision: 2,
        previousReleasePid: 'release_previous',
        status: 'ACTIVE',
        reversibility: 'REVERSIBLE',
        manifestChecksum: 'manifest_active',
        createdAt: '2026-08-09T12:00:00Z',
        activatedAt: '2026-08-09T12:00:00Z',
      },
    ],
    page: 1,
    size: 10,
    total: 1,
    ...overrides,
  };
}
