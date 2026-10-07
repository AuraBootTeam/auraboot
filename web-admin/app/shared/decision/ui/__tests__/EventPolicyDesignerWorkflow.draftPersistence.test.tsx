import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { EventPolicyDesignerWorkflow } from '../EventPolicyDesignerWorkflow';
import { DESIGNER_DRAFT_KEY_PREFIX } from '../designerDraftStorage';
import type { DecisionApi, EventPolicySummary } from '../../api/decisionApi';
import type { FieldOption } from '../ConditionBuilder';

const language = vi.hoisted(() => ({ locale: 'zh-CN' }));
// Flip to true to reproduce the legacy (EP-14/EP-23) behavior: the designer keeps
// its draft purely in memory, so a locale switch — which reloads the page and
// remounts the workflow — loses every unsaved edit.
const draftPersistence = vi.hoisted(() => ({ disabled: false }));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: language.locale, t: (key: string) => key }),
}));
vi.mock('../designerDraftStorage', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../designerDraftStorage')>();
  return {
    ...actual,
    loadDesignerDraft: vi.fn((policyCode: string) =>
      draftPersistence.disabled ? null : actual.loadDesignerDraft(policyCode),
    ),
    saveDesignerDraft: vi.fn((policyCode: string, draft: unknown) => {
      if (draftPersistence.disabled) return;
      actual.saveDesignerDraft(policyCode, draft as Parameters<typeof actual.saveDesignerDraft>[1]);
    }),
    clearDesignerDraft: vi.fn((policyCode: string) => {
      actual.clearDesignerDraft(policyCode);
    }),
  };
});
beforeEach(() => {
  language.locale = 'zh-CN';
  draftPersistence.disabled = false;
});

const FIELDS: FieldOption[] = [
  {
    scope: 'record',
    path: 'data.priority',
    label: '优先级',
    dataType: 'enum',
    options: ['HIGH', 'LOW'],
  },
];

const POLICY: EventPolicySummary = {
  policyCode: 'complaint_form_submit_policy',
  policyName: '投诉表单提交策略',
  eventType: 'FORM_SUBMITTED',
  targetType: 'FORM',
  targetKey: 'complaint_form',
  phase: 'AFTER_COMMIT',
  matchMode: 'COLLECT_ALL',
  status: 'DRAFT',
  version: 1,
  latestVersionPid: 'policy-version-pid-1',
  enabled: true,
};

function api(): DecisionApi {
  return {
    listPolicyVersions: vi.fn(async () => []),
    createPolicyDraftVersion: vi.fn(async () => ({
      pid: 'draft-pid-1',
      status: 'DRAFT',
      version: 2,
    })),
    getActionCatalog: vi.fn(async () => ({
      actions: [
        {
          actionType: 'NOTIFY',
          label: '发送站内通知',
          handlerAvailable: true,
          inputSchema: {},
        },
      ],
    })),
  } as unknown as DecisionApi;
}

function mountWorkflow(selectedPolicy: EventPolicySummary = POLICY, fakeApi = api()) {
  return render(
    <EventPolicyDesignerWorkflow api={fakeApi} fields={FIELDS} selectedPolicy={selectedPolicy} />,
  );
}

function draftFromScreen(): Record<string, unknown> {
  return JSON.parse(screen.getByTestId('epd-draft-json').textContent || '{}');
}

async function waitForDraftRuleName(ruleName: string) {
  await waitFor(() => {
    const draft = draftFromScreen() as { rules: Array<{ ruleName: string }> };
    expect(draft.rules[0].ruleName).toBe(ruleName);
  });
}

describe('EventPolicyDesignerWorkflow unsaved-draft persistence (EP-14/EP-23)', () => {
  it('restores rule name, condition AST and decision mapping rows after a full remount simulating a locale switch', async () => {
    const first = mountWorkflow();
    await waitFor(() => expect(screen.getByTestId('epd-workflow')).toBeInTheDocument());

    fireEvent.click(screen.getByTestId('epd-step-rules'));
    // EP-14: rename the default rule.
    fireEvent.change(screen.getByLabelText('rule-name-0'), {
      target: { value: '高优先级投诉升级' },
    });
    // EP-14: extend the condition group AST.
    fireEvent.click(screen.getByTestId('cb-add'));
    const fieldSelect = screen.getByRole('combobox', { name: /^field-/ }) as HTMLSelectElement;
    fireEvent.change(fieldSelect, { target: { value: 'record:data.priority' } });
    // EP-23: add a decision mapping row and rename its input.
    fireEvent.click(screen.getByRole('button', { name: '添加映射' }));
    fireEvent.change(screen.getByLabelText('mapping-input-0'), { target: { value: 'leaveDays' } });

    const draftBefore = draftFromScreen() as {
      rules: Array<Record<string, unknown>>;
    };
    expect(draftBefore.rules[0].ruleName).toBe('高优先级投诉升级');
    expect(draftBefore.rules[0].condition).toMatchObject({ type: 'group', op: 'AND' });
    expect(draftBefore.rules[0].decisionBinding).toMatchObject({
      decisionCode: expect.any(String),
      inputMappings: [{ input: 'leaveDays' }],
    });
    const storageKey = `${DESIGNER_DRAFT_KEY_PREFIX}${POLICY.policyCode}`;
    expect(sessionStorage.getItem(storageKey)).toBeTruthy();

    // A locale switch calls window.location.reload(): the workflow fully unmounts
    // and a fresh instance mounts while sessionStorage survives.
    first.unmount();
    mountWorkflow();

    await waitForDraftRuleName('高优先级投诉升级');
    expect(draftFromScreen()).toEqual(draftBefore);

    fireEvent.click(screen.getByTestId('epd-step-rules'));
    expect(screen.getByLabelText('rule-name-0')).toHaveValue('高优先级投诉升级');
    expect(screen.getByTestId('decision-binding-mapping-0')).toBeInTheDocument();
    expect(screen.getByLabelText('mapping-input-0')).toHaveValue('leaveDays');
    // The condition row added before the remount is part of the restored AST.
    expect(screen.getByTestId('epd-draft-json')).toHaveTextContent('"type":"compare"');
  });

  it('keeps archives scoped per policy code so different policies never cross-contaminate', async () => {
    const first = mountWorkflow(POLICY);
    await waitFor(() => expect(screen.getByTestId('epd-workflow')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('epd-step-rules'));
    fireEvent.change(screen.getByLabelText('rule-name-0'), { target: { value: 'Policy A draft' } });
    first.unmount();

    const otherPolicy = { ...POLICY, policyCode: 'leave_request_event_policy' };
    const second = mountWorkflow(otherPolicy);
    await waitFor(() => expect(screen.getByTestId('epd-workflow')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('epd-step-rules'));
    expect(screen.getByLabelText('rule-name-0')).toHaveValue('Rule 1');
    second.unmount();

    mountWorkflow(POLICY);
    await waitForDraftRuleName('Policy A draft');
    fireEvent.click(screen.getByTestId('epd-step-rules'));
    expect(screen.getByLabelText('rule-name-0')).toHaveValue('Policy A draft');
  });

  it('clears the session archive after the draft is saved to the backend', async () => {
    const fakeApi = api();
    const view = mountWorkflow(POLICY, fakeApi);
    await waitFor(() => expect(fakeApi.getActionCatalog).toHaveBeenCalledOnce());
    fireEvent.click(screen.getByTestId('epd-step-rules'));
    fireEvent.change(screen.getByLabelText('rule-name-0'), { target: { value: '已保存草稿' } });

    const storageKey = `${DESIGNER_DRAFT_KEY_PREFIX}${POLICY.policyCode}`;
    expect(sessionStorage.getItem(storageKey)).toBeTruthy();

    fireEvent.click(screen.getByTestId('epd-step-publish'));
    fireEvent.click(screen.getByTestId('epd-save-draft'));
    await waitFor(() => expect(fakeApi.createPolicyDraftVersion).toHaveBeenCalledOnce());
    await waitFor(() => expect(sessionStorage.getItem(storageKey)).toBeNull());

    view.unmount();
    mountWorkflow();
    await waitForDraftRuleName('Rule 1');
  });

  it('reproduces the legacy draft loss when persistence is disabled (defect counter-evidence)', async () => {
    draftPersistence.disabled = true;
    const first = mountWorkflow();
    await waitFor(() => expect(screen.getByTestId('epd-workflow')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('epd-step-rules'));
    fireEvent.change(screen.getByLabelText('rule-name-0'), { target: { value: '切换即丢' } });
    expect(draftFromScreen()).toMatchObject({
      rules: [{ ruleName: '切换即丢' }],
    });
    expect(sessionStorage.getItem(`${DESIGNER_DRAFT_KEY_PREFIX}${POLICY.policyCode}`)).toBeNull();

    first.unmount();
    mountWorkflow();
    await waitForDraftRuleName('Rule 1');
    fireEvent.click(screen.getByTestId('epd-step-rules'));
    expect(screen.getByLabelText('rule-name-0')).toHaveValue('Rule 1');
  });
});
