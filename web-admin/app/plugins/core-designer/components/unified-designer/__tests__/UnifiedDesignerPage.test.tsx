import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useSearchParams } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import UnifiedDesignerPage from '../../../pages/unified-designer';
import { samplePageSchemaV3 } from '../fixtures/samplePageSchemaV3';
import { loadModelFieldsByModelCodes } from '../persistence/modelFieldsRepository';
import { loadPageSchemaV3, savePageSchemaV3 } from '../persistence/pageSchemaV3Repository';
import type { PageSchemaV3 } from '../types';
import {
  applyAuthoringAiPatchProposal,
  applyAuthoringStudioBatch,
  consumeAuthoringHandoff,
  createAuthoringNewPageWorkspace,
  createAuthoringAiPatchProposal,
  isAuthoringPermissionDeniedError,
  loadAuthoringCapabilities,
  loadActiveAuthoringIdentitySimulation,
  loadAuthoringPermissionSnapshot,
  loadAuthoringNewPageWorkspaceOptions,
  loadAuthoringChangeItems,
  loadAuthoringReleaseHistory,
  loadAuthoringRolePreviewTargets,
  loadAuthoringRoleStructurePreview,
  loadAuthoringReviewWorkspace,
  loadAuthoringSession,
  openAuthoringReviewWorkspace,
  observeAuthoringChangeSet,
  prepareAuthoringSession,
  publishAuthoringChangeSet,
  renewAuthoringWriterLease,
  rollbackAuthoringRelease,
  rejectAuthoringAiPatchProposal,
  splitAuthoringChangeSet,
  submitAuthoringSession,
  takeoverAuthoringWriterLease,
  transitionAuthoringGovernance,
} from '~/framework/meta/authoring/authoringService';
import type {
  AuthoringAiPatchProposal,
  AuthoringChangeItem,
  AuthoringSession,
  AuthoringSplitResult,
  CapabilityRegistry,
  HandoffContext,
} from '~/framework/meta/authoring/types';
import { consumeAuthoringConflictTransfer } from '~/framework/meta/authoring/authoringConflictTransfer';
import {
  readInlineAuthoringRecovery,
  readStudioAuthoringRecovery,
  storeInlineAuthoringRecovery,
} from '~/framework/meta/authoring/authoringLocalRecovery';

const permissionMock = vi.hoisted(() => ({
  canAdministerDesigner: vi.fn((_permission: string) => true),
}));

vi.mock('~/contexts/AuthContext', () => ({
  usePermission: permissionMock.canAdministerDesigner,
  useUser: () => ({ user: { id: '1' }, isAuthenticated: true }),
}));

vi.mock('../persistence/pageSchemaV3Repository', () => ({
  loadPageSchemaV3: vi.fn(),
  savePageSchemaV3: vi.fn(),
}));

vi.mock('../persistence/modelFieldsRepository', async () => {
  const actual = await vi.importActual<typeof import('../persistence/modelFieldsRepository')>(
    '../persistence/modelFieldsRepository',
  );

  return {
    ...actual,
    loadModelFieldsByModelCodes: vi.fn(),
  };
});

vi.mock('~/framework/meta/authoring/authoringService', () => ({
  acknowledgeAuthoringIdentitySimulation: vi.fn(),
  endAuthoringIdentitySimulation: vi.fn(),
  loadActiveAuthoringIdentitySimulation: vi.fn(),
  applyAuthoringAiPatchProposal: vi.fn(),
  applyAuthoringStudioBatch: vi.fn(),
  consumeAuthoringHandoff: vi.fn(),
  createAuthoringNewPageWorkspace: vi.fn(),
  createAuthoringAiPatchProposal: vi.fn(),
  loadAuthoringCapabilities: vi.fn(),
  loadAuthoringPermissionSnapshot: vi.fn(),
  loadAuthoringNewPageWorkspaceOptions: vi.fn(),
  loadAuthoringIdentitySimulation: vi.fn(),
  loadAuthoringChangeItems: vi.fn(),
  loadAuthoringReleaseHistory: vi.fn(),
  loadAuthoringRolePreviewTargets: vi.fn(),
  loadAuthoringRoleStructurePreview: vi.fn(),
  loadAuthoringSyntheticPreview: vi.fn(),
  loadAuthoringReviewWorkspace: vi.fn(),
  loadAuthoringSession: vi.fn(),
  isAuthoringPermissionDeniedError: vi.fn(),
  observeAuthoringChangeSet: vi.fn(),
  prepareAuthoringSession: vi.fn(),
  publishAuthoringChangeSet: vi.fn(),
  renewAuthoringWriterLease: vi.fn(),
  rollbackAuthoringRelease: vi.fn(),
  rejectAuthoringAiPatchProposal: vi.fn(),
  splitAuthoringChangeSet: vi.fn(),
  submitAuthoringSession: vi.fn(),
  startAuthoringIdentitySimulation: vi.fn(),
  openAuthoringReviewWorkspace: vi.fn(),
  takeoverAuthoringWriterLease: vi.fn(),
  transitionAuthoringGovernance: vi.fn(),
}));

vi.mock('~/framework/meta/authoring/authoringConflictTransfer', () => ({
  consumeAuthoringConflictTransfer: vi.fn(),
}));

vi.mock('~/framework/meta/authoring/authoringRecoveryPolicy', async () => {
  const actual = await vi.importActual<
    typeof import('~/framework/meta/authoring/authoringRecoveryPolicy')
  >('~/framework/meta/authoring/authoringRecoveryPolicy');
  return { ...actual, loadAuthoringRecoveryPolicy: vi.fn().mockResolvedValue('PERSISTENT') };
});

describe('UnifiedDesignerPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(document, 'hasFocus').mockReturnValue(true);
    window.sessionStorage.clear();
    window.localStorage.clear();
    vi.mocked(loadModelFieldsByModelCodes).mockResolvedValue({});
    vi.mocked(savePageSchemaV3).mockResolvedValue({
      ok: true,
      source: { type: 'page', pid: 'page_1', pageKey: 'document_one' },
    });
    vi.mocked(consumeAuthoringHandoff).mockReset();
    vi.mocked(loadAuthoringSession).mockReset();
    vi.mocked(loadAuthoringCapabilities).mockReset();
    vi.mocked(loadActiveAuthoringIdentitySimulation).mockReset();
    vi.mocked(loadActiveAuthoringIdentitySimulation).mockResolvedValue(null);
    vi.mocked(loadAuthoringPermissionSnapshot).mockReset();
    vi.mocked(loadAuthoringPermissionSnapshot).mockResolvedValue({
      canReadDesigner: true,
      canManageDesigner: true,
      canAdministerDesigner: true,
    });
    vi.mocked(isAuthoringPermissionDeniedError).mockReset();
    vi.mocked(isAuthoringPermissionDeniedError).mockImplementation(
      (error) => error instanceof Error && /(?:403|forbidden|permission denied)/i.test(error.message),
    );
    vi.mocked(loadAuthoringNewPageWorkspaceOptions).mockReset();
    vi.mocked(createAuthoringNewPageWorkspace).mockReset();
    vi.mocked(loadAuthoringChangeItems).mockReset();
    vi.mocked(loadAuthoringChangeItems).mockResolvedValue([]);
    vi.mocked(loadAuthoringReleaseHistory).mockReset();
    vi.mocked(loadAuthoringReleaseHistory).mockResolvedValue({
      resourcePid: 'page_1',
      activeReleasePid: null,
      previousReleasePid: null,
      channelVersion: 0,
      rollbackEligibility: {
        eligible: false,
        reasonCode: 'NO_ACTIVE_RELEASE',
        targetReleasePid: null,
        reversibleItemCount: 0,
        compensatableItemCount: 0,
        forwardOnlyItemCount: 0,
      },
      items: [],
      page: 1,
      size: 10,
      total: 0,
    });
    vi.mocked(loadAuthoringRolePreviewTargets).mockReset();
    vi.mocked(loadAuthoringRolePreviewTargets).mockResolvedValue([]);
    vi.mocked(loadAuthoringRoleStructurePreview).mockReset();
    vi.mocked(loadAuthoringReviewWorkspace).mockReset();
    vi.mocked(applyAuthoringAiPatchProposal).mockReset();
    vi.mocked(applyAuthoringStudioBatch).mockReset();
    vi.mocked(createAuthoringAiPatchProposal).mockReset();
    vi.mocked(observeAuthoringChangeSet).mockReset();
    vi.mocked(prepareAuthoringSession).mockReset();
    vi.mocked(publishAuthoringChangeSet).mockReset();
    vi.mocked(renewAuthoringWriterLease).mockReset();
    vi.mocked(rollbackAuthoringRelease).mockReset();
    vi.mocked(rejectAuthoringAiPatchProposal).mockReset();
    vi.mocked(splitAuthoringChangeSet).mockReset();
    vi.mocked(submitAuthoringSession).mockReset();
    vi.mocked(openAuthoringReviewWorkspace).mockReset();
    vi.mocked(takeoverAuthoringWriterLease).mockReset();
    vi.mocked(transitionAuthoringGovernance).mockReset();
    vi.mocked(transitionAuthoringGovernance).mockResolvedValue(undefined);
    vi.mocked(consumeAuthoringConflictTransfer).mockReset();
    permissionMock.canAdministerDesigner.mockImplementation(
      (permission: string) => permission !== 'meta.publish.update',
    );
  });

  it('fails closed when a direct visitor lacks designer read permission', async () => {
    setSearch('');
    permissionMock.canAdministerDesigner.mockReturnValue(false);

    render(<UnifiedDesignerPage />);

    expect(await screen.findByText('应用设计中心不可用')).toBeInTheDocument();
    expect(screen.getByText(/meta\.designer\.read/)).toBeInTheDocument();
    expect(screen.queryByTestId('unified-designer-workbench')).not.toBeInTheDocument();
  });

  it('loads a pageId document and saves edits through the V3 repository', async () => {
    setSearch('?pageId=page_1');
    vi.mocked(loadPageSchemaV3).mockResolvedValue({
      document: createDocument('document_one', 'Document One'),
      source: { type: 'page', pid: 'page_1', pageKey: 'document_one' },
      published: false,
    });

    render(<UnifiedDesignerPage />);

    expect(await screen.findByText('Document One')).toBeInTheDocument();
    expect(screen.getByTestId('designer-return-link')).toHaveAttribute('href', '/p/page_schema');
    expect(loadPageSchemaV3).toHaveBeenCalledWith({ pageId: 'page_1', pageKey: null });

    fireEvent.click(screen.getByTestId('outline-item-field_customer_name'));
    fireEvent.change(screen.getByTestId('inspector-field-props.label'), {
      target: { value: 'Customer legal name' },
    });
    fireEvent.click(screen.getByTestId('designer-save'));

    await waitFor(() => expect(savePageSchemaV3).toHaveBeenCalledTimes(1));
    expect(savePageSchemaV3).toHaveBeenCalledWith({
      document: expect.objectContaining({
        schemaVersion: 3,
        id: 'document_one',
      }),
      source: { type: 'page', pid: 'page_1', pageKey: 'document_one' },
    });
  });

  it('uses bundled model fields for the local sample without remote lookup noise', async () => {
    setSearch('');

    render(<UnifiedDesignerPage />);

    expect(await screen.findByText('客户工作台')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('outline-item-section_basic'));
    fireEvent.click(screen.getByTestId('resource-tab-fields'));

    expect(await screen.findByTestId('model-field-email')).toBeInTheDocument();
    expect(loadModelFieldsByModelCodes).not.toHaveBeenCalled();
  });

  it('resets the workbench document when the pageId search parameter changes', async () => {
    setSearch('?pageId=page_1');
    vi.mocked(loadPageSchemaV3).mockImplementation(async ({ pageId }) => {
      if (pageId === 'page_2') {
        return {
          document: createDocument('document_two', 'Document Two'),
          source: { type: 'page', pid: 'page_2', pageKey: 'document_two' },
          published: false,
        };
      }

      return {
        document: createDocument('document_one', 'Document One'),
        source: { type: 'page', pid: 'page_1', pageKey: 'document_one' },
        published: false,
      };
    });

    const { rerender } = render(<UnifiedDesignerPage />);

    expect(await screen.findByText('Document One')).toBeInTheDocument();

    setSearch('?pageId=page_2');
    rerender(<UnifiedDesignerPage />);

    expect(await screen.findByText('Document Two')).toBeInTheDocument();
    expect(screen.queryByText('Document One')).not.toBeInTheDocument();
  });

  it('consumes an opaque handoff, loads the isolated snapshot and stays read-only without admin permission', async () => {
    setSearch('?contextId=ctx_secure_once');
    const replaceState = vi.spyOn(window.history, 'replaceState');
    permissionMock.canAdministerDesigner.mockReturnValue(false);
    const handoff = createHandoff('field_customer_name', '/props/label');
    const session = createAuthoringSession(createDocument('document_one', 'Isolated Draft'));
    session.ownership = {
      ownershipScope: 'TENANT',
      sourceOwnershipScope: 'APPLICATION',
      sourcePagePid: 'page_1',
      overridePid: 'override_1',
      origin: 'TENANT_OVERRIDE',
      tenantOverride: true,
      sourceMutable: false,
      restoreTarget: 'APPLICATION',
    };
    vi.mocked(consumeAuthoringHandoff).mockResolvedValue(handoff);
    vi.mocked(loadAuthoringSession).mockResolvedValue(session);
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());

    render(<UnifiedDesignerPage />);

    expect(await screen.findByTestId('studio-handoff-context')).toHaveTextContent('修订 r3');
    expect(screen.getByTestId('studio-handoff-context')).not.toHaveTextContent('changeset_1');
    expect(consumeAuthoringHandoff).toHaveBeenCalledWith('ctx_secure_once');
    expect(loadAuthoringSession).toHaveBeenCalledWith('session_1');
    expect(loadPageSchemaV3).not.toHaveBeenCalled();
    expect(screen.getByTestId('studio-handoff-context')).toHaveTextContent('Isolated Draft');
    expect(screen.getByTestId('authoring-ownership-notice')).toHaveTextContent(
      '正在编辑租户派生层',
    );
    expect(screen.getByTestId('authoring-ownership-notice')).toHaveTextContent(
      '共享来源页面保持不变',
    );
    expect(screen.getByTestId('authoring-ownership-notice')).not.toHaveTextContent('page_1');
    expect(screen.getByTestId('studio-handoff-read-only-reason')).toHaveTextContent(
      '缺少高级设计权限',
    );
    expect(screen.getByTestId('designer-return-link')).toHaveAttribute(
      'href',
      '/orders?tab=open&authoringReturn=session_1&authoringFocus=field_customer_name',
    );
    await waitFor(() =>
      expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('field_customer_name'),
    );
    expect(screen.getByTestId('designer-save')).toBeDisabled();
    expect(screen.queryByTestId('designer-publish')).not.toBeInTheDocument();
    expect(screen.queryByTestId('designer-export')).not.toBeInTheDocument();
    expect(screen.queryByTestId('designer-versions')).not.toBeInTheDocument();
    expect(screen.getByText('此对象没有可由当前 Studio 适配器安全保存的属性。')).toBeInTheDocument();
    expect(screen.getByTestId('designer-save')).toBeDisabled();
    expect(savePageSchemaV3).not.toHaveBeenCalled();
    expect(applyAuthoringStudioBatch).not.toHaveBeenCalled();
    expect(String(replaceState.mock.calls.at(-1)?.[2])).toContain(
      'authoringSession=session_1',
    );
    expect(String(replaceState.mock.calls.at(-1)?.[2])).not.toContain('contextId');
    replaceState.mockRestore();
  });

  it('turns a NEW_PAGE handoff into a governed resource wizard and switches to the new ChangeSet', async () => {
    setSearch('?contextId=ctx_new_page');
    const handoff = createHandoff('field_customer_name', '/props/label');
    handoff.intent = 'NEW_PAGE';
    const sourceSession = createAuthoringSession(createDocument('source_page', 'Source page'));
    const createdDocument = createDocument('production_exception', '生产异常看板');
    createdDocument.blocks = [];
    const createdSession = createAuthoringSession(
      createdDocument,
      3,
      'L3',
      'HANDOFF_STUDIO',
    );
    createdSession.sessionPid = 'session_new_page';
    createdSession.changeSetPid = 'changeset_new_page';
    createdSession.pagePid = 'page_new_page';
    createdSession.snapshot = {
      ...(createdDocument as unknown as Record<string, unknown>),
      pid: 'page_new_page',
      ownershipScope: 'TENANT',
      _authoringResource: { lifecycle: 'NEW' },
    };

    vi.mocked(consumeAuthoringHandoff).mockResolvedValue(handoff);
    vi.mocked(loadAuthoringSession).mockResolvedValue(sourceSession);
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(loadAuthoringNewPageWorkspaceOptions).mockResolvedValue({
      models: [{ value: 'manufacturing_exception', label: '生产异常' }],
      parentMenus: [{ value: 'manufacturing', label: '生产管理' }],
      permissions: [{ value: 'page.production_exception.read', label: '查看生产异常' }],
    });
    vi.mocked(createAuthoringNewPageWorkspace).mockResolvedValue(createdSession);

    render(<UnifiedDesignerPage />);

    expect(await screen.findByTestId('new-page-workspace-wizard')).toHaveTextContent(
      '创建页面并挂载菜单',
    );
    fireEvent.change(screen.getByLabelText('页面标题'), {
      target: { value: '生产异常看板' },
    });
    fireEvent.change(screen.getByLabelText(/^页面标识/), {
      target: { value: 'production_exception' },
    });
    expect(screen.getByRole('combobox', { name: /页面类型/ })).not.toHaveTextContent('看板');
    expect(screen.getByRole('link', { name: '仪表板设计器' })).toHaveAttribute(
      'href',
      '/dashboard-designer',
    );
    expect(screen.getByRole('link', { name: '模型设计器' })).toHaveAttribute(
      'href',
      '/meta/models/new',
    );
    const modelSelect = screen.getByLabelText('业务模型');
    await waitFor(() => {
      expect(modelSelect).toBeEnabled();
      expect(modelSelect).toHaveTextContent('生产异常');
    });
    fireEvent.change(screen.getByLabelText('父菜单'), { target: { value: 'manufacturing' } });
    fireEvent.change(screen.getByLabelText('访问权限'), {
      target: { value: 'page.production_exception.read' },
    });
    expect(screen.getByRole('button', { name: '创建并进入页面设计' })).toBeDisabled();
    fireEvent.change(modelSelect, {
      target: { value: 'manufacturing_exception' },
    });
    const createPageButton = screen.getByRole('button', { name: '创建并进入页面设计' });
    await waitFor(() => expect(createPageButton).toBeEnabled());
    fireEvent.click(createPageButton);

    await waitFor(() =>
      expect(createAuthoringNewPageWorkspace).toHaveBeenCalledWith('session_1', 3, {
        pageKey: 'production_exception',
        name: 'production_exception',
        title: '生产异常看板',
        description: undefined,
        kind: 'list',
        modelCode: 'manufacturing_exception',
        parentMenuCode: 'manufacturing',
        menuCode: 'production_exception',
        menuName: '生产异常看板',
        menuPath: '/production-exception',
        menuIcon: undefined,
        permissionCode: 'page.production_exception.read',
      }),
    );
    expect(await screen.findByTestId('studio-handoff-context')).toHaveTextContent('生产异常看板');
    expect(screen.getByTestId('studio-handoff-context')).not.toHaveTextContent(
      'changeset_new_page',
    );
    expect(screen.queryByTestId('new-page-workspace-wizard')).not.toBeInTheDocument();
  });

  it('fails closed when the tenant has no published business model', async () => {
    setSearch('?contextId=ctx_new_page_without_model');
    const handoff = createHandoff('field_customer_name', '/props/label');
    handoff.intent = 'NEW_PAGE';

    vi.mocked(consumeAuthoringHandoff).mockResolvedValue(handoff);
    vi.mocked(loadAuthoringSession).mockResolvedValue(
      createAuthoringSession(createDocument('source_page', 'Source page')),
    );
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(loadAuthoringNewPageWorkspaceOptions).mockResolvedValue({
      models: [],
      parentMenus: [{ value: 'manufacturing', label: '生产管理' }],
      permissions: [{ value: 'page.production_exception.read', label: '查看生产异常' }],
    });

    render(<UnifiedDesignerPage />);

    const modelSelect = await screen.findByLabelText('业务模型');
    expect(modelSelect).toBeDisabled();
    await waitFor(() => expect(modelSelect).toHaveTextContent('暂无已发布模型'));
    expect(screen.getByRole('button', { name: '创建并进入页面设计' })).toBeDisabled();
    expect(screen.getByRole('link', { name: '模型设计器' })).toHaveAttribute(
      'href',
      '/meta/models/new',
    );
    expect(createAuthoringNewPageWorkspace).not.toHaveBeenCalled();
  });

  it('restores the isolated Studio session after a full-page reload without replaying the handoff', async () => {
    setSearch('?authoringSession=session_1');
    const session = createAuthoringSession(createDocument('document_one', 'Reloaded Draft'), 6);
    session.interactionContext.selection = 'field_customer_name';
    vi.mocked(loadAuthoringSession).mockResolvedValue(session);
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());

    render(<UnifiedDesignerPage />);

    expect(await screen.findByTestId('studio-handoff-context')).toHaveTextContent('Reloaded Draft');
    expect(screen.getByTestId('studio-handoff-context')).toHaveTextContent('修订 r6');
    expect(consumeAuthoringHandoff).not.toHaveBeenCalled();
    expect(loadAuthoringSession).toHaveBeenCalledWith('session_1');
    expect(loadPageSchemaV3).not.toHaveBeenCalled();
    expect(screen.getByTestId('designer-return-link')).toHaveAttribute(
      'href',
      '/orders?tab=open&authoringReturn=session_1&authoringFocus=field_customer_name',
    );
    await waitFor(() =>
      expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('field_customer_name'),
    );
  });

  it('renews an owned Studio writer lease before its deadline without changing the draft revision', async () => {
    setSearch('?authoringSession=session_1');
    const session = createAuthoringSession(createDocument('document_one', 'Heartbeat Draft'), 6);
    session.writerLease = {
      status: 'OWNED',
      revision: 1,
      leasedUntil: new Date(Date.now() + 30_000).toISOString(),
    };
    const renewed = {
      ...session,
      writerLease: {
        status: 'OWNED' as const,
        revision: 2,
        leasedUntil: new Date(Date.now() + 300_000).toISOString(),
      },
    };
    vi.mocked(loadAuthoringSession).mockResolvedValue(session);
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(renewAuthoringWriterLease).mockResolvedValue(renewed);

    render(<UnifiedDesignerPage />);

    expect(await screen.findByTestId('studio-handoff-context')).toHaveTextContent('修订 r6');
    window.dispatchEvent(new Event('focus'));
    await waitFor(() =>
      expect(renewAuthoringWriterLease).toHaveBeenCalledWith('session_1'),
    );
    expect(screen.getByTestId('studio-handoff-context')).toHaveTextContent('修订 r6');
    expect(screen.getByTestId('studio-handoff-editable-reason')).toBeInTheDocument();
  });

  it('keeps new resources in Studio through prepare and submit without resuming them on the source page', async () => {
    setSearch('?authoringSession=session_1');
    const draft = createAuthoringSession(
      createDocument('production_exception', 'Production Exception'),
      3,
      'L3',
      'HANDOFF_STUDIO',
    );
    draft.snapshot = {
      ...draft.snapshot,
      _authoringResource: { lifecycle: 'NEW' },
    };
    const prepared = {
      ...draft,
      validationState: 'VALID',
      impactState: 'KNOWN',
    } as AuthoringSession;
    const submitted = {
      ...prepared,
      changeSetStatus: 'IN_REVIEW',
      state: 'READ_ONLY',
      publishState: 'WAITING_APPROVAL',
    } as AuthoringSession;
    vi.mocked(loadAuthoringSession)
      .mockResolvedValueOnce(draft)
      .mockResolvedValueOnce(submitted);
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(prepareAuthoringSession).mockResolvedValue(prepared);
    vi.mocked(submitAuthoringSession).mockResolvedValue(undefined);

    render(<UnifiedDesignerPage />);

    expect(await screen.findByTestId('studio-submission-notice')).toHaveTextContent(
      '校验与影响分析',
    );
    expect(screen.queryByTestId('designer-return-link')).not.toBeInTheDocument();
    expect(screen.getByTestId('studio-return-source')).toHaveAttribute('href', '/orders?tab=open');

    fireEvent.click(screen.getByTestId('studio-prepare-submit'));
    await waitFor(() =>
      expect(prepareAuthoringSession).toHaveBeenCalledWith('session_1', 3),
    );
    expect(await screen.findByTestId('studio-prepare-submit')).toHaveTextContent('提交评审');

    fireEvent.click(screen.getByTestId('studio-prepare-submit'));
    await waitFor(() => expect(submitAuthoringSession).toHaveBeenCalledWith('session_1', 3));
    expect(await screen.findByTestId('authoring-governance-notice')).toHaveTextContent(
      '评审中',
    );
  });

  it('opens a ChangeSet as a read-only observer and takes over its writer lease with a reason', async () => {
    setSearch('?changeSetId=changeset_1');
    const replaceState = vi.spyOn(window.history, 'replaceState');
    const observer = createAuthoringSession(createDocument('document_one', 'Observed Draft'));
    observer.state = 'READ_ONLY';
    observer.writerLease = {
      status: 'HELD_BY_OTHER',
      revision: 4,
      leasedUntil: '2026-08-09T12:05:00Z',
    };
    const taken = createAuthoringSession(createDocument('document_one', 'Observed Draft'));
    taken.writerLease = {
      status: 'OWNED',
      revision: 5,
      leasedUntil: '2026-08-09T12:10:00Z',
    };
    vi.mocked(observeAuthoringChangeSet).mockResolvedValue(observer);
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(takeoverAuthoringWriterLease).mockResolvedValue(taken);

    render(<UnifiedDesignerPage />);

    expect(await screen.findByTestId('authoring-writer-lease-notice')).toHaveTextContent(
      '另一位管理员正在编辑',
    );
    expect(observeAuthoringChangeSet).toHaveBeenCalledWith('changeset_1');
    expect(screen.getByTestId('designer-save')).toBeDisabled();
    fireEvent.change(screen.getByLabelText('接管原因'), {
      target: { value: '经值班负责人确认接管' },
    });
    fireEvent.click(screen.getByTestId('authoring-writer-lease-takeover'));

    await waitFor(() =>
      expect(takeoverAuthoringWriterLease).toHaveBeenCalledWith(
        'session_1',
        3,
        4,
        '经值班负责人确认接管',
      ),
    );
    expect(screen.queryByTestId('authoring-writer-lease-notice')).not.toBeInTheDocument();
    expect(screen.getByTestId('studio-handoff-editable-reason')).toBeInTheDocument();
    expect(String(replaceState.mock.calls.at(-1)?.[2])).toContain(
      'authoringSession=session_1',
    );
    expect(String(replaceState.mock.calls.at(-1)?.[2])).not.toContain('changeSetId');
    replaceState.mockRestore();
  });

  it('refreshes a losing takeover attempt without exposing a generic business error', async () => {
    setSearch('?changeSetId=changeset_1');
    const observer = createAuthoringSession(createDocument('document_one', 'Observed Draft'));
    observer.state = 'READ_ONLY';
    observer.writerLease = {
      status: 'HELD_BY_OTHER_SESSION',
      revision: 4,
      leasedUntil: '2026-08-09T12:05:00Z',
    };
    const winner = createAuthoringSession(createDocument('document_one', 'Observed Draft'));
    winner.state = 'READ_ONLY';
    winner.writerLease = {
      status: 'HELD_BY_OTHER_SESSION',
      revision: 5,
      leasedUntil: '2026-08-09T12:10:00Z',
    };
    vi.mocked(observeAuthoringChangeSet).mockResolvedValue(observer);
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(takeoverAuthoringWriterLease).mockRejectedValue(new Error('Business error'));
    vi.mocked(loadAuthoringSession).mockResolvedValue(winner);

    render(<UnifiedDesignerPage />);

    await screen.findByTestId('authoring-writer-lease-notice');
    fireEvent.change(screen.getByLabelText('接管原因'), {
      target: { value: '基于 lease r4 尝试接管' },
    });
    fireEvent.click(screen.getByTestId('authoring-writer-lease-takeover'));

    await waitFor(() => expect(loadAuthoringSession).toHaveBeenCalledWith('session_1'));
    expect(screen.getByTestId('writer-lease-takeover-feedback')).toHaveTextContent(
      '编辑权刚被另一会话取得，已刷新为只读',
    );
    expect(screen.queryByText('Business error')).not.toBeInTheDocument();
    expect(screen.getByTestId('designer-save')).toBeDisabled();
  });

  it('keeps a network-partitioned takeover retryable when no newer lease exists', async () => {
    setSearch('?changeSetId=changeset_1');
    const observer = createAuthoringSession(createDocument('document_one', 'Observed Draft'));
    observer.state = 'READ_ONLY';
    observer.writerLease = {
      status: 'HELD_BY_OTHER_SESSION',
      revision: 4,
      leasedUntil: '2026-08-09T12:05:00Z',
    };
    vi.mocked(observeAuthoringChangeSet).mockResolvedValue(observer);
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(takeoverAuthoringWriterLease).mockRejectedValue(
      new Error('Network error: Failed to fetch'),
    );
    vi.mocked(loadAuthoringSession).mockResolvedValue(observer);

    render(<UnifiedDesignerPage />);

    await screen.findByTestId('authoring-writer-lease-notice');
    fireEvent.change(screen.getByLabelText('接管原因'), {
      target: { value: '尝试恢复编辑' },
    });
    fireEvent.click(screen.getByTestId('authoring-writer-lease-takeover'));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '网络中断，未取得编辑权；当前仍为只读',
    );
    expect(screen.queryByTestId('writer-lease-takeover-feedback')).not.toBeInTheDocument();
    expect(screen.getByLabelText('接管原因')).toHaveValue('尝试恢复编辑');
    expect(screen.getByTestId('designer-save')).toBeDisabled();
  });

  it('reconciles a committed takeover when the success response is lost', async () => {
    setSearch('?changeSetId=changeset_1');
    const observer = createAuthoringSession(createDocument('document_one', 'Observed Draft'));
    observer.state = 'READ_ONLY';
    observer.writerLease = {
      status: 'HELD_BY_OTHER_SESSION',
      revision: 4,
      leasedUntil: '2026-08-09T12:05:00Z',
    };
    const committed = createAuthoringSession(createDocument('document_one', 'Observed Draft'));
    committed.writerLease = {
      status: 'OWNED',
      revision: 5,
      leasedUntil: '2026-08-09T12:10:00Z',
    };
    vi.mocked(observeAuthoringChangeSet).mockResolvedValue(observer);
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(takeoverAuthoringWriterLease).mockRejectedValue(
      new Error('Network error: Failed to fetch'),
    );
    vi.mocked(loadAuthoringSession).mockResolvedValue(committed);

    render(<UnifiedDesignerPage />);

    await screen.findByTestId('authoring-writer-lease-notice');
    fireEvent.change(screen.getByLabelText('接管原因'), {
      target: { value: '断网后对账接管' },
    });
    fireEvent.click(screen.getByTestId('authoring-writer-lease-takeover'));

    await waitFor(() => expect(loadAuthoringSession).toHaveBeenCalledWith('session_1'));
    expect(screen.queryByTestId('authoring-writer-lease-notice')).not.toBeInTheDocument();
    expect(screen.getByTestId('studio-handoff-editable-reason')).toBeVisible();
    expect(screen.getByTestId('writer-lease-takeover-feedback')).toHaveAttribute(
      'data-tone',
      'success',
    );
    expect(screen.getByTestId('writer-lease-takeover-feedback')).toHaveTextContent(
      '接管已在服务端完成，当前页面已恢复编辑',
    );
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('lets a non-owner reviewer approve the exact frozen revision in Studio', async () => {
    setSearch('?reviewChangeSetId=changeset_1');
    const observer = createAuthoringSession(createDocument('document_one', 'Review Draft'), 7);
    Object.assign(observer, {
      ownerUserId: 2,
      changeSetStatus: 'IN_REVIEW',
      workspaceMode: 'REVIEW',
      state: 'READ_ONLY',
      validationState: 'VALID',
      impactState: 'KNOWN',
      approvalState: 'PENDING',
      publishState: 'DRAFT',
      writerLease: {
        status: 'HELD_BY_OTHER',
        revision: 4,
        leasedUntil: '2026-08-09T12:05:00Z',
      },
    });
    const approved = { ...observer, changeSetStatus: 'APPROVED', approvalState: 'APPROVED', publishState: 'READY' };
    permissionMock.canAdministerDesigner.mockReturnValue(true);
    vi.mocked(openAuthoringReviewWorkspace).mockResolvedValue({
      session: observer,
      capabilities: createCapabilities(),
    });
    vi.mocked(loadAuthoringReviewWorkspace).mockResolvedValueOnce({
      session: approved,
      capabilities: createCapabilities(),
    });

    render(<UnifiedDesignerPage />);

    expect(await screen.findByTestId('authoring-governance-notice')).toHaveTextContent(
      'revision r7 已冻结',
    );
    expect(screen.getByTestId('designer-save')).toBeDisabled();
    expect(screen.getByTestId('studio-handoff-read-only-reason')).toHaveTextContent(
      '评审工作区按当前 revision 只读',
    );
    expect(screen.queryByTestId('authoring-writer-lease-notice')).not.toBeInTheDocument();
    expect(screen.queryByTestId('authoring-writer-lease-takeover')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('authoring-governance-approve'));

    await waitFor(() =>
      expect(transitionAuthoringGovernance).toHaveBeenCalledWith(
        'approve',
        expect.objectContaining({ changeSetPid: 'changeset_1', revision: 7 }),
        '',
      ),
    );
    expect(screen.getByTestId('authoring-governance-notice')).toHaveTextContent(
      'revision r7 已批准',
    );
    expect(screen.queryByTestId('authoring-governance-approve')).not.toBeInTheDocument();
    expect(screen.queryByTestId('authoring-governance-publish')).not.toBeInTheDocument();
    expect(screen.queryByTestId('authoring-release-history')).not.toBeInTheDocument();
    expect(loadAuthoringReleaseHistory).not.toHaveBeenCalled();
    expect(String(window.location.search)).toContain('reviewSession=session_1');
    expect(observeAuthoringChangeSet).not.toHaveBeenCalled();
  });

  it('publishes an approved revision only from Studio and reloads the terminal state', async () => {
    setSearch('?authoringSession=session_1');
    const approved = createAuthoringSession(createDocument('document_one', 'Approved Draft'), 7);
    Object.assign(approved, {
      changeSetStatus: 'APPROVED',
      state: 'READ_ONLY',
      validationState: 'VALID',
      impactState: 'KNOWN',
      approvalState: 'APPROVED',
      publishState: 'READY',
    });
    const published = { ...approved, changeSetStatus: 'PUBLISHED', state: 'CLOSED', publishState: 'PUBLISHED' };
    vi.mocked(loadAuthoringSession)
      .mockResolvedValueOnce(approved)
      .mockResolvedValueOnce(published);
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(publishAuthoringChangeSet).mockResolvedValue({
      releasePid: 'release_1',
      changeSetPid: 'changeset_1',
      changeSetRevision: 7,
      previousReleasePid: null,
      status: 'ACTIVE',
      manifestChecksum: 'manifest_1',
      channelVersion: 1,
      activatedAt: '2026-08-09T12:00:00Z',
    });

    render(<UnifiedDesignerPage />);

    fireEvent.click(await screen.findByTestId('authoring-governance-publish'));
    await waitFor(() =>
      expect(publishAuthoringChangeSet).toHaveBeenCalledWith('changeset_1', 7),
    );
    expect(await screen.findByText('revision r7 已发布')).toBeInTheDocument();
    expect(screen.queryByTestId('authoring-governance-publish')).not.toBeInTheDocument();
    expect(loadAuthoringSession).toHaveBeenCalledTimes(2);
  });

  it('keeps an approved revision retryable when atomic publish fails', async () => {
    setSearch('?authoringSession=session_1');
    const approved = createAuthoringSession(createDocument('document_one', 'Approved Draft'), 7);
    Object.assign(approved, {
      changeSetStatus: 'APPROVED',
      state: 'READ_ONLY',
      validationState: 'VALID',
      impactState: 'KNOWN',
      approvalState: 'APPROVED',
      publishState: 'READY',
    });
    vi.mocked(loadAuthoringSession).mockResolvedValue(approved);
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(publishAuthoringChangeSet).mockRejectedValue(
      new Error('发布失败；活动版本未改变，可检查状态后重试'),
    );

    render(<UnifiedDesignerPage />);

    fireEvent.click(await screen.findByTestId('studio-governance-open'));
    fireEvent.click(await screen.findByTestId('authoring-governance-publish'));
    expect(await screen.findByRole('alert')).toHaveTextContent('活动版本未改变');
    expect(screen.getByTestId('authoring-governance-publish')).toBeEnabled();
    expect(screen.getByText('revision r7 已批准')).toBeInTheDocument();
    expect(loadAuthoringSession).toHaveBeenCalledTimes(1);
  });

  it('keeps one embedded workbench and exposes governance only through its drawer', async () => {
    setSearch('?authoringSession=session_1');
    vi.mocked(loadAuthoringSession).mockResolvedValue(
      createAuthoringSession(createDocument('document_one', 'Governed Draft')),
    );
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());

    render(<UnifiedDesignerPage />);

    await screen.findByTestId('studio-handoff-context');
    expect(screen.getAllByTestId('unified-designer-workbench')).toHaveLength(1);
    expect(screen.getByTestId('unified-designer-workbench')).toHaveClass('h-full');
    expect(screen.getByTestId('studio-governance-drawer')).toHaveAttribute('hidden');

    fireEvent.click(screen.getByTestId('studio-governance-open'));
    expect(screen.getByTestId('studio-governance-drawer')).not.toHaveAttribute('hidden');
    expect(screen.getByTestId('studio-governance-open')).toHaveAttribute('aria-expanded', 'true');

    fireEvent.click(screen.getByTestId('studio-governance-close'));
    expect(screen.getByTestId('studio-governance-drawer')).toHaveAttribute('hidden');
    expect(screen.getByTestId('studio-governance-open')).toHaveAttribute('aria-expanded', 'false');
  });

  it('restores the dedicated review workspace after a full-page reload', async () => {
    setSearch('?reviewSession=review_session_1');
    permissionMock.canAdministerDesigner.mockReturnValue(true);
    const reviewSession = createAuthoringSession(
      createDocument('document_one', 'Reloaded Review Draft'),
      9,
    );
    Object.assign(reviewSession, {
      sessionPid: 'review_session_1',
      ownerUserId: 2,
      changeSetStatus: 'IN_REVIEW',
      workspaceMode: 'REVIEW',
      state: 'READ_ONLY',
      validationState: 'VALID',
      impactState: 'KNOWN',
      approvalState: 'PENDING',
      writerLease: {
        status: 'HELD_BY_OTHER',
        revision: 5,
        leasedUntil: '2026-08-09T12:05:00Z',
      },
    });
    vi.mocked(loadAuthoringReviewWorkspace).mockResolvedValue({
      session: reviewSession,
      capabilities: createCapabilities(),
    });

    render(<UnifiedDesignerPage />);

    expect(await screen.findByTestId('studio-handoff-context')).toHaveTextContent(
      'Reloaded Review Draft',
    );
    expect(loadAuthoringReviewWorkspace).toHaveBeenCalledWith('review_session_1');
    expect(loadAuthoringSession).not.toHaveBeenCalled();
    expect(loadAuthoringCapabilities).not.toHaveBeenCalled();
    expect(screen.getByTestId('studio-handoff-read-only-reason')).toHaveTextContent(
      '评审工作区按当前 revision 只读',
    );
    expect(screen.getByTestId('authoring-governance-approve')).toBeEnabled();
    expect(screen.queryByTestId('authoring-writer-lease-takeover')).not.toBeInTheDocument();
  });

  it('consumes an opaque same-tab conflict transfer and opens the professional three-way panel', async () => {
    const conflictContextId = 'a'.repeat(32);
    setSearch(`?authoringSession=session_1&conflictContext=${conflictContextId}`);
    const replaceState = vi.spyOn(window.history, 'replaceState');
    const baseline = createDocument('document_one', 'Isolated Draft');
    const mine = createDocument('document_one', 'Isolated Draft');
    const mineList = findBlock(mine.blocks, 'list_customer');
    if (!mineList) throw new Error('list_customer fixture missing');
    mineList.dataSource = { model: 'payment' };
    const latest = createDocument('document_one', 'Isolated Draft');
    const latestList = findBlock(latest.blocks, 'list_customer');
    if (!latestList) throw new Error('list_customer fixture missing');
    latestList.dataSource = { model: 'refund' };
    const latestSession = createAuthoringSession(latest, 4);
    const refreshedLatest = createDocument('document_one', 'Isolated Draft');
    const refreshedLatestList = findBlock(refreshedLatest.blocks, 'list_customer');
    if (!refreshedLatestList) throw new Error('list_customer fixture missing');
    refreshedLatestList.dataSource = { model: 'invoice' };
    vi.mocked(loadAuthoringSession)
      .mockResolvedValueOnce(latestSession)
      .mockResolvedValueOnce(latestSession)
      .mockResolvedValueOnce(createAuthoringSession(refreshedLatest, 5));
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(consumeAuthoringConflictTransfer).mockReturnValue({
      version: 1,
      createdAt: Date.now(),
      sessionPid: 'session_1',
      changeSetPid: 'changeset_1',
      pagePid: 'page_1',
      baseRevision: 3,
      baseSnapshot: baseline as unknown as Record<string, unknown>,
      mineSnapshot: mine as unknown as Record<string, unknown>,
    });
    expect(
      storeInlineAuthoringRecovery({
        actorId: '1',
        sessionPid: 'session_1',
        pagePid: 'page_1',
        baseRevision: 3,
        state: 'DIRTY',
        edits: [
          {
            key: 'list_customer:/dataSource',
            baseRevision: 3,
            blockId: 'list_customer',
            blockLabel: '客户列表',
            manifestChecksum: 'list-checksum',
            property: {
              propertyPath: '/dataSource',
              allowedOperations: ['REPLACE'],
              route: 'HANDOFF_STUDIO',
              risk: 'L3',
              effectTags: [],
              reversibility: 'REVERSIBLE',
              protectedSemantic: false,
              rolePreviewRequired: false,
            },
            operation: 'REPLACE',
            previousValue: { model: 'customer' },
            value: { model: 'payment' },
          },
        ],
      }),
    ).toBe(true);

    const firstRender = render(<UnifiedDesignerPage />);

    expect(await screen.findByTestId('authoring-conflict-panel')).toHaveTextContent(
      'Base / Mine / Latest',
    );
    expect(consumeAuthoringConflictTransfer).toHaveBeenCalledWith(conflictContextId, {
      sessionPid: 'session_1',
      changeSetPid: 'changeset_1',
      pagePid: 'page_1',
    });
    expect(screen.getByTestId('designer-save')).toBeDisabled();
    expect(String(replaceState.mock.calls.at(-1)?.[2])).toContain(
      'authoringSession=session_1',
    );
    expect(String(replaceState.mock.calls.at(-1)?.[2])).not.toContain('conflictContext');
    expect(readInlineAuthoringRecovery('1', 'page_1')).toBeNull();
    expect(readStudioAuthoringRecovery('1', 'session_1')?.mineDocument).toEqual(mine);

    firstRender.unmount();
    setSearch('?authoringSession=session_1');
    render(<UnifiedDesignerPage />);

    expect(await screen.findByTestId('authoring-conflict-panel')).toHaveTextContent(
      'Base / Mine / Latest',
    );
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');

    fireEvent.click(screen.getByTestId('authoring-conflict-use-latest'));

    await waitFor(() =>
      expect(screen.queryByTestId('authoring-conflict-panel')).not.toBeInTheDocument(),
    );
    expect(loadAuthoringSession).toHaveBeenCalledTimes(3);
    expect(screen.getByTestId('studio-handoff-context')).toHaveTextContent('修订 r5');
    expect(readStudioAuthoringRecovery('1', 'session_1')).toBeNull();
    replaceState.mockRestore();
  });

  it('saves a manifest-backed Studio edit into the same ChangeSet without touching PageSchema', async () => {
    setSearch('?contextId=ctx_secure_once');
    const handoff = createHandoff('list_customer', '/dataSource');
    const baseline = createDocument('document_one', 'Isolated Draft');
    const session = createAuthoringSession(baseline);
    const saved = createDocument('document_one', 'Isolated Draft');
    const list = saved.blocks.find((block) => block.id === 'list_customer');
    if (list) list.dataSource = { model: 'payment' };

    vi.mocked(consumeAuthoringHandoff).mockResolvedValue(handoff);
    vi.mocked(loadAuthoringSession).mockResolvedValue(session);
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(applyAuthoringStudioBatch).mockResolvedValue({
      session: createAuthoringSession(saved, 4, 'L3', 'HANDOFF_STUDIO'),
      changeItemPids: ['item_1'],
    });

    render(<UnifiedDesignerPage />);

    expect(await screen.findByTestId('studio-handoff-editable-reason')).toHaveTextContent(
      '写回同一隔离草稿',
    );
    expect(await screen.findByTestId('designer-contextual-restricted')).toHaveTextContent(
      '同一 ChangeSet',
    );
    expect(loadPageSchemaV3).not.toHaveBeenCalled();
    expect(screen.queryByTestId('designer-kind-switch')).not.toBeInTheDocument();
    expect(screen.getByTestId('designer-export')).toBeInTheDocument();
    expect(screen.getByTestId('designer-import')).toBeInTheDocument();
    expect(screen.queryByTestId('designer-versions')).not.toBeInTheDocument();
    expect(screen.queryByTestId('designer-publish')).not.toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('list_customer'),
    );
    fireEvent.click(screen.getByTestId('resource-tab-blocks'));
    const structuralActions = screen.getAllByTestId(/^palette-add-/);
    expect(structuralActions.length).toBeGreaterThan(0);
    structuralActions.forEach((action: HTMLElement) => expect(action).toBeDisabled());

    fireEvent.change(screen.getByTestId('inspector-field-dataSource.model-manual'), {
      target: { value: 'payment' },
    });
    fireEvent.click(screen.getByTestId('designer-save'));

    await waitFor(() =>
      expect(applyAuthoringStudioBatch).toHaveBeenCalledWith(
        'session_1',
        3,
        {
          kindSwitch: null,
          creates: [],
          relocations: [],
          removes: [],
          moves: [],
          patches: [{
            blockId: 'list_customer',
            propertyPath: '/dataSource',
            operation: 'REPLACE',
            value: { model: 'payment' },
            manifestChecksum: 'list-checksum',
          }],
          unsupported: [],
        },
      ),
    );
    expect(screen.getByTestId('studio-handoff-context')).toHaveTextContent('修订 r4');
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('已保存');
    expect(savePageSchemaV3).not.toHaveBeenCalled();
  });

  it('routes Studio AI through a reviewed typed proposal before changing the isolated draft', async () => {
    setSearch('?authoringSession=session_1');
    const baseline = createDocument('document_one', 'AI Governed Draft');
    const appliedDocument = JSON.parse(JSON.stringify(baseline)) as PageSchemaV3;
    const target = findTestBlock(appliedDocument.blocks, 'field_customer_name');
    target.props = { ...(target.props ?? {}), label: 'AI confirmed label' };
    const originalSession = createAuthoringSession(baseline);
    const appliedSession = createAuthoringSession(appliedDocument, 4);
    const serverProposal = createAiProposal();
    vi.mocked(loadAuthoringSession).mockResolvedValue(originalSession);
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(createAuthoringAiPatchProposal).mockResolvedValue(serverProposal);
    vi.mocked(applyAuthoringAiPatchProposal).mockResolvedValue({
      proposal: { ...serverProposal, status: 'APPLIED', resultRevision: 4 },
      session: appliedSession,
    });
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({
          content:
            '{"items":[{"blockId":"field_customer_name",'
            + '"propertyPath":"/props/label","operation":"REPLACE",'
            + '"value":"AI confirmed label"}]}',
        }),
      }),
    );

    render(<UnifiedDesignerPage />);

    fireEvent.click(await screen.findByTestId('designer-ai-copilot'));
    fireEvent.change(screen.getByTestId('governed-ai-description'), {
      target: { value: 'Rename the customer field' },
    });
    fireEvent.click(screen.getByTestId('governed-ai-proposal-generate'));

    await screen.findByTestId('governed-ai-proposal-review');
    expect(screen.getByTestId('canvas-block-field_customer_name')).toHaveTextContent(
      'Customer name',
    );
    expect(screen.getByTestId('canvas-block-field_customer_name')).not.toHaveTextContent(
      'AI confirmed label',
    );
    expect(applyAuthoringStudioBatch).not.toHaveBeenCalled();
    expect(createAuthoringAiPatchProposal).toHaveBeenCalledWith(
      'session_1',
      3,
      expect.arrayContaining([
        expect.objectContaining({
          blockId: 'field_customer_name',
          propertyPath: '/props/label',
          manifestChecksum: 'field-checksum',
        }),
      ]),
    );

    fireEvent.click(screen.getByTestId('governed-ai-proposal-apply'));

    await waitFor(() =>
      expect(screen.getByTestId('canvas-block-field_customer_name')).toHaveTextContent(
        'AI confirmed label',
      ),
    );
    expect(screen.getByTestId('studio-handoff-context')).toHaveTextContent('修订 r4');
    expect(applyAuthoringAiPatchProposal).toHaveBeenCalledWith(
      'session_1',
      'proposal_1',
      3,
    );
  });

  it('stops a stale Studio save and requires an explicit Base Mine Latest resolution', async () => {
    setSearch('?contextId=ctx_secure_once');
    const handoff = createHandoff('list_customer', '/dataSource');
    const baseline = createDocument('document_one', 'Isolated Draft');
    const latest = createDocument('document_one', 'Isolated Draft');
    const latestList = findBlock(latest.blocks, 'list_customer');
    if (!latestList) throw new Error('list_customer fixture missing');
    latestList.dataSource = { model: 'refund' };
    const saved = createDocument('document_one', 'Isolated Draft');
    const savedList = findBlock(saved.blocks, 'list_customer');
    if (!savedList) throw new Error('list_customer fixture missing');
    savedList.dataSource = { model: 'payment' };

    vi.mocked(consumeAuthoringHandoff).mockResolvedValue(handoff);
    vi.mocked(loadAuthoringSession)
      .mockResolvedValueOnce(createAuthoringSession(baseline, 3))
      .mockResolvedValueOnce(createAuthoringSession(latest, 4));
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(applyAuthoringStudioBatch)
      .mockRejectedValueOnce(new Error('authoring.revision.conflict'))
      .mockResolvedValueOnce({
        session: createAuthoringSession(saved, 5, 'L3', 'HANDOFF_STUDIO'),
        changeItemPids: ['item_conflict_resolution'],
      });

    render(<UnifiedDesignerPage />);

    await screen.findByTestId('studio-handoff-context');
    await waitFor(() =>
      expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('list_customer'),
    );
    fireEvent.change(screen.getByTestId('inspector-field-dataSource.model-manual'), {
      target: { value: 'payment' },
    });
    fireEvent.click(screen.getByTestId('designer-save'));

    expect(await screen.findByTestId('authoring-conflict-panel')).toHaveTextContent(
      'Base / Mine / Latest',
    );
    expect(screen.getByTestId('authoring-conflict-0')).toHaveTextContent('customer');
    expect(screen.getByTestId('authoring-conflict-0')).toHaveTextContent('payment');
    expect(screen.getByTestId('authoring-conflict-0')).toHaveTextContent('refund');
    expect(screen.getByTestId('designer-save')).toBeDisabled();
    expect(applyAuthoringStudioBatch).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByLabelText('保留 Mine'));
    fireEvent.click(screen.getByTestId('authoring-conflict-apply'));

    await waitFor(() => expect(applyAuthoringStudioBatch).toHaveBeenCalledTimes(2));
    expect(applyAuthoringStudioBatch).toHaveBeenLastCalledWith(
      'session_1',
      4,
      expect.objectContaining({
        patches: [expect.objectContaining({
          blockId: 'list_customer',
          propertyPath: '/dataSource',
          operation: 'REPLACE',
          value: { model: 'payment' },
          manifestChecksum: 'list-checksum',
        })],
      }),
    );
    await waitFor(() =>
      expect(screen.queryByTestId('authoring-conflict-panel')).not.toBeInTheDocument(),
    );
    expect(screen.getByTestId('studio-handoff-context')).toHaveTextContent('修订 r5');
  });

  it('accepts an atomic Studio save that the authoritative draft proves committed after response loss', async () => {
    setSearch('?contextId=ctx_secure_once');
    const handoff = createHandoff('list_customer', '/dataSource');
    const baseline = createDocument('document_one', 'Isolated Draft');
    const committed = createDocument('document_one', 'Isolated Draft');
    const committedList = findBlock(committed.blocks, 'list_customer');
    if (!committedList) throw new Error('list_customer fixture missing');
    committedList.dataSource = { model: 'payment' };

    vi.mocked(consumeAuthoringHandoff).mockResolvedValue(handoff);
    vi.mocked(loadAuthoringSession)
      .mockResolvedValueOnce(createAuthoringSession(baseline, 3))
      .mockResolvedValueOnce(createAuthoringSession(committed, 4, 'L3', 'HANDOFF_STUDIO'));
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(applyAuthoringStudioBatch).mockRejectedValueOnce(new Error('Failed to fetch'));

    render(<UnifiedDesignerPage />);

    await screen.findByTestId('studio-handoff-context');
    await waitFor(() =>
      expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('list_customer'),
    );
    fireEvent.change(screen.getByTestId('inspector-field-dataSource.model-manual'), {
      target: { value: 'payment' },
    });
    fireEvent.click(screen.getByTestId('designer-save'));

    await waitFor(() => expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('已保存'));
    expect(screen.getByTestId('studio-handoff-context')).toHaveTextContent('修订 r4');
    expect(screen.getByTestId('studio-save-reconciliation-feedback')).toHaveTextContent(
      '保存已在服务端完成',
    );
    expect(screen.queryByTestId('designer-save-error')).not.toBeInTheDocument();
    expect(screen.queryByTestId('authoring-conflict-panel')).not.toBeInTheDocument();
    expect(applyAuthoringStudioBatch).toHaveBeenCalledTimes(1);
  });

  it('confirms an atomic Studio save before turning read-only when authority moved', async () => {
    setSearch('?contextId=ctx_secure_once');
    const handoff = createHandoff('list_customer', '/dataSource');
    const baseline = createDocument('document_one', 'Isolated Draft');
    const committed = createDocument('document_one', 'Isolated Draft');
    const committedList = findBlock(committed.blocks, 'list_customer');
    if (!committedList) throw new Error('list_customer fixture missing');
    committedList.dataSource = { model: 'payment' };
    const transferred = createAuthoringSession(committed, 4, 'L3', 'HANDOFF_STUDIO');
    transferred.writerLease = {
      status: 'HELD_BY_OTHER',
      revision: 5,
      leasedUntil: '2026-08-09T12:10:00Z',
    };

    vi.mocked(consumeAuthoringHandoff).mockResolvedValue(handoff);
    vi.mocked(loadAuthoringSession)
      .mockResolvedValueOnce(createAuthoringSession(baseline, 3))
      .mockResolvedValueOnce(transferred);
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(applyAuthoringStudioBatch).mockRejectedValueOnce(new Error('Failed to fetch'));

    render(<UnifiedDesignerPage />);

    await screen.findByTestId('studio-handoff-context');
    await waitFor(() =>
      expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('list_customer'),
    );
    fireEvent.change(screen.getByTestId('inspector-field-dataSource.model-manual'), {
      target: { value: 'payment' },
    });
    fireEvent.click(screen.getByTestId('designer-save'));

    await waitFor(() => expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('已保存'));
    expect(screen.getByTestId('studio-save-reconciliation-feedback')).toHaveAttribute(
      'data-tone',
      'warning',
    );
    expect(screen.getByTestId('studio-save-reconciliation-feedback')).toHaveTextContent(
      '保存已在服务端完成；编辑权已转移到其他会话',
    );
    expect(screen.getByTestId('studio-handoff-read-only-reason')).toBeInTheDocument();
    expect(screen.queryByTestId('designer-save-error')).not.toBeInTheDocument();
    expect(applyAuthoringStudioBatch).toHaveBeenCalledTimes(1);
  });

  it('confirms the atomic Studio save and turns read-only when permission changes during reconciliation', async () => {
    setSearch('?contextId=ctx_secure_once');
    const handoff = createHandoff('list_customer', '/dataSource');
    const baseline = createDocument('document_one', 'Isolated Draft');
    const committed = createDocument('document_one', 'Isolated Draft');
    const committedList = findBlock(committed.blocks, 'list_customer');
    if (!committedList) throw new Error('list_customer fixture missing');
    committedList.dataSource = { model: 'payment' };

    vi.mocked(consumeAuthoringHandoff).mockResolvedValue(handoff);
    vi.mocked(loadAuthoringSession)
      .mockResolvedValueOnce(createAuthoringSession(baseline, 3))
      .mockResolvedValueOnce(createAuthoringSession(committed, 4, 'L3', 'HANDOFF_STUDIO'));
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(loadAuthoringPermissionSnapshot).mockResolvedValueOnce({
      canReadDesigner: true,
      canManageDesigner: true,
      canAdministerDesigner: false,
    });
    vi.mocked(applyAuthoringStudioBatch).mockRejectedValueOnce(new Error('Failed to fetch'));

    render(<UnifiedDesignerPage />);

    await screen.findByTestId('studio-handoff-context');
    await waitFor(() =>
      expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('list_customer'),
    );
    fireEvent.change(screen.getByTestId('inspector-field-dataSource.model-manual'), {
      target: { value: 'payment' },
    });
    fireEvent.click(screen.getByTestId('designer-save'));

    await waitFor(() => expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('已保存'));
    expect(screen.getByTestId('studio-save-reconciliation-feedback')).toHaveAttribute(
      'data-tone',
      'warning',
    );
    expect(screen.getByTestId('studio-save-reconciliation-feedback')).toHaveTextContent(
      '保存已在服务端完成；应用设计中心高级配置权限已收回',
    );
    expect(screen.getByTestId('studio-handoff-read-only-reason')).toHaveTextContent(
      '缺少高级设计权限',
    );
    expect(screen.queryByTestId('designer-save-error')).not.toBeInTheDocument();
    expect(screen.getByTestId('designer-save')).toBeDisabled();
    expect(applyAuthoringStudioBatch).toHaveBeenCalledTimes(1);
  });

  it('keeps the Studio document dirty when permission is revoked before the save commits', async () => {
    setSearch('?contextId=ctx_secure_once');
    const handoff = createHandoff('list_customer', '/dataSource');
    const baseline = createDocument('document_one', 'Isolated Draft');
    const session = createAuthoringSession(baseline, 3);

    vi.mocked(consumeAuthoringHandoff).mockResolvedValue(handoff);
    vi.mocked(loadAuthoringSession).mockResolvedValue(session);
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(loadAuthoringPermissionSnapshot).mockResolvedValueOnce({
      canReadDesigner: true,
      canManageDesigner: true,
      canAdministerDesigner: false,
    });
    vi.mocked(applyAuthoringStudioBatch).mockRejectedValueOnce(new Error('403 Forbidden'));

    render(<UnifiedDesignerPage />);

    await screen.findByTestId('studio-handoff-context');
    await waitFor(() =>
      expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('list_customer'),
    );
    fireEvent.change(screen.getByTestId('inspector-field-dataSource.model-manual'), {
      target: { value: 'payment' },
    });
    fireEvent.click(screen.getByTestId('designer-save'));

    expect(await screen.findByTestId('designer-save-error')).toHaveTextContent(
      '保存未完成；应用设计中心高级配置权限已收回，本地未保存变更已保留且未重放',
    );
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('保存失败');
    expect(screen.getByTestId('studio-handoff-read-only-reason')).toHaveTextContent(
      '缺少高级设计权限',
    );
    expect(screen.getByTestId('designer-save')).toBeDisabled();
    expect(applyAuthoringStudioBatch).toHaveBeenCalledTimes(1);

    vi.mocked(loadAuthoringPermissionSnapshot).mockResolvedValue({
      canReadDesigner: true,
      canManageDesigner: true,
      canAdministerDesigner: true,
    });
    fireEvent(window, new Event('focus'));

    await waitFor(() =>
      expect(screen.getByTestId('studio-handoff-editable-reason')).toBeInTheDocument(),
    );
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('保存失败');
    expect(screen.getByTestId('designer-save')).toBeEnabled();
  });

  it('keeps the Studio document dirty when the authoritative reload also fails', async () => {
    setSearch('?contextId=ctx_secure_once');
    const handoff = createHandoff('list_customer', '/dataSource');
    const baseline = createDocument('document_one', 'Isolated Draft');

    vi.mocked(consumeAuthoringHandoff).mockResolvedValue(handoff);
    vi.mocked(loadAuthoringSession)
      .mockResolvedValueOnce(createAuthoringSession(baseline, 3))
      .mockRejectedValueOnce(new Error('Network error'));
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(applyAuthoringStudioBatch).mockRejectedValueOnce(new Error('Failed to fetch'));

    render(<UnifiedDesignerPage />);

    await screen.findByTestId('studio-handoff-context');
    await waitFor(() =>
      expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('list_customer'),
    );
    fireEvent.change(screen.getByTestId('inspector-field-dataSource.model-manual'), {
      target: { value: 'payment' },
    });
    fireEvent.click(screen.getByTestId('designer-save'));

    expect(await screen.findByTestId('designer-save-error')).toHaveTextContent(
      '保存结果暂时无法确认；无法读取权威草稿，请联网后重试',
    );
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('保存失败');
    expect(screen.getByTestId('inspector-field-dataSource.model-manual')).toHaveValue('payment');
    expect(screen.getByTestId('designer-save')).toBeEnabled();
    expect(applyAuthoringStudioBatch).toHaveBeenCalledTimes(1);
  });

  it('restores a Studio Mine after repeated authoritative GET failures and a page-process restart', async () => {
    setSearch('?contextId=ctx_secure_once');
    const handoff = createHandoff('list_customer', '/dataSource');
    const baseline = createDocument('document_one', 'Isolated Draft');
    const baselineSession = createAuthoringSession(baseline, 3);

    vi.mocked(consumeAuthoringHandoff).mockResolvedValue(handoff);
    vi.mocked(loadAuthoringSession)
      .mockResolvedValueOnce(baselineSession)
      .mockRejectedValueOnce(new Error('Network error'));
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(applyAuthoringStudioBatch).mockRejectedValueOnce(new Error('Failed to fetch'));

    const firstPage = render(<UnifiedDesignerPage />);
    await screen.findByTestId('studio-handoff-context');
    await waitFor(() =>
      expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('list_customer'),
    );
    fireEvent.change(screen.getByTestId('inspector-field-dataSource.model-manual'), {
      target: { value: 'payment' },
    });
    fireEvent.click(screen.getByTestId('designer-save'));
    await screen.findByText(/保存结果暂时无法确认/);
    expect(applyAuthoringStudioBatch).toHaveBeenCalledTimes(1);

    firstPage.unmount();
    setSearch('?authoringSession=session_1');
    vi.mocked(loadAuthoringSession).mockReset();
    vi.mocked(loadAuthoringSession)
      .mockRejectedValueOnce(new Error('Network still unavailable'))
      .mockResolvedValueOnce(baselineSession);
    render(<UnifiedDesignerPage />);

    expect(await screen.findByText('权威草稿暂不可读，本地 Studio 文档仍保留')).toBeInTheDocument();
    expect(screen.getByText('恢复只会重新读取并对账，不会自动重放保存请求。')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('studio-local-recovery-retry'));

    await screen.findByTestId('studio-handoff-context');
    expect(await screen.findByTestId('studio-save-reconciliation-feedback')).toHaveTextContent(
      '已恢复页面进程中断前的完整 Studio 文档，并确认尚未写入权威草稿',
    );
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
    fireEvent.click(screen.getByTestId('outline-item-list_customer'));
    expect(screen.getByTestId('inspector-field-dataSource.model-manual')).toHaveValue('payment');
    expect(applyAuthoringStudioBatch).toHaveBeenCalledTimes(1);

    const committed = createDocument('document_one', 'Isolated Draft');
    const committedList = findBlock(committed.blocks, 'list_customer');
    if (!committedList) throw new Error('list_customer fixture missing');
    committedList.dataSource = { model: 'payment' };
    vi.mocked(applyAuthoringStudioBatch).mockResolvedValueOnce({
      session: createAuthoringSession(committed, 4, 'L3', 'HANDOFF_STUDIO'),
      changeItemPids: ['item_1'],
    });
    fireEvent.click(screen.getByTestId('designer-save'));

    await waitFor(() =>
      expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('已保存'),
    );
    expect(applyAuthoringStudioBatch).toHaveBeenCalledTimes(2);
    expect(readStudioAuthoringRecovery('1', 'session_1')).toBeNull();
  });

  it('preserves an unsaved Studio edit but blocks writes when admin permission is revoked', async () => {
    setSearch('?contextId=ctx_secure_once');
    const handoff = createHandoff('list_customer', '/dataSource');
    const session = createAuthoringSession(createDocument('document_one', 'Isolated Draft'));
    vi.mocked(consumeAuthoringHandoff).mockResolvedValue(handoff);
    vi.mocked(loadAuthoringSession).mockResolvedValue(session);
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());

    const view = render(<UnifiedDesignerPage />);

    await screen.findByTestId('studio-handoff-editable-reason');
    await waitFor(() =>
      expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('list_customer'),
    );
    fireEvent.change(screen.getByTestId('inspector-field-dataSource.model-manual'), {
      target: { value: 'payment' },
    });
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');

    permissionMock.canAdministerDesigner.mockReturnValue(false);
    view.rerender(<UnifiedDesignerPage />);

    expect(screen.getByTestId('studio-handoff-read-only-reason')).toHaveTextContent(
      '当前仅可查看隔离草稿',
    );
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
    expect(screen.getByTestId('designer-save')).toBeDisabled();
    expect(screen.queryByTestId('inspector-field-dataSource.model-manual')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('designer-save'));
    expect(applyAuthoringStudioBatch).not.toHaveBeenCalled();
    expect(savePageSchemaV3).not.toHaveBeenCalled();

    permissionMock.canAdministerDesigner.mockReturnValue(true);
    view.rerender(<UnifiedDesignerPage />);
    expect(screen.getByTestId('studio-handoff-editable-reason')).toBeInTheDocument();
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
    await waitFor(() => expect(screen.getByTestId('designer-save')).toBeEnabled());
    expect(screen.getByTestId('inspector-field-dataSource.model-manual')).toBeEnabled();
    expect(screen.getByTestId('inspector-field-dataSource.model-manual')).toHaveValue('payment');
  });

  it('saves a declared same-parent block reorder through the atomic Studio batch', async () => {
    setSearch('?contextId=ctx_secure_once');
    const handoff = createHandoff('field_customer_name', '/$structure/order');
    const baseline = createDocument('document_one', 'Isolated Draft');
    const session = createAuthoringSession(baseline);
    const saved = createDocument('document_one', 'Isolated Draft');
    const section = findBlock(saved.blocks, 'section_basic');
    if (!section?.blocks) throw new Error('section_basic fixture missing');
    const [name, phone, ...rest] = section.blocks;
    section.blocks = [phone, name, ...rest];

    vi.mocked(consumeAuthoringHandoff).mockResolvedValue(handoff);
    vi.mocked(loadAuthoringSession).mockResolvedValue(session);
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(applyAuthoringStudioBatch).mockResolvedValue({
      session: createAuthoringSession(saved, 4, 'L1', 'GUIDED_INLINE'),
      changeItemPids: ['item_move_1'],
    });

    render(<UnifiedDesignerPage />);

    await screen.findByTestId('studio-handoff-context');
    await waitFor(() =>
      expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent(
        'field_customer_name',
      ),
    );
    fireEvent.click(screen.getByTestId('designer-mode-layout'));
    fireEvent.click(await screen.findByTestId('block-move-down-field_customer_name'));
    fireEvent.click(screen.getByTestId('designer-save'));

    await waitFor(() =>
      expect(applyAuthoringStudioBatch).toHaveBeenCalledWith(
        'session_1',
        3,
        expect.objectContaining({
          patches: [],
          moves: [{
            blockId: 'field_customer_phone',
            beforeBlockId: 'field_customer_name',
            manifestChecksum: 'field-checksum',
          }],
        }),
      ),
    );
    expect(savePageSchemaV3).not.toHaveBeenCalled();
    expect(screen.getByTestId('studio-handoff-context')).toHaveTextContent('修订 r4');
  });

  it('keeps the source workspace continuous after a governed ChangeSet split', async () => {
    setSearch('?authoringSession=session_1');
    const original = createAuthoringSession(createDocument('document_one', 'Mixed Changes'));
    const source = createAuthoringSession(createDocument('document_one', 'Source After Split'), 4);
    const target = {
      ...createAuthoringSession(createDocument('document_one', 'Target Split'), 2, 'L3', 'HANDOFF_STUDIO'),
      sessionPid: 'session_target',
      changeSetPid: 'changeset_target',
    };
    const items = createSplitItems();
    const split: AuthoringSplitResult = {
      sourceSession: source,
      targetSession: target,
      sourceItems: [items[0]],
      targetItems: [{ ...items[1], sourceChangeItemPid: 'item_l3' }],
      lineage: [{ changeSetPid: 'changeset_1', revision: 3, relation: 'SPLIT_FROM' }],
    };
    vi.mocked(loadAuthoringSession).mockResolvedValue(original);
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue(createCapabilities());
    vi.mocked(loadAuthoringChangeItems).mockResolvedValue(items);
    vi.mocked(splitAuthoringChangeSet).mockResolvedValue(split);

    render(<UnifiedDesignerPage />);

    expect(await screen.findByText('Mixed Changes')).toBeInTheDocument();
    expect(await screen.findByTestId('authoring-split-panel')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('authoring-split-item-item_l3'));
    fireEvent.change(screen.getByTestId('authoring-split-reason'), {
      target: { value: '高风险数据源独立评审' },
    });
    fireEvent.click(screen.getByTestId('authoring-split-submit'));

    expect(await screen.findByText('Source After Split')).toBeInTheDocument();
    expect(screen.getByTestId('studio-handoff-context')).toHaveTextContent('修订 r4');
    expect(screen.getByTestId('authoring-split-target-link')).toHaveAttribute(
      'href',
      expect.stringContaining('authoringSession=session_target'),
    );
  });
});

function setSearch(search: string) {
  vi.mocked(useSearchParams).mockReturnValue([
    new URLSearchParams(search),
    vi.fn(),
  ] as unknown as ReturnType<typeof useSearchParams>);
}

function createDocument(id: string, title: string): PageSchemaV3 {
  const document = JSON.parse(JSON.stringify(samplePageSchemaV3)) as PageSchemaV3;
  return {
    ...document,
    id,
    pageKey: id,
    title,
  };
}

function createHandoff(blockId: string, propertyPath: string): HandoffContext {
  return {
    pagePid: 'page_1',
    changeSetPid: 'changeset_1',
    sessionPid: 'session_1',
    revision: 3,
    intent: 'PAGE_STRUCTURE',
    targetRoute: '/unified-designer',
    returnTo: '/orders?tab=open',
    blockId,
    propertyPath,
    interactionContext: { route: '/orders?tab=open' },
    expiresAt: '2026-08-09T12:00:00Z',
  };
}

function createAuthoringSession(
  document: PageSchemaV3,
  revision = 3,
  riskLevel = 'L0',
  route = 'INLINE',
): AuthoringSession {
  return {
    sessionPid: 'session_1',
    changeSetPid: 'changeset_1',
    pagePid: 'page_1',
    ownerUserId: 1,
    changeSetStatus: 'DRAFT',
    workspaceMode: 'AUTHORING',
    state: 'ACTIVE',
    revision,
    riskLevel,
    route,
    publishPolicy: route === 'HANDOFF_STUDIO' ? 'STUDIO_APPROVAL' : 'DIRECT_ALLOWED',
    validationState: 'UNVALIDATED',
    impactState: 'UNKNOWN',
    approvalState: route === 'HANDOFF_STUDIO' ? 'REQUIRED' : 'NOT_REQUIRED',
    publishState: 'DRAFT',
    manifestChecksum: 'registry-checksum',
    snapshot: { ...(document as unknown as Record<string, unknown>), pid: 'page_1' },
    interactionContext: { route: '/orders?tab=open' },
    expiresAt: '2026-08-09T12:00:00Z',
  };
}

function createCapabilities(): CapabilityRegistry {
  return {
    checksum: 'registry-checksum',
    manifests: [
      {
        blockType: 'field',
        pluginCode: 'core.designer',
        pluginVersion: '0.1.0',
        manifestVersion: '1',
        checksum: 'field-checksum',
        properties: {
          '/props/label': propertyCapability('/props/label', 'INLINE'),
          '/layout/span': propertyCapability('/layout/span', 'INLINE'),
          '/$structure/order': propertyCapability(
            '/$structure/order',
            'GUIDED_INLINE',
            ['MOVE'],
          ),
        },
      },
      {
        blockType: 'list',
        pluginCode: 'core.designer',
        pluginVersion: '0.1.0',
        manifestVersion: '1',
        checksum: 'list-checksum',
        properties: {
          '/dataSource': propertyCapability('/dataSource', 'HANDOFF_STUDIO'),
          '/$structure/order': propertyCapability(
            '/$structure/order',
            'GUIDED_INLINE',
            ['MOVE'],
          ),
        },
      },
    ],
  };
}

function createAiProposal(): AuthoringAiPatchProposal {
  return {
    proposalPid: 'proposal_1',
    sourceSessionPid: 'session_1',
    changeSetPid: 'changeset_1',
    pagePid: 'page_1',
    baseRevision: 3,
    registryChecksum: 'registry-checksum',
    proposalHash: 'proposal-hash',
    status: 'PROPOSED',
    aggregateRisk: 'L0',
    aggregateRoute: 'INLINE',
    publishPolicy: 'DIRECT_ALLOWED',
    typedPatchOnly: true,
    requiresHumanApproval: true,
    items: [
      {
        ordinal: 1,
        blockId: 'field_customer_name',
        propertyPath: '/props/label',
        operation: 'REPLACE',
        previousValue: 'Customer name',
        value: 'AI confirmed label',
        manifestChecksum: 'field-checksum',
        decision: {
          route: 'INLINE',
          risk: 'L0',
          publishPolicy: 'DIRECT_ALLOWED',
          reason: 'CAPABILITY_ALLOWED',
          manifestChecksum: 'field-checksum',
          rolePreviewRequired: false,
        },
      },
    ],
    createdAt: '2026-08-09T00:00:00Z',
  };
}

function findTestBlock(blocks: PageSchemaV3['blocks'], blockId: string): PageSchemaV3['blocks'][number] {
  for (const block of blocks) {
    if (block.id === blockId) return block;
    const child = block.blocks ? findTestBlockOrNull(block.blocks, blockId) : null;
    if (child) return child;
  }
  throw new Error(`Missing test block ${blockId}`);
}

function findTestBlockOrNull(
  blocks: PageSchemaV3['blocks'],
  blockId: string,
): PageSchemaV3['blocks'][number] | null {
  for (const block of blocks) {
    if (block.id === blockId) return block;
    const child = block.blocks ? findTestBlockOrNull(block.blocks, blockId) : null;
    if (child) return child;
  }
  return null;
}

function createSplitItems(): AuthoringChangeItem[] {
  return [
    {
      changeItemPid: 'item_l0',
      blockId: 'field_customer_name',
      propertyPath: '/props/label',
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
      changeItemPid: 'item_l3',
      blockId: 'list_customers',
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

function propertyCapability(
  propertyPath: string,
  route: string,
  allowedOperations = ['ADD', 'REPLACE', 'REMOVE'],
) {
  return {
    propertyPath,
    allowedOperations,
    route,
    risk: route === 'HANDOFF_STUDIO' ? 'L3' : 'L1',
    effectTags: route === 'HANDOFF_STUDIO' ? ['DATA_BINDING'] : ['PRESENTATION'],
    reversibility: 'REVERSIBLE',
    protectedSemantic: false,
    rolePreviewRequired: route === 'HANDOFF_STUDIO',
  };
}

function findBlock(
  blocks: PageSchemaV3['blocks'],
  blockId: string,
): PageSchemaV3['blocks'][number] | null {
  for (const block of blocks) {
    if (block.id === blockId) return block;
    const nested = findBlock(block.blocks ?? [], blockId);
    if (nested) return nested;
  }
  return null;
}
