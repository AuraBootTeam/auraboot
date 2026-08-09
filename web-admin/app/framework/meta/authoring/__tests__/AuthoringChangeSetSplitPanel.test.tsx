import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthoringChangeSetSplitPanel } from '../AuthoringChangeSetSplitPanel';
import { loadAuthoringChangeItems, splitAuthoringChangeSet } from '../authoringService';
import type { AuthoringChangeItem, AuthoringSession, AuthoringSplitResult } from '../types';

vi.mock('../authoringService', () => ({
  loadAuthoringChangeItems: vi.fn(),
  splitAuthoringChangeSet: vi.fn(),
}));

describe('AuthoringChangeSetSplitPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(loadAuthoringChangeItems).mockResolvedValue(changeItems());
  });

  it('creates a dependency-checked child and keeps an explicit target link', async () => {
    const onSplit = vi.fn();
    const result = splitResult();
    vi.mocked(splitAuthoringChangeSet).mockResolvedValue(result);

    render(<AuthoringChangeSetSplitPanel session={session()} enabled onSplit={onSplit} />);

    expect(await screen.findByTestId('authoring-split-panel')).toHaveTextContent('2 项');
    expect(screen.getByTestId('authoring-split-submit')).toBeDisabled();
    fireEvent.click(screen.getByTestId('authoring-split-item-item-l3'));
    fireEvent.change(screen.getByTestId('authoring-split-reason'), {
      target: { value: 'L3 数据源单独评审' },
    });
    fireEvent.click(screen.getByTestId('authoring-split-submit'));

    await waitFor(() =>
      expect(splitAuthoringChangeSet).toHaveBeenCalledWith(
        'session-source',
        3,
        ['item-l3'],
        '拆分自 changeset-source',
        'L3 数据源单独评审',
      ),
    );
    expect(await screen.findByTestId('authoring-split-success')).toHaveTextContent(
      'changeset-target',
    );
    expect(screen.getByTestId('authoring-split-target-link')).toHaveAttribute(
      'href',
      expect.stringContaining('authoringSession=session-target'),
    );
    expect(onSplit).toHaveBeenCalledWith(result);
  });

  it('surfaces fail-closed dependency rejection without losing the selection', async () => {
    vi.mocked(splitAuthoringChangeSet).mockRejectedValue(
      new Error('authoring.split.dependency-crosses-partition'),
    );

    render(<AuthoringChangeSetSplitPanel session={session()} enabled onSplit={vi.fn()} />);

    await screen.findByTestId('authoring-split-panel');
    fireEvent.click(screen.getByTestId('authoring-split-item-item-l3'));
    fireEvent.change(screen.getByTestId('authoring-split-reason'), {
      target: { value: '尝试拆分' },
    });
    fireEvent.click(screen.getByTestId('authoring-split-submit'));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'authoring.split.dependency-crosses-partition',
    );
    expect(screen.getByTestId('authoring-split-item-item-l3')).toBeChecked();
    expect(screen.queryByTestId('authoring-split-success')).not.toBeInTheDocument();
  });

  it('does not expose the professional split tool in a review workspace', () => {
    const review = session({ workspaceMode: 'REVIEW', state: 'READ_ONLY' });
    render(<AuthoringChangeSetSplitPanel session={review} enabled={false} onSplit={vi.fn()} />);

    expect(screen.queryByTestId('authoring-split-panel')).not.toBeInTheDocument();
    expect(loadAuthoringChangeItems).not.toHaveBeenCalled();
  });
});

function session(overrides: Partial<AuthoringSession> = {}): AuthoringSession {
  return {
    sessionPid: 'session-source',
    changeSetPid: 'changeset-source',
    pagePid: 'page-1',
    ownerUserId: 1,
    changeSetStatus: 'DRAFT',
    workspaceMode: 'AUTHORING',
    state: 'ACTIVE',
    revision: 3,
    riskLevel: 'L3',
    route: 'HANDOFF_STUDIO',
    publishPolicy: 'STUDIO_APPROVAL',
    validationState: 'UNVALIDATED',
    approvalState: 'PENDING',
    publishState: 'DRAFT',
    manifestChecksum: 'registry-1',
    snapshot: {},
    interactionContext: { route: '/orders' },
    writerLease: { status: 'OWNED', revision: 2, leasedUntil: '2026-08-09T12:00:00Z' },
    expiresAt: '2026-08-09T18:00:00Z',
    ...overrides,
  };
}

function changeItems(): AuthoringChangeItem[] {
  return [
    {
      changeItemPid: 'item-l0',
      blockId: 'table-1',
      propertyPath: '/props/density',
      operation: 'REPLACE',
      riskLevel: 'L0',
      route: 'INLINE',
      publishPolicy: 'DIRECT_ALLOWED',
      reversibility: 'REVERSIBLE',
      actorUserId: 1,
      dependencySnapshot: [],
      createdAt: '2026-08-09T00:00:01Z',
    },
    {
      changeItemPid: 'item-l3',
      blockId: 'table-1',
      propertyPath: '/dataSource',
      operation: 'ADD',
      riskLevel: 'L3',
      route: 'HANDOFF_STUDIO',
      publishPolicy: 'STUDIO_APPROVAL',
      reversibility: 'REVERSIBLE',
      actorUserId: 1,
      dependencySnapshot: [],
      createdAt: '2026-08-09T00:00:02Z',
    },
  ];
}

function splitResult(): AuthoringSplitResult {
  const items = changeItems();
  return {
    sourceSession: session({ revision: 4, riskLevel: 'L0', publishPolicy: 'DIRECT_ALLOWED' }),
    targetSession: session({
      sessionPid: 'session-target',
      changeSetPid: 'changeset-target',
      revision: 2,
    }),
    sourceItems: [items[0]],
    targetItems: [{ ...items[1], sourceChangeItemPid: 'item-l3' }],
    lineage: [{ changeSetPid: 'changeset-source', revision: 3, relation: 'SPLIT_FROM' }],
  };
}
