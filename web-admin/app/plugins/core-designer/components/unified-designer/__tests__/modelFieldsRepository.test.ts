import { describe, expect, it, vi } from 'vitest';
import {
  collectModelCodesFromDocument,
  loadModelFieldsByModelCodes,
  type ModelFieldFetcher,
} from '../persistence/modelFieldsRepository';
import type { PageSchemaV3 } from '../types';

describe('modelFieldsRepository', () => {
  it('collects unique model codes from the document and nested block data sources', () => {
    const document: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'composite',
      id: 'mixed_page',
      modelCode: 'root_model',
      blocks: [
        {
          id: 'form_order',
          blockType: 'form',
          dataSource: { model: 'order' },
          blocks: [
            {
              id: 'section_basic',
              blockType: 'form-section',
              blocks: [{ id: 'field_total', blockType: 'field', field: 'total' }],
            },
          ],
        },
        {
          id: 'list_order',
          blockType: 'list',
          dataSource: { model: 'order' },
        },
        {
          id: 'list_customer',
          blockType: 'list',
          dataSource: { model: 'customer' },
        },
      ],
    };

    expect(collectModelCodesFromDocument(document)).toEqual([
      'root_model',
      'order',
      'customer',
    ]);
  });

  it('loads model fields through model code to pid and maps backend field metadata', async () => {
    const fetcherMock = vi.fn(async (url: string) => {
      if (url === '/api/meta/models/code/customer') {
        return { data: { pid: 'model_customer_pid' } };
      }
      if (url === '/api/meta/models/model_customer_pid/fields') {
        return {
          data: [
            {
              code: 'email',
              dataType: 'email',
              dictCode: 'email_status',
              required: true,
              displayName: 'Email',
              uiSchema: { component: 'input' },
            },
            {
              code: 'status',
              dataType: 'enum',
              extension: { displayName: 'Status' },
            },
            {
              code: 'owner_id',
              dataType: 'relation',
              displayName: 'Owner',
              refTarget: {
                modelCode: 'user',
                valueField: 'pid',
                displayField: 'displayName',
              },
            },
          ],
        };
      }
      throw new Error(`Unexpected URL: ${url}`);
    });
    const fetcher = fetcherMock as unknown as ModelFieldFetcher;

    await expect(
      loadModelFieldsByModelCodes(['customer', 'customer'], fetcher),
    ).resolves.toEqual({
      customer: [
        {
          modelCode: 'customer',
          code: 'email',
          label: 'Email',
          type: 'email',
          component: 'input',
          dictCode: 'email_status',
          required: true,
        },
        {
          modelCode: 'customer',
          code: 'status',
          label: 'Status',
          type: 'enum',
          component: undefined,
          required: false,
        },
        {
          modelCode: 'customer',
          code: 'owner_id',
          label: 'Owner',
          type: 'relation',
          component: undefined,
          required: false,
          refTarget: {
            modelCode: 'user',
            valueField: 'pid',
            displayField: 'displayName',
          },
        },
      ],
    });
    expect(fetcherMock).toHaveBeenCalledTimes(2);
    expect(fetcherMock).toHaveBeenNthCalledWith(1, '/api/meta/models/code/customer');
    expect(fetcherMock).toHaveBeenNthCalledWith(
      2,
      '/api/meta/models/model_customer_pid/fields',
    );
    expect(fetcherMock).not.toHaveBeenCalledWith(
      '/api/meta/view-models/customer/resolved-fields',
    );
  });

  it('loads view-model resolved fields after management model lookup misses', async () => {
    const fetcherMock = vi.fn(async (url: string) => {
      if (url === '/api/meta/models/code/customer_view') {
        throw new Error('not a physical model');
      }
      if (url === '/api/meta/view-models/customer_view/resolved-fields') {
        return {
          data: [
            {
              code: 'gross_margin',
              aliasCode: 'margin',
              displayName: 'Gross Margin',
              dataType: 'decimal',
              required: false,
              uiHint: { component: 'number' },
            },
            {
              code: 'lifecycle_status',
              displayName: 'Lifecycle Status',
              returnType: 'enum',
              uiHint: { widgetType: 'select' },
            },
          ],
        };
      }
      throw new Error(`Unexpected URL: ${url}`);
    });
    const fetcher = fetcherMock as unknown as ModelFieldFetcher;

    await expect(loadModelFieldsByModelCodes(['customer_view'], fetcher)).resolves.toEqual({
      customer_view: [
        {
          modelCode: 'customer_view',
          code: 'margin',
          label: 'Gross Margin',
          type: 'decimal',
          component: 'number',
          required: false,
        },
        {
          modelCode: 'customer_view',
          code: 'lifecycle_status',
          label: 'Lifecycle Status',
          type: 'enum',
          component: 'select',
          required: false,
        },
      ],
    });
    expect(fetcherMock).toHaveBeenCalledTimes(2);
    expect(fetcherMock).toHaveBeenNthCalledWith(1, '/api/meta/models/code/customer_view');
    expect(fetcherMock).toHaveBeenNthCalledWith(
      2,
      '/api/meta/view-models/customer_view/resolved-fields',
    );
  });

  it('flags virtual / computed resolved fields', async () => {
    const fetcherMock = vi.fn(async (url: string) => {
      if (url === '/api/meta/models/code/metrics_view') {
        throw new Error('not a physical model');
      }
      if (url === '/api/meta/view-models/metrics_view/resolved-fields') {
        return {
          data: [
            { code: 'name', displayName: 'Name', dataType: 'string' },
            { code: 'margin', displayName: 'Margin', dataType: 'decimal', virtual: true },
            {
              code: 'total',
              displayName: 'Total',
              dataType: 'decimal',
              computeExpression: 'price * qty',
            },
          ],
        };
      }
      throw new Error(`Unexpected URL: ${url}`);
    });
    const fetcher = fetcherMock as unknown as ModelFieldFetcher;

    const result = await loadModelFieldsByModelCodes(['metrics_view'], fetcher);
    const byCode = Object.fromEntries(result.metrics_view.map((field) => [field.code, field]));
    expect(byCode.name.virtual).toBeUndefined();
    expect(byCode.margin.virtual).toBe(true);
    expect(byCode.total.virtual).toBe(true);
  });

  it('falls back to query-builder model fields when management model lookup is unavailable', async () => {
    const fetcherMock = vi.fn(async (url: string) => {
      if (url === '/api/meta/models/code/customer') {
        throw new Error('management model lookup unavailable');
      }
      if (url === '/api/meta/view-models/customer/resolved-fields') {
        throw new Error('not a view model');
      }
      if (url === '/api/query-builder/models/customer/fields') {
        return {
          data: [
            {
              code: 'title',
              displayName: 'Title',
              dataType: 'string',
              required: true,
            },
            {
              code: 'stage',
              displayName: 'Stage',
              dataType: 'enum',
            },
          ],
        };
      }
      throw new Error(`Unexpected URL: ${url}`);
    });
    const fetcher = fetcherMock as unknown as ModelFieldFetcher;

    await expect(loadModelFieldsByModelCodes(['customer'], fetcher)).resolves.toEqual({
      customer: [
        {
          modelCode: 'customer',
          code: 'title',
          label: 'Title',
          type: 'string',
          required: true,
        },
        {
          modelCode: 'customer',
          code: 'stage',
          label: 'Stage',
          type: 'enum',
          required: false,
        },
      ],
    });
    expect(fetcherMock).toHaveBeenNthCalledWith(1, '/api/meta/models/code/customer');
    expect(fetcherMock).toHaveBeenNthCalledWith(
      2,
      '/api/meta/view-models/customer/resolved-fields',
    );
    expect(fetcherMock).toHaveBeenCalledWith('/api/query-builder/models/customer/fields');
  });
});
