import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import { ConditionFragmentLibraryBlock } from '../ConditionFragmentLibraryBlock';

const auth = vi.hoisted(() => ({
  permissions: new Set([
    'decision.definition.manage',
    'decision.definition.publish',
    'decision.runtime.evaluate',
  ]),
}));
vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({ hasPermission: (code: string) => auth.permissions.has(code) }),
}));
const get = vi.fn();
const post = vi.fn();
const resolveUsageLink = vi.fn();

vi.mock('~/framework/bootstrap', () => ({
  getKernel: () => ({
    contributionRegistry: {
      getPrimaryService: (token: string) =>
        token === 'aura.decision.usage-navigation'
          ? { provider: { resolveLink: resolveUsageLink } }
          : undefined,
    },
  }),
}));

vi.mock('~/shared/services/ApiService', () => ({
  getApiService: () => ({
    get,
    post,
    delete: vi.fn(),
  }),
}));

const baseFragment = {
  pid: 'frag-pid-1',
  fragmentCode: 'leave_sla_node_match',
  fragmentName: '请假 SLA 节点匹配',
  description: 'SLA 计时前复用的条件片段',
  scopeType: 'SLA',
  scopeRef: 'wd_leave_approval',
  version: 1,
  status: 'PUBLISHED',
  conditionSpec: {
    root: {
      type: 'group',
      op: 'AND',
      children: [
        {
          type: 'compare',
          left: { type: 'path', scope: 'record', path: 'data.targetKey' },
          operator: 'EQ',
          right: { type: 'literal', value: 'task_manager_approve', dataType: 'string' },
        },
      ],
    },
  },
  fieldRefs: ['record.data.targetKey'],
  decisionRefs: ['complaint_sla_deadline'],
  ownerModule: 'workflow-demo',
};

describe('ConditionFragmentLibraryBlock', () => {
  beforeEach(() => {
    auth.permissions = new Set([
      'decision.definition.manage',
      'decision.definition.publish',
      'decision.runtime.evaluate',
    ]);
    get.mockReset();
    post.mockReset();
    resolveUsageLink.mockReset();
    resolveUsageLink.mockImplementation((ref: { sourcePid?: string }) =>
      ref.sourcePid
        ? { href: `/extensions/workflow/definitions/${encodeURIComponent(ref.sourcePid)}` }
        : undefined,
    );
    get.mockImplementation((endpoint: string) => {
      if (endpoint === '/decision/condition-fragments') {
        return Promise.resolve({
          data: {
            records: [baseFragment],
            total: 1,
          },
        });
      }
      if (endpoint === '/decision/condition-fragments/leave_sla_node_match/versions') {
        return Promise.resolve({
          data: [
            baseFragment,
            {
              ...baseFragment,
              pid: 'frag-pid-2',
              version: 2,
              status: 'DRAFT',
            },
          ],
        });
      }
      if (endpoint === '/decision/condition-fragments/leave_sla_node_match/impact') {
        return Promise.resolve({
          data: {
            fragmentCode: 'leave_sla_node_match',
            incomingCount: 3,
            incoming: [
              {
                sourceType: 'SLA_RULE',
                sourceCode: 'wd_manager_approve_sla',
                sourcePid: 'sla-manager-pid',
                sourceName: 'Manager Approval SLA',
              },
              {
                sourceType: 'WORKFLOW_PROCESS',
                sourceCode: 'wd_leave_approval',
                sourcePid: 'bpm-process-pid',
                sourceName: '请假审批',
              },
              {
                sourceType: 'PERMISSION_POLICY',
                sourceCode: 'model.leave_request.view',
                sourcePid: 'role-permission-pid',
                sourceName: '请假可见性策略',
              },
            ],
          },
        });
      }
      if (endpoint === '/decision/definitions') {
        return Promise.resolve({
          data: {
            records: [
              {
                decisionCode: 'complaint_sla_deadline',
                decisionName: '请假审批 SLA 截止时间',
                scopeType: 'SLA',
                enabled: true,
              },
              {
                decisionCode: 'approval_routing',
                decisionName: '请假审批分派',
                scopeType: 'WORKFLOW',
                enabled: true,
              },
              {
                decisionCode: 'dynamic_sla_deadline',
                decisionName: '动态 SLA 截止时间',
                scopeType: 'SLA',
                enabled: true,
              },
            ],
          },
        });
      }
      if (endpoint === '/decision/facts/catalog') {
        return Promise.resolve({
          data: {
            entities: [
              {
                entityCode: 'wd_leave_request',
                modelCode: 'wd_leave_request',
                label: '请假申请',
                facts: [
                  {
                    scope: 'record',
                    path: 'record.data.wd_req_days',
                    label: '请假天数',
                    dataType: 'decimal',
                  },
                  {
                    factKey: 'wd_leave_request.wd_req_type',
                    scope: 'record',
                    path: 'record.data.wd_req_type',
                    label: '请假类型',
                    dataType: 'dict',
                    dictCode: 'wd_leave_type',
                    allowedValues: [
                      { value: 'annual', label: '年假' },
                      { value: 'sick', label: '病假' },
                    ],
                  },
                ],
              },
            ],
          },
        });
      }
      return Promise.resolve({ data: [] });
    });
    post.mockImplementation((endpoint: string) => {
      if (endpoint.endsWith('/evaluate')) {
        return Promise.resolve({
          data: {
            fragmentCode: 'leave_sla_node_match',
            version: 1,
            result: 'MATCHED',
            matched: true,
          },
        });
      }
      if (endpoint.endsWith('/validate')) {
        return Promise.resolve({ data: { ...baseFragment, status: 'VALIDATED' } });
      }
      if (endpoint.endsWith('/publish')) {
        return Promise.resolve({ data: { ...baseFragment, status: 'PUBLISHED' } });
      }
      return Promise.resolve({ data: baseFragment });
    });
  });

  it('waits for model metadata before allowing edits to a published version', async () => {
    const original = get.getMockImplementation()!;
    let release!: (value: unknown) => void;
    const delayed = new Promise((resolve) => {
      release = resolve;
    });
    get.mockImplementation((endpoint: string, params: unknown) =>
      endpoint === '/decision/facts/catalog' ? delayed : original(endpoint, params),
    );
    render(<ConditionFragmentLibraryBlock />);
    await screen.findByTestId('cfl-row-leave_sla_node_match');
    expect(await screen.findByTestId('cfl-open-version')).toBeDisabled();
    expect(screen.getByTestId('cfl-open-create')).toBeDisabled();
    expect(screen.getByTestId('cfl-catalog-state')).toHaveTextContent('正在加载模型与属性');
    release(await original('/decision/facts/catalog', undefined));
    await waitFor(() => expect(screen.getByTestId('cfl-open-version')).toBeEnabled());
    fireEvent.click(screen.getByTestId('cfl-open-version'));
    expect(await screen.findByTestId('cfl-save-fragment')).toBeEnabled();
  });

  it('fails closed when metadata fails and retries it through the existing refresh action', async () => {
    const original = get.getMockImplementation()!;
    let catalogCalls = 0;
    get.mockImplementation((endpoint: string, params: unknown) =>
      endpoint === '/decision/facts/catalog' && catalogCalls++ === 0
        ? Promise.reject(new Error('Metadata unavailable'))
        : original(endpoint, params),
    );
    render(<ConditionFragmentLibraryBlock />);
    await screen.findByTestId('cfl-row-leave_sla_node_match');
    await waitFor(() =>
      expect(screen.getByTestId('cfl-catalog-state')).toHaveTextContent('加载失败'),
    );
    expect(await screen.findByTestId('cfl-open-version')).toBeDisabled();
    fireEvent.click(screen.getByTestId('cfl-refresh'));
    await waitFor(() => expect(screen.getByTestId('cfl-open-version')).toBeEnabled());
    expect(catalogCalls).toBe(2);
  });

  it('loads real condition fragments and shows versions plus downstream consumers', async () => {
    render(<ConditionFragmentLibraryBlock />);

    await waitFor(() =>
      expect(get).toHaveBeenCalledWith('/decision/condition-fragments', {
        page: 1,
        size: 50,
      }),
    );
    expect(await screen.findByTestId('cfl-row-leave_sla_node_match')).toHaveTextContent(
      '请假 SLA 节点匹配',
    );

    await waitFor(() =>
      expect(get).toHaveBeenCalledWith(
        '/decision/condition-fragments/leave_sla_node_match/versions',
        undefined,
      ),
    );
    expect(screen.getByTestId('cfl-impact')).toHaveTextContent('Manager Approval SLA');
    expect(screen.getByTestId('cfl-impact')).toHaveTextContent('请假审批');
    expect(screen.getByTestId('cfl-impact')).toHaveTextContent('请假可见性策略');
    expect(screen.getByTestId('cfl-versions')).toHaveTextContent('v2');
    expect(screen.getByTestId('cfl-versions')).toHaveTextContent('草稿');
    expect(screen.getByTestId('cfl-versions')).toHaveTextContent('已发布');
    expect(screen.getByTestId('cfl-versions')).not.toHaveTextContent('frag-pid-2');
    expect(screen.getByTestId('cfl-impact')).not.toHaveTextContent('wd_leave_approval');
    expect(screen.getByTestId('cfl-impact')).toHaveTextContent('SLA / 超时策略');
    expect(screen.getByTestId('cfl-impact')).toHaveTextContent('工作流 / 审批路由');
    expect(screen.getByTestId('cfl-impact')).toHaveTextContent('权限策略');
    expect(screen.getByTestId('cfl-impact-link-SLA_RULE-sla-manager-pid')).toHaveAttribute(
      'href',
      '/p/sla_config/view/sla-manager-pid',
    );
    expect(screen.getByTestId('cfl-impact-link-WORKFLOW_PROCESS-bpm-process-pid')).toHaveAttribute(
      'href',
      '/extensions/workflow/definitions/bpm-process-pid',
    );
    expect(
      screen.getByTestId('cfl-impact-link-PERMISSION_POLICY-role-permission-pid'),
    ).toHaveAttribute('href', '/enterprise/permissions');
    expect(screen.getByTestId('condition-fragment-library')).toHaveTextContent('当前记录.SLA 节点');
    expect(screen.getByTestId('condition-fragment-library')).not.toHaveTextContent(
      'record.data.targetKey',
    );
    expect(screen.getByTestId('cfl-decision-link-complaint_sla_deadline')).toHaveTextContent(
      '请假审批 SLA 截止时间',
    );
    expect(screen.getByTestId('cfl-decision-link-complaint_sla_deadline')).not.toHaveTextContent(
      'complaint_sla_deadline',
    );
    expect(screen.getByTestId('cfl-decision-link-complaint_sla_deadline')).toHaveAttribute(
      'href',
      '/p/decisionops_definitions/view/complaint_sla_deadline',
    );
    expect(screen.getByTestId('cfl-decision-logs-complaint_sla_deadline')).toHaveAttribute(
      'href',
      '/p/decisionops_execution_logs?decisionCode=complaint_sla_deadline',
    );
    expect(screen.getByTestId('condition-fragment-library')).not.toHaveTextContent('PUBLISHED');
    expect(screen.getByTestId('condition-fragment-library')).not.toHaveTextContent('DRAFT');
  });

  it('creates a fragment and evaluates the selected reusable condition', async () => {
    render(<ConditionFragmentLibraryBlock />);

    await screen.findByTestId('cfl-row-leave_sla_node_match');
    fireEvent.click(screen.getByTestId('cfl-open-create'));
    expect(screen.getByTestId('condition-builder')).toBeInTheDocument();
    expect(screen.queryByLabelText('fragment-condition-spec')).not.toBeInTheDocument();
    fireEvent.change(screen.getByTestId('fragment-code'), {
      target: { value: 'approval_high_amount' },
    });
    fireEvent.change(screen.getByTestId('fragment-name'), {
      target: { value: '高金额审批条件' },
    });
    fireEvent.change(screen.getByTestId('fragment-scope-type'), {
      target: { value: 'WORKFLOW' },
    });
    await waitFor(() =>
      expect(get).toHaveBeenCalledWith('/decision/definitions', { page: 1, size: 200 }),
    );
    fireEvent.change(screen.getByTestId('fragment-decision-binding-select'), {
      target: { value: 'approval_routing' },
    });
    fireEvent.click(screen.getByTestId('cfl-add-decision-binding'));
    expect(screen.getByTestId('cfl-decision-binding-approval_routing')).toHaveTextContent(
      '请假审批分派',
    );
    fireEvent.click(screen.getByTestId('cb-add'));
    fireEvent.change(screen.getByLabelText('model-0'), {
      target: { value: 'record:wd_leave_request' },
    });
    fireEvent.change(screen.getByLabelText('field-0'), {
      target: { value: 'record:data.wd_req_days' },
    });
    fireEvent.change(screen.getByLabelText('operator-0'), {
      target: { value: 'GTE' },
    });
    fireEvent.change(screen.getByLabelText('value-0'), {
      target: { value: '3' },
    });
    fireEvent.click(screen.getByTestId('cfl-save-fragment'));

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/decision/condition-fragments',
        expect.objectContaining({
          fragmentCode: 'approval_high_amount',
          fragmentName: '高金额审批条件',
          scopeType: 'WORKFLOW',
          conditionSpec: expect.objectContaining({
            decisionBindings: [
              {
                decisionCode: 'approval_routing',
                versionPolicy: 'LATEST_PUBLISHED',
                enabled: true,
              },
            ],
            root: expect.objectContaining({
              type: 'group',
              children: [
                expect.objectContaining({
                  operator: 'GTE',
                  left: expect.objectContaining({ scope: 'record', path: 'data.wd_req_days' }),
                  right: expect.objectContaining({ value: 3 }),
                }),
              ],
            }),
          }),
        }),
      ),
    );

    fireEvent.click(screen.getByTestId('cfl-run-evaluate'));
    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/decision/condition-fragments/leave_sla_node_match/evaluate',
        expect.any(Object),
      ),
    );
    expect(screen.getByTestId('cfl-evaluation')).toHaveTextContent('命中');
    expect(screen.getByTestId('cfl-evaluation')).not.toHaveTextContent('MATCHED');
  });

  it('labels dynamic decision bindings from loaded definition metadata', async () => {
    render(<ConditionFragmentLibraryBlock />);

    await screen.findByTestId('cfl-row-leave_sla_node_match');
    fireEvent.click(screen.getByTestId('cfl-open-create'));
    await waitFor(() =>
      expect(get).toHaveBeenCalledWith('/decision/definitions', { page: 1, size: 200 }),
    );

    fireEvent.change(screen.getByTestId('fragment-decision-binding-select'), {
      target: { value: 'dynamic_sla_deadline' },
    });
    fireEvent.click(screen.getByTestId('cfl-add-decision-binding'));

    expect(screen.getByTestId('cfl-decision-binding-dynamic_sla_deadline')).toHaveTextContent(
      '动态 SLA 截止时间',
    );
    expect(screen.getByTestId('cfl-decision-binding-dynamic_sla_deadline')).not.toHaveTextContent(
      'dynamic_sla_deadline',
    );
  });

  it('loads ConditionBuilder fields from the unified fact catalog before the legacy model field endpoint', async () => {
    render(<ConditionFragmentLibraryBlock />);

    await waitFor(() => expect(get).toHaveBeenCalledWith('/decision/facts/catalog', undefined));
    expect(get).not.toHaveBeenCalledWith('/decision/model/fields', undefined);

    fireEvent.click(await screen.findByTestId('cfl-open-create'));
    fireEvent.click(screen.getByTestId('cb-add'));
    const fieldSelect = screen.getByLabelText('field-0') as HTMLSelectElement;
    const optionText = Array.from(fieldSelect.options).map((option) => option.textContent);
    expect(optionText).toContain('请假类型');

    fireEvent.change(fieldSelect, { target: { value: 'record:data.wd_req_type' } });
    await waitFor(() => expect(screen.getByLabelText('value-0').tagName).toBe('SELECT'));
    const valueSelect = screen.getByLabelText('value-0') as HTMLSelectElement;
    expect(Array.from(valueSelect.options).map((option) => option.textContent)).toEqual(
      expect.arrayContaining(['年假', '病假']),
    );
  });

  it('blocks invalid lifecycle actions on immutable versions', async () => {
    render(<ConditionFragmentLibraryBlock />);

    await screen.findByTestId('cfl-row-leave_sla_node_match');
    await screen.findByTestId('cfl-open-version');

    expect(screen.getByTestId('cfl-open-version')).not.toBeDisabled();
    expect(screen.getByTestId('cfl-validate-selected')).toBeDisabled();
    expect(screen.getByTestId('cfl-publish-selected')).toBeDisabled();
  });

  it('keeps lifecycle result visible when the secondary list refresh fails', async () => {
    const draftFragment = {
      ...baseFragment,
      status: 'DRAFT',
    };
    const validatedFragment = {
      ...draftFragment,
      status: 'VALIDATED',
    };
    let listCalls = 0;
    let validated = false;
    get.mockImplementation((endpoint: string) => {
      if (endpoint === '/decision/condition-fragments') {
        listCalls += 1;
        if (listCalls > 1) {
          return Promise.reject(new Error('refresh failed'));
        }
        return Promise.resolve({
          data: {
            records: [draftFragment],
            total: 1,
          },
        });
      }
      if (endpoint === '/decision/condition-fragments/leave_sla_node_match/versions') {
        return Promise.resolve({ data: [validated ? validatedFragment : draftFragment] });
      }
      if (endpoint === '/decision/condition-fragments/leave_sla_node_match/impact') {
        return Promise.resolve({
          data: {
            fragmentCode: 'leave_sla_node_match',
            incomingCount: 0,
            incoming: [],
          },
        });
      }
      return Promise.resolve({ data: [] });
    });
    post.mockImplementation((endpoint: string) => {
      if (endpoint.endsWith('/validate')) {
        validated = true;
        return Promise.resolve({ data: validatedFragment });
      }
      return Promise.resolve({ data: draftFragment });
    });

    render(<ConditionFragmentLibraryBlock />);

    await screen.findByTestId('cfl-row-leave_sla_node_match');
    await waitFor(() => expect(screen.getByTestId('cfl-validate-selected')).not.toBeDisabled());
    expect(screen.getByTestId('cfl-publish-selected')).toBeDisabled();

    fireEvent.click(screen.getByTestId('cfl-validate-selected'));

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/decision/condition-fragment-versions/frag-pid-1/validate',
        undefined,
      ),
    );
    await waitFor(() => expect(screen.getByTestId('cfl-publish-selected')).not.toBeDisabled());
    expect(screen.getByTestId('cfl-message')).toHaveTextContent('已校验');
    expect(screen.queryByTestId('cfl-error')).not.toBeInTheDocument();
  });

  it('requires explicit impact acknowledgement before publishing a reused v2 fragment', async () => {
    const validatedV2 = {
      ...baseFragment,
      pid: 'frag-pid-2',
      version: 2,
      status: 'VALIDATED',
    };
    const publishedV2 = {
      ...validatedV2,
      status: 'PUBLISHED',
    };
    let published = false;
    get.mockImplementation((endpoint: string) => {
      if (endpoint === '/decision/condition-fragments') {
        return Promise.resolve({
          data: {
            records: [baseFragment, published ? publishedV2 : validatedV2],
            total: 2,
          },
        });
      }
      if (endpoint === '/decision/condition-fragments/leave_sla_node_match/versions') {
        return Promise.resolve({ data: [baseFragment, published ? publishedV2 : validatedV2] });
      }
      if (endpoint === '/decision/condition-fragments/leave_sla_node_match/impact') {
        return Promise.resolve({
          data: {
            fragmentCode: 'leave_sla_node_match',
            incomingCount: 2,
            incoming: [
              {
                sourceType: 'SLA_RULE',
                sourceCode: 'wd_manager_approve_sla',
                sourcePid: 'sla-manager-pid',
                sourceName: 'Manager Approval SLA',
              },
              {
                sourceType: 'WORKFLOW_PROCESS',
                sourceCode: 'wd_leave_approval',
                sourcePid: 'bpm-process-pid',
                sourceName: '请假审批',
              },
            ],
          },
        });
      }
      return Promise.resolve({ data: [] });
    });
    post.mockImplementation((endpoint: string) => {
      if (endpoint.endsWith('/publish')) {
        published = true;
        return Promise.resolve({ data: publishedV2 });
      }
      return Promise.resolve({ data: baseFragment });
    });

    render(<ConditionFragmentLibraryBlock />);

    await screen.findByTestId('cfl-row-leave_sla_node_match');
    await waitFor(() => expect(screen.getByTestId('cfl-impact-ack')).toBeInTheDocument());

    // Detail hydration resets consent for a new version/impact set. Interact only
    // after its readback settles, using the browser event sequence.
    await waitFor(() => expect(screen.queryByText('加载影响面...')).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId('cfl-versions')).toHaveTextContent('v2已校验'));
    const user = userEvent.setup();
    const publish = screen.getByTestId('cfl-publish-selected');
    expect(screen.getByTestId('cfl-open-version')).toBeDisabled();
    expect(screen.getByTestId('cfl-validate-selected')).not.toBeDisabled();
    expect(publish).toBeDisabled();
    expect(publish).toHaveAttribute('title', '请先确认 2 个复用方影响');

    const ack = screen.getByTestId('cfl-impact-ack') as HTMLInputElement;
    expect(ack.checked).toBe(false);
    await user.click(ack);
    await waitFor(() => expect(screen.getByTestId('cfl-impact-ack')).toBeChecked());
    await waitFor(() => expect(publish).not.toBeDisabled());

    await user.click(screen.getByTestId('cfl-publish-selected'));
    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/decision/condition-fragment-versions/frag-pid-2/publish',
        { impactAcknowledged: true },
      ),
    );
    await waitFor(() => expect(screen.getByTestId('cfl-versions')).toHaveTextContent('v2已发布'));
    expect(screen.queryByTestId('cfl-impact-ack')).not.toBeInTheDocument();
    expect(screen.getByTestId('cfl-publish-selected')).toBeDisabled();
  });
  it('shows the shared condition read-only and blocks every mutation without management permission', async () => {
    auth.permissions = new Set(['decision.definition.read']);
    render(<ConditionFragmentLibraryBlock />);
    const condition = await screen.findByTestId('cfl-readonly-condition');
    await waitFor(() => expect(condition).toHaveTextContent('只读'));
    expect(screen.getByTestId('cfl-open-create')).toBeDisabled();
    expect(screen.queryByTestId('cfl-open-edit')).not.toBeInTheDocument();
    for (const id of [
      'cfl-open-version',
      'cfl-validate-selected',
      'cfl-publish-selected',
      'cfl-run-evaluate',
    ]) {
      expect(screen.getByTestId(id)).toBeDisabled();
      fireEvent.click(screen.getByTestId(id));
    }
    for (const input of condition.querySelectorAll(
      'fieldset input, fieldset select, fieldset button',
    )) {
      expect(input).toBeDisabled();
    }
    fireEvent.click(screen.getByTestId('cfl-open-create'));
    expect(screen.queryByTestId('cfl-editor')).not.toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();
  });
});
