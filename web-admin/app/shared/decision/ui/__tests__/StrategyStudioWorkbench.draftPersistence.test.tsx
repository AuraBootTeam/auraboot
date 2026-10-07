import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { StrategyStudioWorkbench } from '../StrategyStudioWorkbench';
import { STUDIO_DRAFT_KEY_PREFIX, STUDIO_WORKBENCH_DRAFT_SCOPE } from '../designerDraftStorage';
import type { DecisionApi } from '../../api/decisionApi';

const language = vi.hoisted(() => ({ locale: 'zh-CN' }));
// Flip to true to reproduce the legacy behavior: the studio keeps its mapping
// drafts purely in memory, so a locale switch — which reloads the page and
// remounts the workbench — loses every unsaved mapping row.
const draftPersistence = vi.hoisted(() => ({ disabled: false }));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: language.locale,
    t: (key: string, _params?: Record<string, unknown>, fallback?: string) => fallback ?? key,
  }),
}));
vi.mock('../designerDraftStorage', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../designerDraftStorage')>();
  return {
    ...actual,
    loadStudioDraft: vi.fn((scope: string) =>
      draftPersistence.disabled ? null : actual.loadStudioDraft(scope),
    ),
    saveStudioDraft: vi.fn((scope: string, draft: unknown) => {
      if (draftPersistence.disabled) return;
      actual.saveStudioDraft(scope, draft as Parameters<typeof actual.saveStudioDraft>[1]);
    }),
    clearStudioDraft: vi.fn((scope: string) => {
      actual.clearStudioDraft(scope);
    }),
  };
});
beforeEach(() => {
  language.locale = 'zh-CN';
  draftPersistence.disabled = false;
});

const STORAGE_KEY = `${STUDIO_DRAFT_KEY_PREFIX}${STUDIO_WORKBENCH_DRAFT_SCOPE}`;

interface ArchivedInputMapping {
  input: string;
  source?: { kind?: string; scope?: string; path?: string };
}

interface ArchivedBinding {
  decisionBinding?: { inputMappings?: ArchivedInputMapping[] };
}

function api(): DecisionApi {
  return {
    getActionCatalog: vi.fn(async () => ({ actions: [] })),
    listVersions: vi.fn(async () => []),
    getDecisionImpact: vi.fn(async () => ({
      incoming: [],
      outgoing: [],
      risk: { level: 'LOW', summary: 'no blockers' },
    })),
    evaluate: vi.fn(async () => ({
      traceId: 'trace-draft',
      status: 'MATCHED',
      matched: true,
      outputs: {},
    })),
    listDefinitions: vi.fn(async () => ({ records: [] })),
    getDefinition: vi.fn(async () => undefined),
    createDefinition: vi.fn(async () => ({ decisionCode: 'complaint_sla_deadline' })),
    createDraftVersion: vi.fn(async () => ({ pid: 'studio-draft-pid-1', status: 'DRAFT' })),
    listConditionFragments: vi.fn(async () => ({
      records: [],
      total: 0,
      size: 20,
      current: 1,
      pages: 0,
    })),
    createConditionFragment: vi.fn(async () => ({
      fragmentCode: 'wd_manager_approve_sla_condition',
      pid: 'studio-fragment-pid-1',
      version: 1,
      status: 'DRAFT',
    })),
  } as unknown as DecisionApi;
}

function mountWorkbench(fakeApi: DecisionApi = api()) {
  return render(<StrategyStudioWorkbench api={fakeApi} fields={[]} />);
}

function openDecisionMappingEditor() {
  fireEvent.click(screen.getByTestId('decision-rule-section-tab-decision'));
}

function addMappingRow(inputName: string) {
  fireEvent.click(screen.getByRole('button', { name: '添加映射' }));
  fireEvent.change(screen.getByLabelText('mapping-input-0'), { target: { value: inputName } });
}

function archivedBindings(): Record<string, ArchivedBinding> {
  const raw = sessionStorage.getItem(STORAGE_KEY);
  expect(raw).toBeTruthy();
  return JSON.parse(raw as string).bindings;
}

describe('StrategyStudioWorkbench unsaved mapping-draft persistence', () => {
  it('restores renamed mapping rows and field selections after a full remount simulating a locale switch', async () => {
    const first = mountWorkbench();
    await waitFor(() => expect(screen.getByTestId('strategy-studio')).toBeInTheDocument());

    openDecisionMappingEditor();
    addMappingRow('C1MAP-leaveDays');
    fireEvent.change(screen.getByRole('combobox', { name: 'mapping-field-0' }), {
      target: { value: 'sla:deadlineMinutes' },
    });

    const bindings = archivedBindings();
    expect(Object.keys(bindings)).toEqual(['SLA:wd_manager_approve_sla']);
    expect(bindings['SLA:wd_manager_approve_sla'].decisionBinding?.inputMappings).toEqual([
      {
        input: 'C1MAP-leaveDays',
        source: { kind: 'FIELD', scope: 'sla', path: 'deadlineMinutes' },
      },
    ]);

    // A locale switch calls window.location.reload(): the workbench fully
    // unmounts and a fresh instance mounts while sessionStorage survives.
    first.unmount();
    mountWorkbench();

    await waitFor(() =>
      expect(screen.getByLabelText('mapping-input-0')).toHaveValue('C1MAP-leaveDays'),
    );
    expect(screen.getByRole('combobox', { name: 'mapping-field-0' })).toHaveValue(
      'sla:deadlineMinutes',
    );
    // The re-archived snapshot carries exactly the restored mapping rows.
    expect(archivedBindings()).toEqual(bindings);
  });

  it('keeps archives scoped per scenario binding so different orchestrators never cross-contaminate', async () => {
    const first = mountWorkbench();
    await waitFor(() => expect(screen.getByTestId('strategy-studio')).toBeInTheDocument());

    openDecisionMappingEditor();
    addMappingRow('C1MAP-leaveDays');
    fireEvent.click(screen.getByTestId('strategy-scenario-BPM'));
    openDecisionMappingEditor();
    addMappingRow('BPM-MAP-1');

    const bindings = archivedBindings();
    expect(Object.keys(bindings)).toEqual(
      expect.arrayContaining(['SLA:wd_manager_approve_sla', 'BPM:wd_leave_approval']),
    );
    expect(bindings['SLA:wd_manager_approve_sla'].decisionBinding?.inputMappings?.[0]?.input).toBe(
      'C1MAP-leaveDays',
    );
    expect(bindings['BPM:wd_leave_approval'].decisionBinding?.inputMappings?.[0]?.input).toBe(
      'BPM-MAP-1',
    );

    first.unmount();
    mountWorkbench();

    await waitFor(() =>
      expect(screen.getByLabelText('mapping-input-0')).toHaveValue('C1MAP-leaveDays'),
    );
    fireEvent.click(screen.getByTestId('strategy-scenario-BPM'));
    await waitFor(() => expect(screen.getByLabelText('mapping-input-0')).toHaveValue('BPM-MAP-1'));
    // Each scenario binding keeps its own rows — the SLA rename never leaks.
    expect(screen.getByLabelText('mapping-input-0')).not.toHaveValue('C1MAP-leaveDays');
  });

  it('clears the session archive after the mapping draft is saved to the backend', async () => {
    const fakeApi = api();
    const view = mountWorkbench(fakeApi);
    await waitFor(() => expect(screen.getByTestId('strategy-studio')).toBeInTheDocument());

    openDecisionMappingEditor();
    addMappingRow('C1MAP-saved');
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeTruthy();

    fireEvent.click(screen.getByTestId('strategy-save-draft'));
    await waitFor(() =>
      expect(fakeApi.createConditionFragment as ReturnType<typeof vi.fn>).toHaveBeenCalledOnce(),
    );
    await waitFor(() => expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull());

    view.unmount();
    mountWorkbench();
    await waitFor(() => expect(screen.getByTestId('strategy-studio')).toBeInTheDocument());
    openDecisionMappingEditor();
    expect(screen.queryByLabelText('mapping-input-0')).not.toBeInTheDocument();
  });

  it('reproduces the legacy mapping-row loss when persistence is disabled (defect counter-evidence)', async () => {
    draftPersistence.disabled = true;
    const first = mountWorkbench();
    await waitFor(() => expect(screen.getByTestId('strategy-studio')).toBeInTheDocument());

    openDecisionMappingEditor();
    addMappingRow('切换即丢');
    expect(screen.getByLabelText('mapping-input-0')).toHaveValue('切换即丢');
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();

    first.unmount();
    mountWorkbench();
    await waitFor(() => expect(screen.getByTestId('strategy-studio')).toBeInTheDocument());
    openDecisionMappingEditor();
    expect(screen.queryByLabelText('mapping-input-0')).not.toBeInTheDocument();
  });
});
