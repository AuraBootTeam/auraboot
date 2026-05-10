import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { validateStructure } from '../../validation/DslValidator';
import { canonicalizePageSchemaDto, type PageSchemaDTO } from '../canonicalizePageDsl';

function collectPluginPageFiles(dir: string): string[] {
  if (!existsSync(dir)) return [];

  return readdirSync(dir).flatMap((entry) => {
    const path = resolve(dir, entry);
    const stat = statSync(path);
    if (stat.isDirectory()) {
      return collectPluginPageFiles(path);
    }
    return path.includes('/config/pages') && path.endsWith('.json') ? [path] : [];
  });
}

function readPages(file: string): PageSchemaDTO[] {
  const data = JSON.parse(readFileSync(file, 'utf8'));
  if (Array.isArray(data)) return data;
  if (Array.isArray(data.pages)) return data.pages;
  return [data];
}

describe('canonicalizePageSchemaDto', () => {
  it('builds a structurally valid canonical schema from a backend PageSchemaDTO', () => {
    const schema = canonicalizePageSchemaDto({
      pid: 'page-001',
      pageKey: 'meta_models_admin',
      modelCode: 'meta_models',
      modelCategory: null,
      name: 'Model Management',
      title: {
        'zh-CN': '模型',
        'en-US': 'Models',
      },
      description: '',
      kind: 'list',
      profile: 'admin',
      schemaVersion: 4,
      metaInfo: {},
      isTemplate: false,
      layout: {
        type: 'stack',
      },
      blocks: [
        {
          id: 'model_table',
          blockType: 'table',
          table: {
            rowKey: 'pid',
            dataSource: 'modelList',
            columns: [
              {
                field: 'code',
                valueType: 'meta_model_code',
              },
            ],
          },
        },
      ],
      extension: {
        dataSource: {
          type: 'api',
          endpoint: '/api/meta/models',
          method: 'get',
        },
        options: {
          enableCreate: true,
        },
      },
    });

    expect(schema.id).toBe('page-001');
    expect(schema.version).toBe('1.0.0');
    expect(schema.pageKey).toBe('meta_models_admin');
    expect((schema.blocks[0] as any).table.columns[0]).toMatchObject({
      field: 'code',
      cellRenderer: 'meta_model_code',
    });
    expect((schema.blocks[0] as any).table.columns[0].valueType).toBeUndefined();
    expect(schema.dataSource).toEqual({
      type: 'api',
      endpoint: '/api/meta/models',
      method: 'get',
    });
    expect(schema.options).toEqual({ enableCreate: true });
    expect(validateStructure(schema)).toEqual([]);
  });

  it('normalizes legacy button shortcuts, structured visibility, and inline block data sources', () => {
    const schema = canonicalizePageSchemaDto({
      pageKey: 'legacy_actions_list',
      modelCode: 'legacy_model',
      modelCategory: null,
      kind: 'list',
      layout: { type: 'stack' },
      blocks: [
        {
          id: 'toolbar',
          blockType: 'toolbar',
          buttons: [
            {
              code: 'edit',
              commandCode: 'legacy:update',
              navigateTo: 'legacy_form',
              confirmMessageKey: 'legacy.confirm',
              visibleWhen: { field: 'status', operator: 'EQ', value: 'draft' },
            },
          ],
        },
        {
          id: 'summary',
          blockType: 'stat-card',
          dataSource: {
            kind: 'namedQuery',
            queryCode: 'legacy_summary',
            url: '/api/datasource/list',
          },
        },
      ],
    });

    const button = (schema.blocks[0] as any).buttons[0];
    expect(button).toMatchObject({
      action: {
        type: 'navigate',
        to: 'legacy_form',
        command: 'legacy:update',
      },
      confirm: 'legacy.confirm',
      visibleWhen: '(row?.["status"] ?? record?.["status"] ?? form?.["status"]) === "draft"',
    });
    expect(button.commandCode).toBeUndefined();
    expect(button.navigateTo).toBeUndefined();
    expect(button.confirmMessageKey).toBeUndefined();

    expect((schema.blocks[1] as any).dataSource).toBe('summary_dataSource');
    expect(schema.dataSources?.summary_dataSource).toMatchObject({
      id: 'summary_dataSource',
      type: 'namedQuery',
      queryCode: 'legacy_summary',
      endpoint: '/api/datasource/list',
    });
    expect(validateStructure(schema)).toEqual([]);
  });

  it('normalizes nested tab blocks, sub-table columns, row actions, and custom cell renderers', () => {
    const schema = canonicalizePageSchemaDto({
      pageKey: 'detail_nested',
      modelCode: 'detail_model',
      modelCategory: null,
      kind: 'detail',
      layout: { type: 'stack' },
      blocks: [
        {
          id: 'tabs',
          blockType: 'tabs',
          tabs: [
            {
              key: 'history',
              label: 'History',
              blocks: [
                {
                  id: 'history_table',
                  blockType: 'sub-table',
                  subTable: {
                    childModel: 'history_item',
                    parentField: 'parent_id',
                    columns: [
                      {
                        field: 'actor',
                        valueType: 'user_avatar_name',
                      },
                    ],
                    actions: [
                      {
                        code: 'open',
                        navigateTo: 'history_detail',
                      },
                    ],
                  },
                },
              ],
            },
          ],
        },
      ],
    });

    const subTable = (schema.blocks[0] as any).tabs[0].blocks[0].subTable;
    expect(subTable.columns[0]).toMatchObject({
      field: 'actor',
      cellRenderer: 'user_avatar_name',
    });
    expect(subTable.columns[0].valueType).toBeUndefined();
    expect(subTable.actions[0]).toMatchObject({
      action: {
        type: 'navigate',
        to: 'history_detail',
      },
    });
    expect(validateStructure(schema)).toEqual([]);
  });

  it('canonicalizes checked-in plugin page configs before structure validation', () => {
    const root = resolve(process.cwd(), '..');
    const pageFiles = collectPluginPageFiles(resolve(root, 'plugins'));
    const failures = pageFiles.flatMap((file) =>
      readPages(file).flatMap((page) => {
        const schema = canonicalizePageSchemaDto(page);
        return validateStructure(schema).map((message) => ({
          file: file.replace(`${root}/`, ''),
          pageKey: page.pageKey,
          path: message.path,
          message: message.message,
        }));
      }),
    );

    expect(failures).toEqual([]);
  });
});
