import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ContextualAuthoringSurface } from '../ContextualAuthoringSurface';
import {
  applyAuthoringPatch,
  createAuthoringHandoff,
  loadAuthoringCapabilities,
  loadAuthoringSession,
  openAuthoringSession,
  prepareAuthoringSession,
  renewAuthoringWriterLease,
  submitAuthoringSession,
  takeoverAuthoringWriterLease,
  transitionAuthoringGovernance,
} from '../authoringService';
import type { UnifiedSchema } from '~/framework/meta/schemas/types';
import type { AuthoringSession } from '../types';
import { storeAuthoringConflictTransfer } from '../authoringConflictTransfer';

const permissionMock = vi.hoisted(() => ({
  canRead: true,
  canManage: true,
  canAdmin: true,
  usePermission: vi.fn((permission: string) =>
    permission === 'meta.designer.read'
      ? permissionMock.canRead
      : permission === 'meta.designer.update'
        ? permissionMock.canManage
        : permission === 'meta.designer.admin'
          ? permissionMock.canAdmin
          : false,
  ),
}));

vi.mock('~/contexts/AuthContext', () => ({
  usePermission: permissionMock.usePermission,
  useUser: () => ({ user: { id: '1' }, isAuthenticated: true }),
}));

vi.mock('../authoringService', () => ({
  openAuthoringSession: vi.fn(),
  loadAuthoringCapabilities: vi.fn(),
  loadAuthoringSession: vi.fn(),
  applyAuthoringPatch: vi.fn(),
  prepareAuthoringSession: vi.fn(),
  renewAuthoringWriterLease: vi.fn(),
  submitAuthoringSession: vi.fn(),
  createAuthoringHandoff: vi.fn(),
  takeoverAuthoringWriterLease: vi.fn(),
  transitionAuthoringGovernance: vi.fn(),
}));

vi.mock('../authoringConflictTransfer', () => ({
  storeAuthoringConflictTransfer: vi.fn(() => 'a'.repeat(32)),
}));

const schema: UnifiedSchema = {
  kind: 'list',
  version: '1.0.0',
  id: 'page-1',
  pageKey: 'orders_list',
  title: '订单列表',
  blocks: [
    {
      id: 'table-1',
      blockType: 'table',
      title: '订单表格',
      columns: [{ field: 'name', label: '名称' }],
      buttons: [{ code: 'create', label: '新建' }],
    },
  ],
  layout: { type: 'stack' },
};

describe('ContextualAuthoringSurface', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissionMock.canRead = true;
    permissionMock.canManage = true;
    permissionMock.canAdmin = true;
    window.history.replaceState(
      null,
      '',
      '/orders?tab=open&filter.status=OPEN&sort=createdAt%3Adesc',
    );
    vi.stubGlobal('scrollTo', vi.fn());
    Element.prototype.scrollIntoView = vi.fn();
    const openedSession = createAuthoringSession();
    vi.mocked(openAuthoringSession).mockResolvedValue(openedSession);
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue({
      checksum: 'registry-1',
      manifests: [
        {
          blockType: 'table',
          pluginCode: 'core.designer',
          pluginVersion: '0.1.0',
          manifestVersion: '1',
          checksum: 'table-1',
          properties: {
            '/title': {
              propertyPath: '/title',
              allowedOperations: ['REPLACE'],
              route: 'INLINE',
              risk: 'L1',
              effectTags: ['PRESENTATION'],
              reversibility: 'REVERSIBLE',
              protectedSemantic: false,
              rolePreviewRequired: false,
            },
            '/dataSource': {
              propertyPath: '/dataSource',
              allowedOperations: ['REPLACE'],
              route: 'HANDOFF_STUDIO',
              risk: 'L3',
              effectTags: ['DATA_BINDING'],
              reversibility: 'REVERSIBLE',
              protectedSemantic: false,
              rolePreviewRequired: true,
            },
          },
        },
      ],
    });
    vi.mocked(createAuthoringHandoff).mockResolvedValue({
      contextId: 'ctx_secure_once',
      targetRoute: '/unified-designer',
      expiresAt: '2026-08-09T12:00:00Z',
    });
    vi.mocked(applyAuthoringPatch).mockResolvedValue({
      session: {
        sessionPid: 'session-1',
        changeSetPid: 'changeset-1',
        pagePid: 'page-1',
        ownerUserId: 1,
        changeSetStatus: 'DRAFT',
        workspaceMode: 'AUTHORING',
        state: 'ACTIVE',
        revision: 2,
        riskLevel: 'L1',
        route: 'INLINE',
        publishPolicy: 'DIRECT_ALLOWED',
        validationState: 'VALID',
        impactState: 'KNOWN',
        approvalState: 'NOT_REQUIRED',
        publishState: 'DRAFT',
        manifestChecksum: 'registry-1',
        snapshot: {
          ...schema,
          pid: 'page-1',
          blocks: [{ ...schema.blocks[0], title: '生产订单' }],
        },
        interactionContext: {},
        expiresAt: '2026-08-09T12:00:00Z',
      },
      changeItemPid: 'item-1',
      decision: {
        route: 'INLINE',
        risk: 'L1',
        publishPolicy: 'DIRECT_ALLOWED',
        reason: 'CAPABILITY_ALLOWED',
        manifestChecksum: 'table-1',
        rolePreviewRequired: false,
      },
      previousValue: '订单表格',
      savedValue: '生产订单',
    });
    vi.mocked(submitAuthoringSession).mockResolvedValue(undefined);
    vi.mocked(prepareAuthoringSession).mockResolvedValue(
      createAuthoringSession({
        revision: 2,
        validationState: 'VALID',
        impactState: 'KNOWN',
        validation: {
          validationRunPid: 'validation-1',
          revision: 2,
          status: 'VALID',
          errorCount: 0,
          issues: [],
          validatedAt: '2026-08-09T12:00:00Z',
        },
        impact: {
          impactRunPid: 'impact-1',
          revision: 2,
          status: 'KNOWN',
          dependencyChecksum: 'dependency-1',
          dependencies: [],
          analyzedAt: '2026-08-09T12:00:00Z',
        },
      }),
    );
    vi.mocked(transitionAuthoringGovernance).mockResolvedValue(undefined);
    vi.mocked(loadAuthoringSession).mockResolvedValue(openedSession);
    vi.mocked(renewAuthoringWriterLease).mockReset();
    vi.mocked(renewAuthoringWriterLease).mockResolvedValue(openedSession);
    vi.mocked(takeoverAuthoringWriterLease).mockResolvedValue(
      createAuthoringSession({
        writerLease: {
          status: 'OWNED',
          revision: 2,
          leasedUntil: '2026-08-09T12:05:00Z',
        },
      }),
    );
    vi.mocked(storeAuthoringConflictTransfer).mockReturnValue('a'.repeat(32));
  });

  it('enters from the runtime page, separates modes and exposes independent counters', async () => {
    const unsafeAction = vi.fn();
    const safeTab = vi.fn();
    renderSurface(unsafeAction, safeTab);

    fireEvent.click(screen.getByTestId('contextual-authoring-enter'));
    expect(await screen.findByTestId('contextual-authoring-surface')).toHaveAttribute(
      'data-mode',
      'select',
    );
    expect(screen.getByText('0 项未保存')).toBeInTheDocument();
    expect(screen.getByText('0 项草稿变更')).toBeInTheDocument();
    expect(screen.getByText('0 个校验错误')).toBeInTheDocument();
    expect(openAuthoringSession).toHaveBeenCalledWith(
      'page-1',
      expect.objectContaining({
        route: '/orders?tab=open&filter.status=OPEN&sort=createdAt%3Adesc',
        recordPid: 'record-1',
        tabId: 'open',
        filters: { 'filter.status': ['OPEN'] },
        sort: { sort: ['createdAt:desc'] },
        selection: 'page-1',
        outlinePath: ['page-1'],
        viewport: expect.objectContaining({
          width: window.innerWidth,
          height: window.innerHeight,
        }),
      }),
    );

    fireEvent.click(screen.getByTestId('runtime-write'));
    expect(unsafeAction).not.toHaveBeenCalled();
    expect(screen.getByTestId('authoring-inspector')).toHaveTextContent('订单表格');

    fireEvent.click(screen.getByText('交互预览'));
    expect(screen.getByTestId('contextual-authoring-surface')).toHaveAttribute(
      'data-mode',
      'interact',
    );
    fireEvent.click(screen.getByTestId('runtime-write'));
    expect(unsafeAction).not.toHaveBeenCalled();
    expect(screen.getByTestId('authoring-write-blocked')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('runtime-tab'));
    expect(safeTab).toHaveBeenCalledTimes(1);
  });

  it('restores the same session, focus and scroll after returning from Studio', async () => {
    window.history.replaceState(
      null,
      '',
      '/orders?tab=open&authoringReturn=session-return&authoringFocus=table-1',
    );
    vi.mocked(loadAuthoringSession).mockResolvedValue(
      createAuthoringSession({
        sessionPid: 'session-return',
        revision: 7,
        snapshot: {
          ...schema,
          pid: 'page-1',
          blocks: [{ ...schema.blocks[0], title: 'Studio 草稿表格' }],
        },
        interactionContext: {
          route: '/orders?tab=open',
          scroll: { x: 16, y: 640 },
          selection: 'table-1',
          viewport: { width: 1440, height: 900, scale: 2 },
        },
      }),
    );

    renderSurface(vi.fn(), vi.fn());

    expect(await screen.findByTestId('contextual-authoring-surface')).toHaveTextContent(
      'Studio 草稿表格',
    );
    expect(loadAuthoringSession).toHaveBeenCalledWith('session-return');
    expect(openAuthoringSession).not.toHaveBeenCalled();
    expect(screen.getByTestId('authoring-inspector')).toHaveTextContent('Studio 草稿表格');
    await waitFor(() => expect(window.scrollTo).toHaveBeenCalledWith(16, 640));
    expect(window.location.search).toBe('?tab=open');
  });

  it('explains a studio boundary before creating an opaque handoff', async () => {
    renderSurface(vi.fn(), vi.fn());
    fireEvent.click(screen.getByTestId('contextual-authoring-enter'));
    await screen.findByTestId('contextual-authoring-surface');
    fireEvent.click(screen.getByTestId('runtime-write'));

    fireEvent.click(await screen.findByText('高级设置 ↗'));
    expect(screen.getByRole('dialog')).toHaveTextContent('DATA_BINDING');
    expect(screen.getByRole('dialog')).toHaveTextContent('10 分钟');
    fireEvent.click(screen.getByText('继续到应用设计中心'));

    await waitFor(() =>
      expect(createAuthoringHandoff).toHaveBeenCalledWith(
        'session-1',
        1,
        'PAGE_STRUCTURE',
        'table-1',
        '/dataSource',
      ),
    );
  });

  it('stages an inline property, shows a diff and saves it into the ChangeSet', async () => {
    renderSurface(vi.fn(), vi.fn());
    fireEvent.click(screen.getByTestId('contextual-authoring-enter'));
    await screen.findByTestId('contextual-authoring-surface');
    fireEvent.click(screen.getByTestId('runtime-write'));

    fireEvent.change(screen.getByLabelText(/标题/), { target: { value: '生产订单' } });
    expect(screen.getByText('1 项未保存')).toBeInTheDocument();
    fireEvent.click(screen.getByText('差异'));
    expect(screen.getByRole('dialog')).toHaveTextContent('订单表格');
    expect(screen.getByRole('dialog')).toHaveTextContent('生产订单');
    fireEvent.click(screen.getByLabelText('关闭差异'));

    fireEvent.click(screen.getByText('保存'));
    await waitFor(() =>
      expect(applyAuthoringPatch).toHaveBeenCalledWith(
        'session-1',
        1,
        'table-1',
        '/title',
        'REPLACE',
        '生产订单',
        'table-1',
      ),
    );
    expect(await screen.findByText('0 项未保存')).toBeInTheDocument();
    expect(screen.getByText('1 项草稿变更')).toBeInTheDocument();
  });

  it('deduplicates an inline save when the server committed but the success response was lost', async () => {
    vi.mocked(applyAuthoringPatch).mockRejectedValueOnce(new Error('Failed to fetch'));
    vi.mocked(loadAuthoringSession).mockResolvedValueOnce(
      createAuthoringSession({
        revision: 2,
        snapshot: {
          ...schema,
          pid: 'page-1',
          blocks: [{ ...schema.blocks[0], title: '生产订单' }],
        },
      }),
    );
    renderSurface(vi.fn(), vi.fn());
    fireEvent.click(screen.getByTestId('contextual-authoring-enter'));
    await screen.findByTestId('contextual-authoring-surface');
    fireEvent.click(screen.getByTestId('runtime-write'));
    fireEvent.change(screen.getByLabelText(/标题/), { target: { value: '生产订单' } });

    fireEvent.click(screen.getByText('保存'));

    expect(await screen.findByTestId('authoring-save-reconciliation-feedback')).toHaveAttribute(
      'data-tone',
      'success',
    );
    expect(screen.getByTestId('authoring-save-reconciliation-feedback')).toHaveTextContent(
      '保存已在服务端完成',
    );
    expect(screen.getByText('0 项未保存')).toBeInTheDocument();
    expect(screen.getByText('1 项草稿变更')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(applyAuthoringPatch).toHaveBeenCalledTimes(1);
  });

  it('retries an unchanged inline edit from an authoritative revision advanced by another change', async () => {
    vi.mocked(applyAuthoringPatch)
      .mockRejectedValueOnce(new Error('Failed to fetch'))
      .mockResolvedValueOnce({
        session: createAuthoringSession({
          revision: 3,
          snapshot: {
            ...schema,
            pid: 'page-1',
            blocks: [{ ...schema.blocks[0], title: '生产订单' }],
          },
        }),
        changeItemPid: 'item-retry',
        decision: {
          route: 'INLINE',
          risk: 'L1',
          publishPolicy: 'DIRECT_ALLOWED',
          reason: 'CAPABILITY_ALLOWED',
          manifestChecksum: 'table-1',
          rolePreviewRequired: false,
        },
        previousValue: '订单表格',
        savedValue: '生产订单',
      });
    vi.mocked(loadAuthoringSession).mockResolvedValueOnce(
      createAuthoringSession({
        revision: 2,
        snapshot: { ...schema, pid: 'page-1' },
      }),
    );
    renderSurface(vi.fn(), vi.fn());
    fireEvent.click(screen.getByTestId('contextual-authoring-enter'));
    await screen.findByTestId('contextual-authoring-surface');
    fireEvent.click(screen.getByTestId('runtime-write'));
    fireEvent.change(screen.getByLabelText(/标题/), { target: { value: '生产订单' } });

    fireEvent.click(screen.getByText('保存'));

    await waitFor(() => expect(applyAuthoringPatch).toHaveBeenCalledTimes(2));
    expect(applyAuthoringPatch).toHaveBeenNthCalledWith(
      2,
      'session-1',
      2,
      'table-1',
      '/title',
      'REPLACE',
      '生产订单',
      'table-1',
    );
    expect(screen.getByText('0 项未保存')).toBeInTheDocument();
    expect(screen.queryByTestId('contextual-authoring-conflict')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('stops stale inline writes and transfers only an opaque conflict context to Studio', async () => {
    vi.mocked(applyAuthoringPatch).mockRejectedValueOnce(new Error('authoring.revision.conflict'));
    vi.mocked(loadAuthoringSession).mockResolvedValueOnce(
      createAuthoringSession({
        revision: 2,
        snapshot: {
          ...schema,
          pid: 'page-1',
          blocks: [{ ...schema.blocks[0], title: 'Latest 订单标题' }],
        },
      }),
    );
    renderSurface(vi.fn(), vi.fn());
    fireEvent.click(screen.getByTestId('contextual-authoring-enter'));
    await screen.findByTestId('contextual-authoring-surface');
    fireEvent.click(screen.getByTestId('runtime-write'));
    fireEvent.change(screen.getByLabelText(/标题/), { target: { value: 'Mine 订单标题' } });
    fireEvent.click(screen.getByText('保存'));

    expect(await screen.findByTestId('contextual-authoring-conflict')).toHaveTextContent(
      'Base r1 / Latest r2',
    );
    expect(screen.getByTestId('contextual-authoring-surface')).toHaveAttribute(
      'data-read-only',
      'true',
    );
    expect(screen.getByText('保存')).toBeDisabled();
    expect(applyAuthoringPatch).toHaveBeenCalledTimes(1);
    expect(storeAuthoringConflictTransfer).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('contextual-authoring-conflict-studio'));

    expect(storeAuthoringConflictTransfer).toHaveBeenCalledWith(
      expect.objectContaining({
        sessionPid: 'session-1',
        changeSetPid: 'changeset-1',
        pagePid: 'page-1',
        baseRevision: 1,
        baseSnapshot: expect.objectContaining({
          blocks: [expect.objectContaining({ title: '订单表格' })],
        }),
        mineSnapshot: expect.objectContaining({
          blocks: [expect.objectContaining({ title: 'Mine 订单标题' })],
        }),
      }),
    );
  });

  it('freezes the original page as soon as polling detects a conflicting Latest value', async () => {
    let poll: (() => void) | undefined;
    const interval = vi.spyOn(window, 'setInterval').mockImplementation((handler) => {
      if (typeof handler === 'function') poll = handler;
      return {} as ReturnType<typeof window.setInterval>;
    });
    renderSurface(vi.fn(), vi.fn());
    fireEvent.click(screen.getByTestId('contextual-authoring-enter'));
    await screen.findByTestId('contextual-authoring-surface');
    fireEvent.click(screen.getByTestId('runtime-write'));
    fireEvent.change(screen.getByLabelText(/标题/), { target: { value: 'Mine 订单标题' } });
    vi.mocked(loadAuthoringSession).mockResolvedValueOnce(
      createAuthoringSession({
        revision: 2,
        snapshot: {
          ...schema,
          pid: 'page-1',
          blocks: [{ ...schema.blocks[0], title: 'Latest 订单标题' }],
        },
      }),
    );

    await act(async () => {
      poll?.();
      await Promise.resolve();
    });

    expect(await screen.findByTestId('contextual-authoring-conflict')).toHaveTextContent(
      'Base r1 / Latest r2',
    );
    expect(screen.getByText('保存')).toBeDisabled();
    expect(applyAuthoringPatch).not.toHaveBeenCalled();
    interval.mockRestore();
  });

  it('withdraws a frozen review into a new editable revision without hidden mutation', async () => {
    vi.mocked(openAuthoringSession).mockResolvedValue(
      createAuthoringSession({
        revision: 7,
        changeSetStatus: 'IN_REVIEW',
        state: 'READ_ONLY',
        validationState: 'VALID',
        impactState: 'KNOWN',
        approvalState: 'PENDING',
        publishState: 'DRAFT',
      }),
    );
    vi.mocked(loadAuthoringSession).mockResolvedValueOnce(
      createAuthoringSession({
        revision: 8,
        changeSetStatus: 'DRAFT',
        state: 'ACTIVE',
        validationState: 'UNVALIDATED',
        impactState: 'UNKNOWN',
        approvalState: 'STALE',
        publishState: 'DRAFT',
      }),
    );
    renderSurface(vi.fn(), vi.fn());
    fireEvent.click(screen.getByTestId('contextual-authoring-enter'));

    expect(await screen.findByTestId('authoring-governance-notice')).toHaveTextContent(
      'revision r7 已冻结',
    );
    expect(screen.getByText('保存')).toBeDisabled();
    fireEvent.change(screen.getByTestId('authoring-governance-reason'), {
      target: { value: '补充评审要求的异常场景' },
    });
    fireEvent.click(screen.getByTestId('authoring-governance-withdraw'));

    await waitFor(() =>
      expect(transitionAuthoringGovernance).toHaveBeenCalledWith(
        'withdraw',
        expect.objectContaining({ sessionPid: 'session-1', revision: 7 }),
        '补充评审要求的异常场景',
      ),
    );
    expect(screen.queryByTestId('authoring-governance-notice')).not.toBeInTheDocument();
    expect(screen.getByTestId('contextual-authoring-surface')).toHaveAttribute(
      'data-read-only',
      'false',
    );
    expect(screen.getByText('保存')).toBeDisabled();
  });

  it('turns an active session read-only when permission is revoked and preserves local edits', async () => {
    const view = renderSurface(vi.fn(), vi.fn());
    fireEvent.click(screen.getByTestId('contextual-authoring-enter'));
    await screen.findByTestId('contextual-authoring-surface');
    fireEvent.click(screen.getByTestId('runtime-write'));
    fireEvent.change(screen.getByLabelText(/标题/), { target: { value: '待恢复的订单标题' } });

    expect(screen.getByText('1 项未保存')).toBeInTheDocument();
    permissionMock.canManage = false;
    view.rerender(surfaceElement(vi.fn(), vi.fn()));

    expect(screen.getByTestId('contextual-authoring-surface')).toHaveAttribute(
      'data-read-only',
      'true',
    );
    expect(screen.getByTestId('authoring-permission-revoked')).toHaveTextContent(
      '未保存差异仍会保留',
    );
    expect(screen.getByLabelText(/标题/)).toBeDisabled();
    expect(screen.getByText('1 项未保存')).toBeInTheDocument();
    expect(screen.getByText('保存')).toBeDisabled();
    expect(screen.getByText('校验与影响分析')).toBeDisabled();
    expect(screen.getByText('高级设置')).toBeDisabled();
    fireEvent.click(screen.getByText('保存'));
    expect(applyAuthoringPatch).not.toHaveBeenCalled();

    permissionMock.canManage = true;
    view.rerender(surfaceElement(vi.fn(), vi.fn()));
    expect(screen.queryByTestId('authoring-permission-revoked')).not.toBeInTheDocument();
    expect(screen.getByTestId('contextual-authoring-surface')).toHaveAttribute(
      'data-read-only',
      'false',
    );
    expect(screen.getByLabelText(/标题/)).toBeEnabled();
    expect(screen.getByText('1 项未保存')).toBeInTheDocument();
    expect(screen.getByText('保存')).toBeEnabled();
  });

  it('prepares validation and impact before a separate submit-for-review action', async () => {
    vi.mocked(openAuthoringSession).mockResolvedValue(
      createAuthoringSession({
        revision: 2,
        validationState: 'UNVALIDATED',
        impactState: 'UNKNOWN',
      }),
    );
    vi.mocked(loadAuthoringSession).mockResolvedValue(
      createAuthoringSession({
        revision: 2,
        validationState: 'VALID',
        impactState: 'KNOWN',
      }),
    );
    renderSurface(vi.fn(), vi.fn());
    fireEvent.click(screen.getByTestId('contextual-authoring-enter'));

    const prepare = await screen.findByText('校验与影响分析');
    fireEvent.click(prepare);

    await waitFor(() => expect(prepareAuthoringSession).toHaveBeenCalledWith('session-1', 2));
    expect(submitAuthoringSession).not.toHaveBeenCalled();
    const submit = await screen.findByText('提交评审');
    fireEvent.click(submit);

    await waitFor(() => expect(submitAuthoringSession).toHaveBeenCalledWith('session-1', 2));
    expect(prepareAuthoringSession).toHaveBeenCalledTimes(1);
  });

  it('keeps stale dependency results fail-closed until a new revision exists', async () => {
    vi.mocked(openAuthoringSession).mockResolvedValue(
      createAuthoringSession({
        revision: 2,
        validationState: 'STALE',
        impactState: 'STALE',
      }),
    );
    renderSurface(vi.fn(), vi.fn());
    fireEvent.click(screen.getByTestId('contextual-authoring-enter'));

    expect(await screen.findByTestId('authoring-impact-notice')).toHaveTextContent('依赖已变化');
    expect(screen.getByText('校验与影响分析')).toBeDisabled();
    expect(prepareAuthoringSession).not.toHaveBeenCalled();
    expect(submitAuthoringSession).not.toHaveBeenCalled();
  });

  it('retries a timed-out impact analysis without submitting the revision', async () => {
    vi.mocked(openAuthoringSession).mockResolvedValue(
      createAuthoringSession({
        revision: 2,
        validationState: 'VALID',
        impactState: 'FAILED',
        impact: {
          impactRunPid: 'impact-timeout',
          revision: 2,
          status: 'FAILED',
          dependencies: [],
          failureCode: 'ANALYSIS_TIMEOUT',
          analyzedAt: '2026-08-09T12:00:00Z',
        },
      }),
    );
    renderSurface(vi.fn(), vi.fn());
    fireEvent.click(screen.getByTestId('contextual-authoring-enter'));

    fireEvent.click(await screen.findByText('重试影响分析'));

    await waitFor(() => expect(prepareAuthoringSession).toHaveBeenCalledWith('session-1', 2));
    expect(submitAuthoringSession).not.toHaveBeenCalled();
  });

  it('shows a held writer lease as read-only and allows an audited admin takeover', async () => {
    vi.mocked(openAuthoringSession).mockResolvedValue(
      createAuthoringSession({
        writerLease: {
          status: 'HELD_BY_OTHER',
          revision: 7,
          leasedUntil: '2026-08-09T12:05:00Z',
        },
      }),
    );
    renderSurface(vi.fn(), vi.fn());
    fireEvent.click(screen.getByTestId('contextual-authoring-enter'));

    expect(await screen.findByTestId('authoring-writer-lease-notice')).toHaveTextContent(
      '另一位管理员正在编辑',
    );
    expect(screen.getByTestId('contextual-authoring-surface')).toHaveAttribute(
      'data-read-only',
      'true',
    );
    fireEvent.change(screen.getByLabelText('接管原因'), {
      target: { value: '原作者离线，继续紧急修复' },
    });
    fireEvent.click(screen.getByTestId('authoring-writer-lease-takeover'));

    await waitFor(() =>
      expect(takeoverAuthoringWriterLease).toHaveBeenCalledWith(
        'session-1',
        1,
        7,
        '原作者离线，继续紧急修复',
      ),
    );
    expect(screen.queryByTestId('authoring-writer-lease-notice')).not.toBeInTheDocument();
    expect(screen.getByTestId('contextual-authoring-surface')).toHaveAttribute(
      'data-read-only',
      'false',
    );
  });

  it('reloads the authoritative lease when another node wins the observed revision', async () => {
    const observed = createAuthoringSession({
      writerLease: {
        status: 'HELD_BY_OTHER',
        revision: 7,
        leasedUntil: '2026-08-09T12:05:00Z',
      },
    });
    const winner = createAuthoringSession({
      writerLease: {
        status: 'HELD_BY_OTHER_SESSION',
        revision: 8,
        leasedUntil: '2026-08-09T12:10:00Z',
      },
    });
    vi.mocked(openAuthoringSession).mockResolvedValue(observed);
    vi.mocked(takeoverAuthoringWriterLease).mockRejectedValue(new Error('Business error'));
    vi.mocked(loadAuthoringSession).mockResolvedValue(winner);
    renderSurface(vi.fn(), vi.fn());
    fireEvent.click(screen.getByTestId('contextual-authoring-enter'));

    await screen.findByTestId('authoring-writer-lease-notice');
    fireEvent.change(screen.getByLabelText('接管原因'), {
      target: { value: '基于 lease r7 尝试接管' },
    });
    fireEvent.click(screen.getByTestId('authoring-writer-lease-takeover'));

    await waitFor(() => expect(loadAuthoringSession).toHaveBeenCalledWith('session-1'));
    expect(screen.getByTestId('authoring-writer-lease-notice')).toHaveTextContent(
      '当前账号的另一个会话持有编辑权',
    );
    expect(screen.getByTestId('authoring-writer-lease-notice')).toHaveTextContent('租约版本 r8');
    expect(screen.getByTestId('contextual-authoring-surface')).toHaveAttribute(
      'data-read-only',
      'true',
    );
    expect(screen.getByTestId('writer-lease-takeover-feedback')).toHaveTextContent(
      '编辑权刚被另一会话取得，已刷新为只读',
    );
    expect(screen.queryByText('Business error')).not.toBeInTheDocument();
  });

  it('restores contextual editing when a lost response already committed the takeover', async () => {
    const observed = createAuthoringSession({
      writerLease: {
        status: 'HELD_BY_OTHER_SESSION',
        revision: 7,
        leasedUntil: '2026-08-09T12:05:00Z',
      },
    });
    const committed = createAuthoringSession({
      writerLease: {
        status: 'OWNED',
        revision: 8,
        leasedUntil: '2026-08-09T12:10:00Z',
      },
    });
    vi.mocked(openAuthoringSession).mockResolvedValue(observed);
    vi.mocked(takeoverAuthoringWriterLease).mockRejectedValue(
      new Error('Network error: Failed to fetch'),
    );
    vi.mocked(loadAuthoringSession).mockResolvedValue(committed);
    renderSurface(vi.fn(), vi.fn());
    fireEvent.click(screen.getByTestId('contextual-authoring-enter'));

    await screen.findByTestId('authoring-writer-lease-notice');
    fireEvent.change(screen.getByLabelText('接管原因'), {
      target: { value: '响应丢失后对账接管' },
    });
    fireEvent.click(screen.getByTestId('authoring-writer-lease-takeover'));

    await waitFor(() => expect(loadAuthoringSession).toHaveBeenCalledWith('session-1'));
    expect(screen.queryByTestId('authoring-writer-lease-notice')).not.toBeInTheDocument();
    expect(screen.getByTestId('contextual-authoring-surface')).toHaveAttribute(
      'data-read-only',
      'false',
    );
    expect(screen.getByTestId('writer-lease-takeover-feedback')).toHaveAttribute(
      'data-tone',
      'success',
    );
    expect(screen.getByTestId('writer-lease-takeover-feedback')).toHaveTextContent(
      '接管已在服务端完成，当前页面已恢复编辑',
    );
  });

  it('preserves local edits and requires an explicit audited takeover after lease expiry', async () => {
    let poll: (() => void) | undefined;
    const interval = vi.spyOn(window, 'setInterval').mockImplementation((handler) => {
      if (typeof handler === 'function') poll = handler;
      return {} as ReturnType<typeof window.setInterval>;
    });
    renderSurface(vi.fn(), vi.fn());
    fireEvent.click(screen.getByTestId('contextual-authoring-enter'));
    await screen.findByTestId('contextual-authoring-surface');
    fireEvent.click(screen.getByTestId('runtime-write'));
    fireEvent.change(screen.getByLabelText(/标题/), { target: { value: '过期前的本地标题' } });
    expect(screen.getByText('1 项未保存')).toBeVisible();
    vi.mocked(loadAuthoringSession).mockResolvedValueOnce(
      createAuthoringSession({
        writerLease: {
          status: 'EXPIRED',
          revision: 7,
          leasedUntil: '2026-08-09T12:05:00Z',
        },
      }),
    );

    await act(async () => {
      poll?.();
      await Promise.resolve();
    });

    expect(await screen.findByTestId('authoring-writer-lease-notice')).toHaveTextContent(
      'Writer lease 已过期',
    );
    expect(screen.getByTestId('contextual-authoring-surface')).toHaveAttribute(
      'data-read-only',
      'true',
    );
    expect(screen.getByLabelText(/标题/)).toHaveValue('过期前的本地标题');
    expect(screen.getByText('1 项未保存')).toBeVisible();
    expect(screen.getByText('保存')).toBeDisabled();
    expect(screen.getByTestId('authoring-writer-lease-takeover')).toHaveTextContent(
      '重新取得编辑权',
    );
    expect(takeoverAuthoringWriterLease).not.toHaveBeenCalled();
    interval.mockRestore();
  });

  it('renews an owned writer lease on resume when it is close to expiry', async () => {
    const expiring = createAuthoringSession({
      writerLease: {
        status: 'OWNED',
        revision: 7,
        leasedUntil: new Date(Date.now() + 30_000).toISOString(),
      },
    });
    const renewed = createAuthoringSession({
      writerLease: {
        status: 'OWNED',
        revision: 8,
        leasedUntil: new Date(Date.now() + 5 * 60_000).toISOString(),
      },
    });
    vi.mocked(openAuthoringSession).mockResolvedValue(expiring);
    vi.mocked(loadAuthoringSession).mockResolvedValue(expiring);
    vi.mocked(renewAuthoringWriterLease).mockResolvedValue(renewed);
    renderSurface(vi.fn(), vi.fn());
    fireEvent.click(screen.getByTestId('contextual-authoring-enter'));
    await screen.findByTestId('contextual-authoring-surface');

    act(() => window.dispatchEvent(new Event('focus')));

    await waitFor(() => expect(renewAuthoringWriterLease).toHaveBeenCalledWith('session-1'));
    expect(screen.queryByTestId('authoring-writer-lease-notice')).not.toBeInTheDocument();
    expect(screen.getByTestId('contextual-authoring-surface')).toHaveAttribute(
      'data-read-only',
      'false',
    );
  });

  it('does not renew an expiring writer lease from a hidden heartbeat', async () => {
    const intervalHandlers = new Map<number, TimerHandler>();
    let intervalId = 0;
    const interval = vi.spyOn(window, 'setInterval').mockImplementation((handler, timeout) => {
      intervalId += 1;
      if (timeout === 60_000) intervalHandlers.set(intervalId, handler);
      return intervalId as unknown as ReturnType<typeof window.setInterval>;
    });
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    vi.mocked(openAuthoringSession).mockResolvedValue(
      createAuthoringSession({
        writerLease: {
          status: 'OWNED',
          revision: 7,
          leasedUntil: new Date(Date.now() + 30_000).toISOString(),
        },
      }),
    );
    try {
      renderSurface(vi.fn(), vi.fn());
      fireEvent.click(screen.getByTestId('contextual-authoring-enter'));
      await screen.findByTestId('contextual-authoring-surface');
      expect(intervalHandlers.size).toBe(1);

      await act(async () => {
        for (const handler of intervalHandlers.values()) {
          if (typeof handler === 'function') handler();
        }
        await Promise.resolve();
      });

      expect(renewAuthoringWriterLease).not.toHaveBeenCalled();
      visibility.mockReturnValue('visible');
      act(() => document.dispatchEvent(new Event('visibilitychange')));
      await waitFor(() => expect(renewAuthoringWriterLease).toHaveBeenCalledWith('session-1'));
    } finally {
      visibility.mockRestore();
      interval.mockRestore();
    }
  });

  it('keeps recursive authoring roots for editing but canonicalizes them for runtime preview', async () => {
    vi.mocked(openAuthoringSession).mockResolvedValue(
      createAuthoringSession({
        snapshot: {
          ...schema,
          pid: 'page-1',
          schemaVersion: 4,
          modelCode: 'orders',
          blocks: [
            {
              id: 'list-orders-list',
              blockType: 'list',
              blocks: [
                {
                  id: 'table-orders',
                  blockType: 'table',
                  blocks: [
                    {
                      id: 'column-name',
                      blockType: 'column',
                      field: 'name',
                      props: { label: '名称' },
                    },
                  ],
                },
              ],
            },
          ],
        },
      }),
    );
    const renderRuntime = vi.fn((runtimeSchema: UnifiedSchema) => (
      <div data-testid="authoring-runtime-preview">
        {runtimeSchema.blocks.map((block) => block.blockType).join(',')}
      </div>
    ));

    render(
      <MemoryRouter initialEntries={['/orders']}>
        <ContextualAuthoringSurface schema={schema} renderRuntime={renderRuntime}>
          <div>runtime</div>
        </ContextualAuthoringSurface>
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByTestId('contextual-authoring-enter'));

    expect(await screen.findByTestId('authoring-runtime-preview')).toHaveTextContent('table');
    const previewSchema = renderRuntime.mock.calls.at(-1)?.[0];
    expect(previewSchema?.blocks).toEqual([
      expect.objectContaining({
        id: 'table-orders',
        blockType: 'table',
        columns: [
          expect.objectContaining({
            id: 'column-name',
            field: 'name',
            label: '名称',
          }),
        ],
      }),
    ]);
    expect(screen.getByTestId('authoring-outline-list-orders-list')).toBeInTheDocument();
  });
});

function renderSurface(unsafeAction: () => void, safeTab: () => void) {
  return render(surfaceElement(unsafeAction, safeTab));
}

function surfaceElement(unsafeAction: () => void, safeTab: () => void) {
  return (
    <MemoryRouter initialEntries={['/orders?tab=open']}>
      <ContextualAuthoringSurface schema={schema} recordPid="record-1">
        <div data-aura-block-id="table-1">
          <button type="button" data-testid="runtime-write" onClick={unsafeAction}>
            批准订单
          </button>
          <button type="button" role="tab" data-testid="runtime-tab" onClick={safeTab}>
            详情
          </button>
        </div>
      </ContextualAuthoringSurface>
    </MemoryRouter>
  );
}

function createAuthoringSession(overrides: Partial<AuthoringSession> = {}): AuthoringSession {
  return {
    sessionPid: 'session-1',
    changeSetPid: 'changeset-1',
    pagePid: 'page-1',
    ownerUserId: 1,
    changeSetStatus: 'DRAFT',
    workspaceMode: 'AUTHORING',
    state: 'ACTIVE',
    revision: 1,
    riskLevel: 'L0',
    route: 'INLINE',
    publishPolicy: 'DIRECT_ALLOWED',
    validationState: 'UNVALIDATED',
    impactState: 'UNKNOWN',
    approvalState: 'NOT_REQUIRED',
    publishState: 'DRAFT',
    manifestChecksum: 'registry-1',
    snapshot: { ...schema, pid: 'page-1' },
    interactionContext: {},
    expiresAt: '2026-08-09T12:00:00Z',
    ...overrides,
  };
}
