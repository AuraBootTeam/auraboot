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
  applyAuthoringStudioPatch,
  consumeAuthoringHandoff,
  createAuthoringNewPageWorkspace,
  createAuthoringAiPatchProposal,
  loadAuthoringCapabilities,
  loadAuthoringNewPageWorkspaceOptions,
  loadAuthoringChangeItems,
  loadAuthoringReleaseHistory,
  loadAuthoringRolePreviewTargets,
  loadAuthoringRoleStructurePreview,
  loadAuthoringReviewWorkspace,
  loadAuthoringSession,
  moveAuthoringStudioBlock,
  openAuthoringReviewWorkspace,
  observeAuthoringChangeSet,
  prepareAuthoringSession,
  publishAuthoringChangeSet,
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
  endAuthoringIdentitySimulation: vi.fn(),
  applyAuthoringAiPatchProposal: vi.fn(),
  applyAuthoringStudioPatch: vi.fn(),
  consumeAuthoringHandoff: vi.fn(),
  createAuthoringNewPageWorkspace: vi.fn(),
  createAuthoringAiPatchProposal: vi.fn(),
  loadAuthoringCapabilities: vi.fn(),
  loadAuthoringNewPageWorkspaceOptions: vi.fn(),
  loadAuthoringIdentitySimulation: vi.fn(),
  loadAuthoringChangeItems: vi.fn(),
  loadAuthoringReleaseHistory: vi.fn(),
  loadAuthoringRolePreviewTargets: vi.fn(),
  loadAuthoringRoleStructurePreview: vi.fn(),
  loadAuthoringSyntheticPreview: vi.fn(),
  loadAuthoringReviewWorkspace: vi.fn(),
  loadAuthoringSession: vi.fn(),
  moveAuthoringStudioBlock: vi.fn(),
  observeAuthoringChangeSet: vi.fn(),
  prepareAuthoringSession: vi.fn(),
  publishAuthoringChangeSet: vi.fn(),
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

describe('UnifiedDesignerPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(loadModelFieldsByModelCodes).mockResolvedValue({});
    vi.mocked(savePageSchemaV3).mockResolvedValue({
      ok: true,
      source: { type: 'page', pid: 'page_1', pageKey: 'document_one' },
    });
    vi.mocked(consumeAuthoringHandoff).mockReset();
    vi.mocked(loadAuthoringSession).mockReset();
    vi.mocked(loadAuthoringCapabilities).mockReset();
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
    vi.mocked(applyAuthoringStudioPatch).mockReset();
    vi.mocked(createAuthoringAiPatchProposal).mockReset();
    vi.mocked(moveAuthoringStudioBlock).mockReset();
    vi.mocked(observeAuthoringChangeSet).mockReset();
    vi.mocked(prepareAuthoringSession).mockReset();
    vi.mocked(publishAuthoringChangeSet).mockReset();
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

    expect(await screen.findByTestId('studio-handoff-context')).toHaveTextContent(
      'ChangeSet changeset_1',
    );
    expect(consumeAuthoringHandoff).toHaveBeenCalledWith('ctx_secure_once');
    expect(loadAuthoringSession).toHaveBeenCalledWith('session_1');
    expect(loadPageSchemaV3).not.toHaveBeenCalled();
    expect(screen.getByTestId('studio-handoff-context')).toHaveTextContent('Isolated Draft');
    expect(screen.getByTestId('authoring-ownership-notice')).toHaveTextContent(
      '正在编辑租户派生层',
    );
    expect(screen.getByTestId('authoring-ownership-notice')).toHaveTextContent(
      '来源页面 page_1保持不变',
    );
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
    expect(applyAuthoringStudioPatch).not.toHaveBeenCalled();
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
    fireEvent.change(screen.getByLabelText('父菜单'), { target: { value: 'manufacturing' } });
    fireEvent.change(screen.getByLabelText('访问权限'), {
      target: { value: 'page.production_exception.read' },
    });
    fireEvent.click(screen.getByRole('button', { name: '创建并进入页面设计' }));

    await waitFor(() =>
      expect(createAuthoringNewPageWorkspace).toHaveBeenCalledWith('session_1', 3, {
        pageKey: 'production_exception',
        name: 'production_exception',
        title: '生产异常看板',
        description: undefined,
        kind: 'list',
        parentMenuCode: 'manufacturing',
        menuCode: 'production_exception',
        menuName: '生产异常看板',
        menuPath: '/production-exception',
        menuIcon: undefined,
        permissionCode: 'page.production_exception.read',
      }),
    );
    expect(await screen.findByTestId('studio-handoff-context')).toHaveTextContent(
      'ChangeSet changeset_new_page',
    );
    expect(screen.queryByTestId('new-page-workspace-wizard')).not.toBeInTheDocument();
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

    fireEvent.click(await screen.findByTestId('authoring-governance-publish'));
    expect(await screen.findByRole('alert')).toHaveTextContent('活动版本未改变');
    expect(screen.getByTestId('authoring-governance-publish')).toBeEnabled();
    expect(screen.getByText('revision r7 已批准')).toBeInTheDocument();
    expect(loadAuthoringSession).toHaveBeenCalledTimes(1);
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

    render(<UnifiedDesignerPage />);

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

    fireEvent.click(screen.getByTestId('authoring-conflict-use-latest'));

    await waitFor(() =>
      expect(screen.queryByTestId('authoring-conflict-panel')).not.toBeInTheDocument(),
    );
    expect(loadAuthoringSession).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId('studio-handoff-context')).toHaveTextContent('修订 r5');
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
    vi.mocked(applyAuthoringStudioPatch).mockResolvedValue({
      session: createAuthoringSession(saved, 4, 'L3', 'HANDOFF_STUDIO'),
      changeItemPid: 'item_1',
      decision: {
        route: 'HANDOFF_STUDIO',
        risk: 'L3',
        publishPolicy: 'STUDIO_APPROVAL',
        reason: 'CAPABILITY_ALLOWED',
        manifestChecksum: 'list-checksum',
        rolePreviewRequired: true,
      },
      previousValue: { model: 'customer' },
      savedValue: { model: 'payment' },
    });

    render(<UnifiedDesignerPage />);

    expect(await screen.findByTestId('studio-handoff-editable-reason')).toHaveTextContent(
      '写回同一隔离草稿',
    );
    expect(screen.getByTestId('designer-contextual-restricted')).toHaveTextContent(
      '同一 ChangeSet',
    );
    expect(loadPageSchemaV3).not.toHaveBeenCalled();
    expect(screen.queryByTestId('designer-kind-switch')).not.toBeInTheDocument();
    expect(screen.queryByTestId('designer-export')).not.toBeInTheDocument();
    expect(screen.queryByTestId('designer-import')).not.toBeInTheDocument();
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
      expect(applyAuthoringStudioPatch).toHaveBeenCalledWith(
        'session_1',
        3,
        'list_customer',
        '/dataSource',
        'REPLACE',
        { model: 'payment' },
        'list-checksum',
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
    expect(applyAuthoringStudioPatch).not.toHaveBeenCalled();
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
    vi.mocked(applyAuthoringStudioPatch)
      .mockRejectedValueOnce(new Error('authoring.revision.conflict'))
      .mockResolvedValueOnce({
        session: createAuthoringSession(saved, 5, 'L3', 'HANDOFF_STUDIO'),
        changeItemPid: 'item_conflict_resolution',
        decision: {
          route: 'HANDOFF_STUDIO',
          risk: 'L3',
          publishPolicy: 'STUDIO_APPROVAL',
          reason: 'CAPABILITY_ALLOWED',
          manifestChecksum: 'list-checksum',
          rolePreviewRequired: true,
        },
        previousValue: { model: 'refund' },
        savedValue: { model: 'payment' },
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
    expect(applyAuthoringStudioPatch).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByLabelText('保留 Mine'));
    fireEvent.click(screen.getByTestId('authoring-conflict-apply'));

    await waitFor(() => expect(applyAuthoringStudioPatch).toHaveBeenCalledTimes(2));
    expect(applyAuthoringStudioPatch).toHaveBeenLastCalledWith(
      'session_1',
      4,
      'list_customer',
      '/dataSource',
      'REPLACE',
      { model: 'payment' },
      'list-checksum',
    );
    await waitFor(() =>
      expect(screen.queryByTestId('authoring-conflict-panel')).not.toBeInTheDocument(),
    );
    expect(screen.getByTestId('studio-handoff-context')).toHaveTextContent('修订 r5');
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
    expect(applyAuthoringStudioPatch).not.toHaveBeenCalled();
    expect(moveAuthoringStudioBlock).not.toHaveBeenCalled();
    expect(savePageSchemaV3).not.toHaveBeenCalled();

    permissionMock.canAdministerDesigner.mockReturnValue(true);
    view.rerender(<UnifiedDesignerPage />);
    expect(screen.getByTestId('studio-handoff-editable-reason')).toBeInTheDocument();
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
    expect(screen.getByTestId('designer-save')).toBeEnabled();
    expect(screen.getByTestId('inspector-field-dataSource.model-manual')).toBeEnabled();
    expect(screen.getByTestId('inspector-field-dataSource.model-manual')).toHaveValue('payment');
  });

  it('saves a declared same-parent block reorder through the typed Studio move endpoint', async () => {
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
    vi.mocked(moveAuthoringStudioBlock).mockResolvedValue({
      session: createAuthoringSession(saved, 4, 'L1', 'GUIDED_INLINE'),
      changeItemPid: 'item_move_1',
      decision: {
        route: 'GUIDED_INLINE',
        risk: 'L1',
        publishPolicy: 'DEFAULT_REVIEW',
        reason: 'CAPABILITY_ALLOWED',
        manifestChecksum: 'field-checksum',
        rolePreviewRequired: false,
      },
      previousValue: { beforeBlockId: rest[0]?.id ?? null },
      savedValue: { beforeBlockId: name.id },
    });

    render(<UnifiedDesignerPage />);

    await screen.findByTestId('studio-handoff-context');
    fireEvent.click(screen.getByTestId('designer-mode-layout'));
    fireEvent.click(screen.getByTestId('block-move-down-field_customer_name'));
    fireEvent.click(screen.getByTestId('designer-save'));

    await waitFor(() =>
      expect(moveAuthoringStudioBlock).toHaveBeenCalledWith(
        'session_1',
        3,
        'field_customer_phone',
        'field_customer_name',
        'field-checksum',
      ),
    );
    expect(applyAuthoringStudioPatch).not.toHaveBeenCalled();
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
