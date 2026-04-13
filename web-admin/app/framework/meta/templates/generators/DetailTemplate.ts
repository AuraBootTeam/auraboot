/**
 * Detail Page Template Generator
 *
 * Generates a UnifiedSchema for read-only detail/view pages with:
 * - Description list layout (label-value pairs)
 * - Section grouping
 * - Back/Edit action buttons
 *
 * @since 3.8.0
 */

import type { UnifiedSchema, BlockConfig, ButtonConfig } from '~/framework/meta/schemas/types';
import type {
  TemplateModelMeta,
  TemplateOptions,
  TemplateFieldMeta,
  TemplateGenerator,
  TemplateClassOverrides,
  TemplateStyleSet,
} from '../types';
import { TEMPLATE_STYLES } from '../types';
import { mapFieldToValueType } from '../utils';

export const DetailTemplate: TemplateGenerator = {
  type: 'detail',
  generate(model: TemplateModelMeta, options: TemplateOptions = {}): UnifiedSchema {
    const { variant = 'default', classOverrides = {} } = options;

    const styles = TEMPLATE_STYLES[variant] ?? TEMPLATE_STYLES.default;
    const primaryKey = model.primaryKey ?? 'id';
    const apiBase = model.apiBasePath ?? `/api/dynamic/${model.modelCode}`;

    const detailFields = model.fields.filter((f) => f.detailVisible !== false);

    // Group fields by section
    const sections = groupFieldsIntoSections(detailFields);
    const blocks: BlockConfig[] = [];

    // Header with title + actions
    blocks.push(buildHeaderBlock(model, styles, classOverrides));

    // Detail sections
    for (const section of sections) {
      blocks.push(buildDetailSectionBlock(section, styles, classOverrides));
    }

    return {
      kind: 'page',
      version: '1.0',
      id: `${model.modelCode}_detail`,
      title: { 'zh-CN': `${model.displayName}详情`, 'en-US': `${model.displayName} Detail` },
      layout: {
        type: 'stack',
        gap: 0,
      },
      blocks,
      dataSources: {
        ds_detail: {
          type: 'api',
          endpoint: `${apiBase}/\${state.recordId}`,
          method: 'get',
          autoFetch: true,
        },
      },
      handlers: {
        onEdit: {
          type: 'flow',
          steps: [
            { action: 'navigate', args: { path: `/${model.modelCode}/\${state.recordId}/edit` } },
          ],
        },
        onBack: {
          type: 'flow',
          steps: [{ action: 'navigateBack' }],
        },
        onDelete: {
          type: 'flow',
          steps: [
            {
              action: 'confirm',
              args: { title: '确认删除', message: '删除后无法恢复，确定继续？' },
            },
            { action: 'apiCall', method: 'delete', endpoint: `${apiBase}/\${state.recordId}` },
            { action: 'toast', level: 'success', content: '删除成功' },
            { action: 'navigateBack' },
          ],
        },
      },
      state: {
        recordId: null,
      },
    };
  },
};

interface FieldSection {
  title: string;
  fields: TemplateFieldMeta[];
}

function groupFieldsIntoSections(fields: TemplateFieldMeta[]): FieldSection[] {
  const groups = new Map<string, TemplateFieldMeta[]>();

  for (const field of fields) {
    const prefix = extractSectionPrefix(field.field);
    if (!groups.has(prefix)) {
      groups.set(prefix, []);
    }
    groups.get(prefix)!.push(field);
  }

  const sections: FieldSection[] = [];
  for (const [prefix, groupFields] of groups) {
    sections.push({
      title: prefix === 'default' ? '基本信息' : prefix,
      fields: groupFields,
    });
  }

  return sections;
}

function extractSectionPrefix(fieldName: string): string {
  const parts = fieldName.split('_');
  if (parts.length >= 3) {
    return parts[0];
  }
  return 'default';
}

function buildHeaderBlock(
  model: TemplateModelMeta,
  styles: TemplateStyleSet,
  overrides: TemplateClassOverrides,
): BlockConfig {
  const buttons: ButtonConfig[] = [
    {
      code: 'back',
      label: { 'zh-CN': '返回', 'en-US': 'Back' },
      variant: 'default',
      handler: 'onBack',
      icon: 'ArrowLeft',
    },
    {
      code: 'edit',
      label: { 'zh-CN': '编辑', 'en-US': 'Edit' },
      variant: 'primary',
      handler: 'onEdit',
      icon: 'Edit',
    },
    {
      code: 'delete',
      label: { 'zh-CN': '删除', 'en-US': 'Delete' },
      variant: 'danger',
      handler: 'onDelete',
      icon: 'Trash2',
    },
  ];

  return {
    id: 'block_header',
    blockType: 'toolbar',
    title: model.displayName,
    className: `${overrides.card ?? styles.card} ${styles.toolbar}`,
    buttons,
  };
}

function buildDetailSectionBlock(
  section: FieldSection,
  styles: TemplateStyleSet,
  overrides: TemplateClassOverrides,
): BlockConfig {
  const fields = section.fields.map((f) => ({
    field: f.field,
    label: f.label,
    component: 'DetailField',
    props: {
      valueType: f.valueType ?? mapFieldToValueType(f.type),
      ...(f.options ? { options: f.options } : {}),
    },
  }));

  return {
    id: `section_${section.title}`,
    blockType: 'description',
    title: section.title,
    className: `${overrides.card ?? styles.card} ${styles.cardBody}`,
    fields,
    columns: 2,
  };
}
