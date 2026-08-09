import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useSearchParams } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import UnifiedDesignerPage from '../../../pages/unified-designer';
import { samplePageSchemaV3 } from '../fixtures/samplePageSchemaV3';
import { loadModelFieldsByModelCodes } from '../persistence/modelFieldsRepository';
import { loadPageSchemaV3, savePageSchemaV3 } from '../persistence/pageSchemaV3Repository';
import type { PageSchemaV3 } from '../types';
import {
  applyAuthoringStudioPatch,
  consumeAuthoringHandoff,
  loadAuthoringCapabilities,
  loadAuthoringSession,
} from '~/framework/meta/authoring/authoringService';
import type {
  AuthoringSession,
  CapabilityRegistry,
  HandoffContext,
} from '~/framework/meta/authoring/types';

const permissionMock = vi.hoisted(() => ({ canAdministerDesigner: vi.fn(() => true) }));

vi.mock('~/contexts/AuthContext', () => ({
  usePermission: permissionMock.canAdministerDesigner,
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
  applyAuthoringStudioPatch: vi.fn(),
  consumeAuthoringHandoff: vi.fn(),
  loadAuthoringCapabilities: vi.fn(),
  loadAuthoringSession: vi.fn(),
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
    vi.mocked(applyAuthoringStudioPatch).mockReset();
    permissionMock.canAdministerDesigner.mockReturnValue(true);
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
    permissionMock.canAdministerDesigner.mockReturnValue(false);
    const handoff = createHandoff('field_customer_name', '/props/label');
    const session = createAuthoringSession(createDocument('document_one', 'Isolated Draft'));
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
    expect(screen.getByTestId('studio-handoff-read-only-reason')).toHaveTextContent(
      '缺少高级设计权限',
    );
    expect(screen.getByTestId('designer-return-link')).toHaveAttribute(
      'href',
      '/orders?tab=open',
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
    structuralActions.forEach((action) => expect(action).toBeDisabled());

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
    state: 'ACTIVE',
    revision,
    riskLevel,
    route,
    publishPolicy: route === 'HANDOFF_STUDIO' ? 'STUDIO_APPROVAL' : 'DIRECT_ALLOWED',
    validationState: 'UNVALIDATED',
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
        },
      },
    ],
  };
}

function propertyCapability(propertyPath: string, route: string) {
  return {
    propertyPath,
    allowedOperations: ['ADD', 'REPLACE', 'REMOVE'],
    route,
    risk: route === 'HANDOFF_STUDIO' ? 'L3' : 'L1',
    effectTags: route === 'HANDOFF_STUDIO' ? ['DATA_BINDING'] : ['PRESENTATION'],
    reversibility: 'REVERSIBLE',
    protectedSemantic: false,
    rolePreviewRequired: route === 'HANDOFF_STUDIO',
  };
}
