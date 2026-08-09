import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ContextualAuthoringSurface } from '../ContextualAuthoringSurface';
import {
  applyAuthoringPatch,
  createAuthoringHandoff,
  loadAuthoringCapabilities,
  loadAuthoringSession,
  openAuthoringSession,
  submitAuthoringSession,
} from '../authoringService';
import type { UnifiedSchema } from '~/framework/meta/schemas/types';
import type { AuthoringSession } from '../types';

const permissionMock = vi.hoisted(() => ({
  canRead: true,
  canManage: true,
  usePermission: vi.fn((permission: string) =>
    permission === 'meta.designer.read'
      ? permissionMock.canRead
      : permission === 'meta.designer.update'
        ? permissionMock.canManage
        : false,
  ),
}));

vi.mock('~/contexts/AuthContext', () => ({
  usePermission: permissionMock.usePermission,
}));

vi.mock('../authoringService', () => ({
  openAuthoringSession: vi.fn(),
  loadAuthoringCapabilities: vi.fn(),
  loadAuthoringSession: vi.fn(),
  applyAuthoringPatch: vi.fn(),
  submitAuthoringSession: vi.fn(),
  createAuthoringHandoff: vi.fn(),
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
        state: 'ACTIVE',
        revision: 2,
        riskLevel: 'L1',
        route: 'INLINE',
        publishPolicy: 'DIRECT_ALLOWED',
        validationState: 'VALID',
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
    vi.mocked(loadAuthoringSession).mockResolvedValue(openedSession);
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
    expect(screen.getByText('提交评审')).toBeDisabled();
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
    state: 'ACTIVE',
    revision: 1,
    riskLevel: 'L0',
    route: 'INLINE',
    publishPolicy: 'DIRECT_ALLOWED',
    validationState: 'UNVALIDATED',
    approvalState: 'NOT_REQUIRED',
    publishState: 'DRAFT',
    manifestChecksum: 'registry-1',
    snapshot: { ...schema, pid: 'page-1' },
    interactionContext: {},
    expiresAt: '2026-08-09T12:00:00Z',
    ...overrides,
  };
}
