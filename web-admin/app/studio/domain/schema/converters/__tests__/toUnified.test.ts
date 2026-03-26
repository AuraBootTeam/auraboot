import { describe, it, expect } from 'vitest';
import { convertSchemaToUnified } from '~/studio/domain/schema/converters/toUnified';
import type { FormSchema } from '~/studio/domain/schema/types';

const createBaseSchema = (): FormSchema => ({
  id: 'form_1',
  kind: 'form',
  title: '测试表单',
  description: 'Studio schema compatibility test',
  version: '1.0.0',
  components: [
    {
      id: 'block_basic',
      type: 'section',
      name: '基础信息',
      props: {},
      components: [
        {
          id: 'field_code',
          type: 'input',
          name: '编码',
          position: { row: 0, column: 0 },
          size: { width: 2, height: 1, span: 2 },
          props: {
            name: 'code',
            label: '编码',
            required: true,
          },
        },
      ],
    },
  ],
  layout: {
    type: 'grid',
    spacing: 16,
    padding: 24,
    columns: 4,
  },
  theme: {
    primaryColor: '#3B82F6',
    backgroundColor: '#FFFFFF',
    textColor: '#1F2937',
    borderRadius: 8,
  },
  metadata: {
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    createdBy: 'tester',
    tags: [],
  },
});

describe('convertSchemaToUnified', () => {
  it('将设计器Schema转换为运行时结构', () => {
    const schema = createBaseSchema();
    const studioResult = convertSchemaToUnified(schema);

    expect(studioResult).toMatchObject({
      kind: 'Form',
      version: '1.0.0',
      id: 'form_1',
      title: '测试表单',
      description: 'Studio schema compatibility test',
      layout: {
        areas: ['main'],
        areasConfig: {
          main: {
            type: 'grid',
            cols: 4,
            rowGap: 16,
            colGap: 16,
            padding: 24,
          },
        },
      },
      areas: {
        main: {
          blocks: [
            {
              id: 'block_basic',
              blockType: 'section',
              title: '基础信息',
              fields: [
                {
                  field: 'code',
                  label: '编码',
                  component: 'input',
                  props: expect.objectContaining({
                    name: 'code',
                    label: '编码',
                    required: true,
                  }),
                  validation: [
                    {
                      type: 'required',
                      message: { 'zh-CN': '该字段必填' },
                    },
                  ],
                },
              ],
            },
          ],
        },
      },
      theme: {
        tokens: {
          'color.primary': '#3B82F6',
          'color.surface': '#FFFFFF',
          'color.text': '#1F2937',
          'border.radius': '8',
        },
      },
    });
  });
});
