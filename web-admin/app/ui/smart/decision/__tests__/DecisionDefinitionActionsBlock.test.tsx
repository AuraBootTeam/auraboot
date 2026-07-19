import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { DecisionImpact } from '~/shared/decision/api/decisionApi';
import { DecisionDefinitionActionsBlock } from '../DecisionDefinitionActionsBlock';

const routerMocks = vi.hoisted(() => ({
  navigate: vi.fn(),
}));
const http = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}));

vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>();
  return {
    ...actual,
    useNavigate: () => routerMocks.navigate,
  };
});

vi.mock('~/shared/services/ApiService', () => ({
  getApiService: () => http,
}));

const safeImpact: DecisionImpact = {
  decisionCode: 'sla_deadline',
  incoming: [],
  outgoing: [{ targetType: 'FIELD', targetPath: 'record.data.priority' }],
  risk: {
    blocking: false,
    summary: 'No downstream consumers',
    counts: {},
  },
};

const blockingImpact: DecisionImpact = {
  decisionCode: 'sla_deadline',
  incoming: [
    {
      sourceType: 'AUTOMATION',
      sourceCode: 'auto-high-priority',
      sourceName: 'High Priority Automation',
    },
  ],
  outgoing: [],
  risk: {
    blocking: true,
    summary: 'Used by 1 automation',
    counts: { AUTOMATION: 1 },
  },
};

function mockDefinitionApi(options: {
  impact?: DecisionImpact;
  versions?: Array<Record<string, unknown>>;
}) {
  http.get.mockImplementation((endpoint: string) => {
    if (endpoint === '/decision/definitions/sla_deadline/impact') {
      return Promise.resolve({ data: options.impact ?? safeImpact });
    }
    if (endpoint === '/decision/definitions/sla_deadline/versions') {
      return Promise.resolve({ data: options.versions ?? [] });
    }
    return Promise.resolve({ data: {} });
  });
  http.post.mockResolvedValue({ data: {} });
  http.delete.mockResolvedValue({ data: {} });
}

function renderAtDetail(props?: { permissionCodes?: string[] }) {
  return render(
    <MemoryRouter initialEntries={['/p/decisionops_definitions/view/sla_deadline']}>
      <DecisionDefinitionActionsBlock block={props ? { props } : undefined} />
    </MemoryRouter>,
  );
}

describe('DecisionDefinitionActionsBlock', () => {
  beforeEach(() => {
    routerMocks.navigate.mockReset();
    http.get.mockReset();
    http.post.mockReset();
    http.delete.mockReset();
  });

  it('loads impact and version lifecycle data from a DSL detail URL', async () => {
    mockDefinitionApi({
      versions: [
        { pid: 'version-pid-1', decisionCode: 'sla_deadline', version: 1, status: 'PUBLISHED' },
        { pid: 'version-pid-2', decisionCode: 'sla_deadline', version: 2, status: 'VALIDATED' },
      ],
    });

    renderAtDetail();

    await waitFor(() =>
      expect(http.get).toHaveBeenCalledWith('/decision/definitions/sla_deadline/impact', undefined),
    );
    await waitFor(() => expect(screen.getByTestId('impact-graph-panel')).toBeInTheDocument());
    expect(screen.getByTestId('impact-graph-panel')).toHaveTextContent('record.data.priority');
    expect(screen.getByTestId('dda-version-version-pid-1')).toHaveTextContent('PUBLISHED');
    expect(screen.getByTestId('dda-version-version-pid-2')).toHaveTextContent('VALIDATED');
  });

  it('requires impact acknowledgement before publishing a referenced version', async () => {
    mockDefinitionApi({
      impact: blockingImpact,
      versions: [
        { pid: 'version-pid-2', decisionCode: 'sla_deadline', version: 2, status: 'VALIDATED' },
      ],
    });

    renderAtDetail();

    const publish = await screen.findByTestId('dda-publish-version-pid-2');
    expect(publish).toBeDisabled();

    fireEvent.click(screen.getByTestId('dda-impact-ack'));
    fireEvent.click(publish);

    await waitFor(() =>
      expect(http.post).toHaveBeenCalledWith('/decision/versions/version-pid-2/publish', {
        impactAcknowledged: true,
        note: 'DecisionOps definition DSL action acknowledged impact',
      }),
    );
    await waitFor(() => expect(screen.getByTestId('dda-message')).toHaveTextContent('发布成功'));
  });

  it('shows permission disabled reasons for publish and approval lifecycle actions', async () => {
    mockDefinitionApi({
      versions: [
        { pid: 'version-pid-validated', decisionCode: 'sla_deadline', version: 2, status: 'VALIDATED' },
        {
          pid: 'version-pid-pending',
          decisionCode: 'sla_deadline',
          version: 3,
          status: 'PENDING_APPROVAL',
        },
      ],
    });

    renderAtDetail({ permissionCodes: ['decision.definition.read'] });

    const submit = await screen.findByTestId('dda-submit-version-pid-validated');
    const publish = screen.getByTestId('dda-publish-version-pid-validated');
    const approve = screen.getByTestId('dda-approve-version-pid-pending');
    const reject = screen.getByTestId('dda-reject-version-pid-pending');

    expect(submit).toBeDisabled();
    expect(publish).toBeDisabled();
    expect(approve).toBeDisabled();
    expect(reject).toBeDisabled();
    expect(screen.getByTestId('dda-submit-version-pid-validated-disabled-reason')).toHaveTextContent(
      '缺少权限 decision.definition.publish',
    );
    expect(screen.getByTestId('dda-publish-version-pid-validated-disabled-reason')).toHaveTextContent(
      '缺少权限 decision.definition.publish',
    );
    expect(screen.getByTestId('dda-approve-version-pid-pending-disabled-reason')).toHaveTextContent(
      '缺少权限 decision.definition.approve',
    );
    expect(screen.getByTestId('dda-reject-version-pid-pending-disabled-reason')).toHaveTextContent(
      '缺少权限 decision.definition.approve',
    );

    fireEvent.click(publish);
    fireEvent.click(approve);

    expect(http.post).not.toHaveBeenCalled();
  });

  it('emits a clear error toast when a lifecycle action is rejected by backend permission guard', async () => {
    mockDefinitionApi({
      versions: [
        { pid: 'version-pid-validated', decisionCode: 'sla_deadline', version: 2, status: 'VALIDATED' },
      ],
    });
    const toastEvents: string[] = [];
    const handler = (event: Event) => {
      toastEvents.push((event as CustomEvent<{ message: string }>).detail.message);
    };
    window.addEventListener('aura:toast', handler);
    http.post.mockRejectedValueOnce(new Error('缺少权限 decision.definition.publish'));

    try {
      renderAtDetail({ permissionCodes: ['decision.definition.publish'] });

      fireEvent.click(await screen.findByTestId('dda-publish-version-pid-validated'));

      await waitFor(() =>
        expect(screen.getByTestId('dda-message')).toHaveTextContent('缺少权限 decision.definition.publish'),
      );
      expect(toastEvents).toContain('缺少权限 decision.definition.publish');
    } finally {
      window.removeEventListener('aura:toast', handler);
    }
  });

  it('wires the version lifecycle endpoints without returning to the old console page', async () => {
    mockDefinitionApi({
      versions: [
        { pid: 'version-pid-draft', decisionCode: 'sla_deadline', version: 1, status: 'DRAFT' },
        { pid: 'version-pid-validated', decisionCode: 'sla_deadline', version: 2, status: 'VALIDATED' },
        {
          pid: 'version-pid-pending',
          decisionCode: 'sla_deadline',
          version: 3,
          status: 'PENDING_APPROVAL',
        },
        { pid: 'version-pid-published', decisionCode: 'sla_deadline', version: 4, status: 'PUBLISHED' },
        { pid: 'version-pid-deprecated', decisionCode: 'sla_deadline', version: 5, status: 'DEPRECATED' },
      ],
    });

    renderAtDetail();

    fireEvent.click(await screen.findByTestId('dda-validate-version-pid-draft'));
    await waitFor(() =>
      expect(http.post).toHaveBeenCalledWith('/decision/versions/version-pid-draft/validate', undefined),
    );

    fireEvent.click(screen.getByTestId('dda-submit-version-pid-validated'));
    await waitFor(() =>
      expect(http.post).toHaveBeenCalledWith(
        '/decision/versions/version-pid-validated/submit-for-approval',
        undefined,
      ),
    );

    fireEvent.click(screen.getByTestId('dda-approve-version-pid-pending'));
    await waitFor(() =>
      expect(http.post).toHaveBeenCalledWith('/decision/versions/version-pid-pending/approve', {
        impactAcknowledged: true,
        note: 'DecisionOps definition DSL action acknowledged impact',
      }),
    );

    fireEvent.click(screen.getByTestId('dda-reject-version-pid-pending'));
    await waitFor(() =>
      expect(http.post).toHaveBeenCalledWith('/decision/versions/version-pid-pending/reject', {
        note: 'Rejected from DecisionOps DSL action',
      }),
    );

    fireEvent.click(screen.getByTestId('dda-deprecate-version-pid-published'));
    await waitFor(() =>
      expect(http.post).toHaveBeenCalledWith('/decision/versions/version-pid-published/deprecate', {
        impactAcknowledged: true,
        note: 'DecisionOps definition DSL action acknowledged impact',
      }),
    );

    fireEvent.click(screen.getByTestId('dda-retire-version-pid-deprecated'));
    await waitFor(() =>
      expect(http.post).toHaveBeenCalledWith('/decision/versions/version-pid-deprecated/retire', {
        impactAcknowledged: true,
        note: 'DecisionOps definition DSL action acknowledged impact',
      }),
    );

    fireEvent.click(screen.getByTestId('dda-delete-version-pid-draft'));
    await waitFor(() => expect(http.delete).toHaveBeenCalledWith('/decision/versions/version-pid-draft'));
    expect(routerMocks.navigate).not.toHaveBeenCalledWith('/decision-ops');
  });

  it('opens the existing rollout DSL monitor with baseline and candidate versions', async () => {
    mockDefinitionApi({
      versions: [
        { pid: 'version-pid-1', decisionCode: 'sla_deadline', version: 1, status: 'PUBLISHED' },
        { pid: 'version-pid-2', decisionCode: 'sla_deadline', version: 2, status: 'PUBLISHED' },
      ],
    });

    renderAtDetail();

    fireEvent.click(await screen.findByTestId('dda-start-rollout-version-pid-2'));

    expect(routerMocks.navigate).toHaveBeenCalledWith(
      '/p/decisionops_rollouts?decisionCode=sla_deadline&baselineVersion=1&candidateVersion=2',
    );
  });

  it('opens filtered execution logs from the definition detail action bar', async () => {
    mockDefinitionApi({
      versions: [{ pid: 'version-pid-1', decisionCode: 'sla_deadline', version: 1, status: 'PUBLISHED' }],
    });

    renderAtDetail();

    fireEvent.click(await screen.findByTestId('dda-open-logs'));

    expect(routerMocks.navigate).toHaveBeenCalledWith(
      '/p/decisionops_execution_logs?decisionCode=sla_deadline',
    );
  });

  it('can derive the decision code from a DSL runtime record', async () => {
    mockDefinitionApi({
      versions: [{ pid: 'version-pid-1', decisionCode: 'sla_deadline', version: 1, status: 'PUBLISHED' }],
    });

    render(
      <MemoryRouter>
        <DecisionDefinitionActionsBlock
          runtime={{
            getContext: () => ({
              record: {
                decisionCode: 'sla_deadline',
              },
            }),
          }}
        />
      </MemoryRouter>,
    );

    await waitFor(() =>
      expect(http.get).toHaveBeenCalledWith('/decision/definitions/sla_deadline/versions', undefined),
    );
  });
});
