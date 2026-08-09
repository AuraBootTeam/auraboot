import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useSearchParams } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import UnifiedDesignerPage from '../../../pages/unified-designer';
import { samplePageSchemaV3 } from '../fixtures/samplePageSchemaV3';
import { loadModelFieldsByModelCodes } from '../persistence/modelFieldsRepository';
import { loadPageSchemaV3, savePageSchemaV3 } from '../persistence/pageSchemaV3Repository';
import type { PageSchemaV3 } from '../types';
import { consumeAuthoringHandoff } from '~/framework/meta/authoring/authoringService';

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
  consumeAuthoringHandoff: vi.fn(),
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

  it('consumes an opaque contextual handoff and keeps the legacy studio path read-only', async () => {
    setSearch('?contextId=ctx_secure_once');
    vi.mocked(consumeAuthoringHandoff).mockResolvedValue({
      pagePid: 'page_1',
      changeSetPid: 'changeset_1',
      sessionPid: 'session_1',
      revision: 3,
      intent: 'PAGE_STRUCTURE',
      targetRoute: '/unified-designer',
      returnTo: '/orders?tab=open',
      blockId: 'field_customer_name',
      propertyPath: '/props/label',
      interactionContext: { route: '/orders?tab=open' },
      expiresAt: '2026-08-09T12:00:00Z',
    });
    vi.mocked(loadPageSchemaV3).mockResolvedValue({
      document: createDocument('document_one', 'Document One'),
      source: { type: 'page', pid: 'page_1', pageKey: 'document_one' },
      published: true,
    });

    render(<UnifiedDesignerPage />);

    expect(await screen.findByTestId('studio-handoff-context')).toHaveTextContent(
      'ChangeSet changeset_1',
    );
    expect(consumeAuthoringHandoff).toHaveBeenCalledWith('ctx_secure_once');
    expect(loadPageSchemaV3).toHaveBeenCalledWith({ pageId: 'page_1', pageKey: null });
    expect(screen.getByTestId('designer-return-link')).toHaveAttribute(
      'href',
      '/orders?tab=open',
    );
    await waitFor(() =>
      expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('field_customer_name'),
    );
    expect(screen.getByTestId('designer-save')).toBeDisabled();
    expect(screen.getByTestId('designer-publish')).toBeDisabled();

    fireEvent.change(screen.getByTestId('inspector-field-props.label'), {
      target: { value: 'Must not persist' },
    });
    expect(screen.getByTestId('designer-save')).toBeDisabled();
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
