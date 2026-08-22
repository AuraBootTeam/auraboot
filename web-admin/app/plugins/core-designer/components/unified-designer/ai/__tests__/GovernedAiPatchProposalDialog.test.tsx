import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  AuthoringAiPatchProposal,
  AuthoringSession,
  CapabilityRegistry,
} from '~/framework/meta/authoring/types';
import {
  applyAuthoringAiPatchProposal,
  createAuthoringAiPatchProposal,
  rejectAuthoringAiPatchProposal,
} from '~/framework/meta/authoring/authoringService';
import type { PageSchemaV3 } from '../../types';
import { GovernedAiPatchProposalDialog } from '../GovernedAiPatchProposalDialog';

vi.mock('~/framework/meta/authoring/authoringService', () => ({
  applyAuthoringAiPatchProposal: vi.fn(),
  createAuthoringAiPatchProposal: vi.fn(),
  rejectAuthoringAiPatchProposal: vi.fn(),
}));

const document: PageSchemaV3 = {
  schemaVersion: 3,
  kind: 'list',
  id: 'orders',
  blocks: [
    { id: 'table-1', blockType: 'table', props: { density: 'normal' } },
  ],
};

const capabilities: CapabilityRegistry = {
  checksum: 'registry-checksum',
  manifests: [
    {
      blockType: 'table',
      pluginCode: 'core.designer',
      pluginVersion: '1',
      manifestVersion: '1',
      checksum: 'table-checksum',
      properties: {
        '/props/density': {
          propertyPath: '/props/density',
          allowedOperations: ['ADD', 'REPLACE', 'REMOVE'],
          route: 'HANDOFF_STUDIO',
          risk: 'L3',
          effectTags: ['PRESENTATION'],
          reversibility: 'REVERSIBLE',
          protectedSemantic: false,
          rolePreviewRequired: false,
        },
      },
    },
  ],
};

describe('GovernedAiPatchProposalDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({
          content:
            '{"items":[{"blockId":"table-1","propertyPath":"/props/density",'
            + '"operation":"REPLACE","value":"compact"}]}',
        }),
      }),
    );
    vi.mocked(createAuthoringAiPatchProposal).mockResolvedValue(proposal());
    vi.mocked(applyAuthoringAiPatchProposal).mockResolvedValue({
      proposal: { ...proposal(), status: 'APPLIED', resultRevision: 4 },
      session: session(4),
    });
    vi.mocked(rejectAuthoringAiPatchProposal).mockResolvedValue({
      ...proposal(),
      status: 'REJECTED',
    });
  });

  it('keeps the draft unchanged until the reviewed server proposal is confirmed', async () => {
    const onApplied = vi.fn();
    const onClose = vi.fn();
    render(
      <GovernedAiPatchProposalDialog
        open
        onClose={onClose}
        sessionPid="session-1"
        revision={3}
        document={document}
        capabilities={capabilities}
        onApplied={onApplied}
      />,
    );

    fireEvent.change(screen.getByTestId('governed-ai-description'), {
      target: { value: 'Use compact density' },
    });
    fireEvent.click(screen.getByTestId('governed-ai-proposal-generate'));

    await screen.findByTestId('governed-ai-proposal-review');
    expect(createAuthoringAiPatchProposal).toHaveBeenCalledWith('session-1', 3, [
      {
        blockId: 'table-1',
        propertyPath: '/props/density',
        operation: 'REPLACE',
        value: 'compact',
        manifestChecksum: 'table-checksum',
      },
    ]);
    expect(onApplied).not.toHaveBeenCalled();
    expect(screen.getByText('草稿尚未变化')).toBeInTheDocument();
    expect(screen.getByText('变更前')).toBeInTheDocument();
    expect(screen.getByText('normal')).toBeInTheDocument();
    expect(screen.getByText('变更后')).toBeInTheDocument();
    expect(screen.getByText('compact')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('governed-ai-proposal-apply'));

    await waitFor(() => expect(onApplied).toHaveBeenCalledWith(session(4)));
    expect(applyAuthoringAiPatchProposal).toHaveBeenCalledWith('session-1', 'proposal-1', 3);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('records an explicit rejection when a reviewed proposal is closed', async () => {
    const onClose = vi.fn();
    render(
      <GovernedAiPatchProposalDialog
        open
        onClose={onClose}
        sessionPid="session-1"
        revision={3}
        document={document}
        capabilities={capabilities}
        onApplied={vi.fn()}
      />,
    );
    fireEvent.change(screen.getByTestId('governed-ai-description'), {
      target: { value: 'Use compact density' },
    });
    fireEvent.click(screen.getByTestId('governed-ai-proposal-generate'));
    await screen.findByTestId('governed-ai-proposal-review');

    fireEvent.click(screen.getByTestId('governed-ai-proposal-discard'));

    await waitFor(() => expect(rejectAuthoringAiPatchProposal).toHaveBeenCalled());
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

function proposal(): AuthoringAiPatchProposal {
  return {
    proposalPid: 'proposal-1',
    sourceSessionPid: 'session-1',
    changeSetPid: 'changes-1',
    pagePid: 'page-1',
    baseRevision: 3,
    registryChecksum: 'registry-checksum',
    proposalHash: 'hash-1',
    status: 'PROPOSED',
    aggregateRisk: 'L3',
    aggregateRoute: 'HANDOFF_STUDIO',
    publishPolicy: 'STUDIO_APPROVAL',
    typedPatchOnly: true,
    requiresHumanApproval: true,
    items: [
      {
        ordinal: 1,
        blockId: 'table-1',
        propertyPath: '/props/density',
        operation: 'REPLACE',
        previousValue: 'normal',
        value: 'compact',
        manifestChecksum: 'table-checksum',
        decision: {
          route: 'HANDOFF_STUDIO',
          risk: 'L3',
          publishPolicy: 'STUDIO_APPROVAL',
          reason: 'CAPABILITY_ALLOWED',
          manifestChecksum: 'table-checksum',
          rolePreviewRequired: false,
        },
      },
    ],
    createdAt: '2026-08-09T00:00:00Z',
  };
}

function session(revision: number): AuthoringSession {
  return {
    sessionPid: 'session-1',
    changeSetPid: 'changes-1',
    pagePid: 'page-1',
    ownerUserId: 1,
    changeSetStatus: 'DRAFT',
    workspaceMode: 'AUTHORING',
    state: 'ACTIVE',
    revision,
    riskLevel: 'L3',
    route: 'HANDOFF_STUDIO',
    publishPolicy: 'STUDIO_APPROVAL',
    validationState: 'UNVALIDATED',
    impactState: 'UNKNOWN',
    approvalState: 'PENDING',
    publishState: 'DRAFT',
    manifestChecksum: 'registry-checksum',
    snapshot: document as unknown as Record<string, unknown>,
    interactionContext: {},
    expiresAt: '2026-08-09T12:00:00Z',
  };
}
