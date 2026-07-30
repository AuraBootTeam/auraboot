import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ReleasesTab } from '../AgentDetailTabs';

const { get, post, put, translate } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  translate: (_key: string, _params?: unknown, fallback?: string) => fallback ?? _key,
}));

vi.mock('~/shared/services/http-client', () => ({
  get,
  post,
  put,
  del: vi.fn(),
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    t: translate,
  }),
}));

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({
    showSuccessToast: vi.fn(),
    showErrorToast: vi.fn(),
  }),
}));

const releases = [
  {
    pid: 'RELEASE_V2',
    release_no: 2,
    release_hash: '2222222222222222222222222222222222222222222222222222222222222222',
    status: 'published',
    source_updated_at: '2026-07-29T10:00:00Z',
    published_at: '2026-07-29T10:00:01Z',
    deployed: true,
  },
  {
    pid: 'RELEASE_V1',
    release_no: 1,
    release_hash: '1111111111111111111111111111111111111111111111111111111111111111',
    status: 'deprecated',
    source_updated_at: '2026-07-28T10:00:00Z',
    published_at: '2026-07-28T10:00:01Z',
    deployed: false,
  },
];

const deploymentPolicy = {
  deploymentPid: 'DEPLOYMENT_1',
  channelPolicy: {
    version: 'invocation-policy/v1',
    allowedChannels: ['web'],
    allowedInitiatorTypes: ['human'],
    allowedUserIds: [],
    allowedMemberIds: [],
    allowedRoleIds: [44],
  },
  policySnapshot: {
    invocationPolicyVersion: 'invocation-policy/v1',
  },
};

describe('Agent release tab', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    get.mockImplementation((url: string) =>
      Promise.resolve({
        code: '0',
        data: url.endsWith('/deployment-policy') ? deploymentPolicy : releases,
      }),
    );
    post.mockResolvedValue({ code: '0', data: { releaseNo: 3 } });
    put.mockResolvedValue({ code: '0', data: deploymentPolicy });
  });

  it('renders deployed and historical immutable releases with draft state', async () => {
    render(
      <ReleasesTab
        agentPid="AGENT_1"
        agentUpdatedAt="2026-07-29T11:00:00Z"
        readOnly={false}
      />,
    );

    await screen.findByTestId('agent-release-history');
    expect(screen.getByText('v2')).toBeTruthy();
    expect(screen.getByText('v1')).toBeTruthy();
    expect(screen.getByText('Deployed')).toBeTruthy();
    expect(screen.getByText('Historical')).toBeTruthy();
    expect(screen.getByTestId('agent-release-draft-state')).toHaveTextContent(
      'Draft changes are not deployed',
    );
    expect(get).toHaveBeenCalledWith('/api/agent/definitions/AGENT_1/releases');
    expect(get).toHaveBeenCalledWith('/api/agent/definitions/AGENT_1/deployment-policy');
  });

  it('requires confirmation, publishes, and reloads release history', async () => {
    render(
      <ReleasesTab
        agentPid="AGENT_1"
        agentUpdatedAt="2026-07-29T10:00:00Z"
        readOnly={false}
      />,
    );

    await screen.findByTestId('agent-release-history');
    fireEvent.click(screen.getByTestId('publish-agent-release'));
    expect(screen.getByRole('dialog', { name: 'Publish immutable release' })).toBeTruthy();

    fireEvent.click(screen.getByTestId('confirm-publish-agent-release'));

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/api/agent/definitions/AGENT_1/publish', {}),
    );
    await waitFor(() => expect(get).toHaveBeenCalledTimes(3));
  });

  it('confirms an atomic rollback to a historical release', async () => {
    render(
      <ReleasesTab
        agentPid="AGENT_1"
        agentUpdatedAt="2026-07-29T10:00:00Z"
        readOnly={false}
      />,
    );

    await screen.findByTestId('agent-release-history');
    fireEvent.click(screen.getByTestId('rollback-agent-release-1'));
    expect(screen.getByRole('dialog', { name: 'Roll back deployed release' })).toBeTruthy();

    fireEvent.click(screen.getByTestId('confirm-rollback-agent-release'));

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/api/agent/definitions/AGENT_1/releases/RELEASE_V1/deploy',
        {},
      ),
    );
    await waitFor(() => expect(get).toHaveBeenCalledTimes(3));
  });

  it('fails closed with a retry surface when release history cannot load', async () => {
    get.mockImplementation((url: string) =>
      url.endsWith('/releases')
        ? Promise.reject(new Error('network unavailable'))
        : Promise.resolve({ code: '0', data: deploymentPolicy }),
    );

    render(
      <ReleasesTab
        agentPid="AGENT_1"
        agentUpdatedAt="2026-07-29T10:00:00Z"
        readOnly={false}
      />,
    );

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Release history could not be loaded',
    );
    expect(screen.queryByTestId('publish-agent-release')).toBeNull();

    get.mockImplementation((url: string) =>
      Promise.resolve({
        code: '0',
        data: url.endsWith('/deployment-policy') ? deploymentPolicy : releases,
      }),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await screen.findByTestId('agent-release-history');
  });

  it('edits and saves the versioned deployment invocation policy', async () => {
    render(
      <ReleasesTab
        agentPid="AGENT_1"
        agentUpdatedAt="2026-07-29T10:00:00Z"
        readOnly={false}
      />,
    );

    await screen.findByTestId('agent-deployment-policy');
    await waitFor(() => expect(screen.getByTestId('deployment-channel-web')).toBeChecked());
    fireEvent.click(screen.getByTestId('deployment-channel-schedule'));
    fireEvent.change(screen.getByTestId('deployment-policy-allowedRoleIds'), {
      target: { value: '44, 55' },
    });
    fireEvent.click(screen.getByTestId('save-agent-deployment-policy'));

    await waitFor(() =>
      expect(put).toHaveBeenCalledWith(
        '/api/agent/definitions/AGENT_1/deployment-policy',
        expect.objectContaining({
          allowedChannels: ['web', 'schedule'],
          allowedRoleIds: [44, 55],
        }),
      ),
    );
  });
});
