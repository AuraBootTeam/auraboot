import React, { useEffect, useState } from 'react';
import { renderHook, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { usePageDataSources } from '../usePageDataSources';
import { SchemaRuntime } from '../../runtime/schema-runtime';
import { createExpressionContext } from '../../runtime/expression/context';
import { readDataSourceRows } from '../../rendering/blocks/workbenchBlockUtils';
import { usePageRuntime } from '../../rendering/pages/hooks/usePageRuntime';
import { MemoryRouter } from 'react-router';
import { DetailPageContent } from '../../rendering/pages/DetailPageContent';
import { ProfileProvider } from '@auraboot/runtime-kernel';
import { adminProfile } from '../../profiles/admin';

const { fetchResult } = vi.hoisted(() => ({ fetchResult: vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({ fetchResult }));
vi.mock('~/contexts/AuthContext', async () => ({
  ...(await vi.importActual<any>('~/contexts/AuthContext')),
  useAuth: () => ({ hasPermission: () => true }),
  useUser: () => ({}),
  usePermission: () => true,
}));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'en-US', t: (key: string) => key }),
}));
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => ({}) }));
vi.mock('~/framework/extensions/use-contribution', () => ({
  useContributionRegistry: () => ({ listPageRuntimeHooks: () => [] }),
}));

describe('page-owned named query with a schema runtime', () => {
  it('renders completed query rows after StrictMode reconnects the runtime scope', async () => {
    const records = Array.from({ length: 8 }, (_, index) => ({ pid: `project-${index}` }));
    fetchResult.mockResolvedValue({
      code: '0',
      data: { records, total: 8, page: 1, pageSize: 100 },
    });
    const schema = {
      id: 'delivery-start',
      pageKey: 'delivery-start',
      kind: 'detail',
      blocks: [],
      dataSources: {
        projects: {
          type: 'namedQuery',
          queryCode: 'delivery_brief',
          format: 'records',
          adaptor: 'table',
        },
      },
    } as any;
    const { result, unmount } = renderHook(
      () => {
        const { manager } = usePageDataSources({ schema, context: createExpressionContext({}) });
        const [runtime, setRuntime] = useState<SchemaRuntime | null>(null);
        useEffect(() => {
          const instance = new SchemaRuntime({
            schema,
            globalState: {} as any,
            dataSourceManager: manager,
            skipDataSourceRegistration: true,
          });
          setRuntime(instance);
          return () => instance.destroy();
        }, [manager]);
        return runtime ? readDataSourceRows(runtime, 'projects') : [];
      },
      { wrapper: ({ children }) => <React.StrictMode>{children}</React.StrictMode> },
    );
    await waitFor(() => expect(result.current).toEqual(records));
    unmount();
  });

  it('keeps loaded rows through the complete page runtime hook', async () => {
    const records = [{ pid: 'project-1', project_name: 'Delivery project' }];
    fetchResult.mockResolvedValue({
      code: '0',
      data: { records, total: 1, page: 1, pageSize: 100 },
    });
    const schema = {
      id: 'delivery-start',
      pageKey: 'delivery-start',
      kind: 'detail',
      blocks: [],
      dataSources: {
        projects: {
          type: 'namedQuery',
          queryCode: 'delivery_brief',
          format: 'records',
          adaptor: 'table',
        },
      },
    } as any;
    const { result, unmount, rerender } = renderHook(
      ({ loaded }) => {
        const { runtime } = usePageRuntime(loaded ? schema : null, {
          additionalContext: {
            record: {},
            row: {},
            form: {},
            $page: { kind: 'detail', pageKey: 'delivery-start' },
          },
        });
        return runtime ? readDataSourceRows(runtime, 'projects') : [];
      },
      {
        initialProps: { loaded: false },
        wrapper: ({ children }) => (
          <React.StrictMode>
            <MemoryRouter>{children}</MemoryRouter>
          </React.StrictMode>
        ),
      },
    );
    rerender({ loaded: true });
    await waitFor(() => expect(result.current).toEqual(records));
    unmount();
  });

  it('shows query rows on the actual detail page and table renderer', async () => {
    fetchResult.mockResolvedValue({
      code: '0',
      data: {
        records: [{ pid: 'project-1', name: 'Delivery project' }],
        total: 1,
        page: 1,
        pageSize: 100,
      },
    });
    const schema = {
      id: 'delivery-start',
      pageKey: 'delivery-start',
      kind: 'detail',
      title: 'Delivery',
      extension: { skipFieldMeta: true, skipDynamicFieldMeta: true },
      blocks: [
        {
          id: 'projects',
          blockType: 'table',
          dataSource: 'projects',
          columns: [{ field: 'name', label: 'Project' }],
        },
      ],
      dataSources: {
        projects: {
          type: 'namedQuery',
          queryCode: 'delivery_brief',
          format: 'records',
          adaptor: 'table',
        },
      },
    } as any;
    const view = render(
      <React.StrictMode>
        <MemoryRouter>
          <ProfileProvider value={adminProfile}>
            <DetailPageContent schema={schema} tableName="delivery-start" />
          </ProfileProvider>
        </MemoryRouter>
      </React.StrictMode>,
    );
    await waitFor(() => expect(screen.getByText('Delivery project')).toBeVisible());
    view.unmount();
  });
});
