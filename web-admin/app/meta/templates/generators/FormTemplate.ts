/**
 * Form Page Template Generator
 *
 * Generates a UnifiedSchema for create/edit forms with:
 * - Multi-column grid layout
 * - Auto-grouped sections (by field grouping)
 * - Validation rules from field metadata
 * - Submit/Cancel button group
 *
 * @since 3.8.0
 */

import type { UnifiedSchema, FieldConfig, BlockConfig, ButtonConfig } from '~/meta/schemas/types';
import type {
  TemplateModelMeta,
  TemplateOptions,
  TemplateFieldMeta,
  TemplateGenerator,
  TemplateClassOverrides,
  TemplateStyleSet,
} from '../types';
import { TEMPLATE_STYLES } from '../types';
import { mapFieldToComponent, buildValidationRules } from '../utils';

export const FormTemplate: TemplateGenerator = {
  type: 'form',
  generate(model: TemplateModelMeta, options: TemplateOptions = {}): UnifiedSchema {
    const {
      variant = 'default',
      formColumns = 2,
      classOverrides = {},
      formMode = 'page',
    } = options;

    const styles = TEMPLATE_STYLES[variant] ?? TEMPLATE_STYLES.default;
    const primaryKey = model.primaryKey ?? 'id';
    const apiBase = model.apiBasePath ?? `/api/dynamic/${model.modelCode}`;

    const formFields = model.fields.filter((f) => f.formVisible !== false);

    // Group fields by section (fields with same prefix before '_' go together)
    const sections = groupFieldsIntoSections(formFields);
    const blocks: BlockConfig[] = [];

    for (const section of sections) {
      blocks.push(buildFormSectionBlock(section, formColumns, styles, classOverrides));
    }

    // Button group
    blocks.push(buildFormButtonsBlock(styles, classOverrides));

    return {
      kind: 'form',
      version: '1.0',
      id: `${model.modelCode}_form`,
      title: { 'zh-CN': `${model.displayName}表单`, 'en-US': `${model.displayName} Form` },
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
          autoFetch: false,
        },
        ...buildFieldDataSources(formFields),
      },
      handlers: {
        onLoad: {
          type: 'flow',
          steps: [
            {
              type: 'if',
              condition: '${state.recordId}',
              action: 'fetchDataSource',
              args: { sourceId: 'ds_detail' },
            },
          ],
        },
        onSubmit: {
          type: 'flow',
          steps: [
            { action: 'validateForm' },
            {
              type: 'if',
              condition: '${state.recordId}',
              action: 'apiCall',
              method: 'put',
              endpoint: `${apiBase}/\${state.recordId}`,
              body: '${formData}',
            },
            {
              type: 'if',
              condition: '${!state.recordId}',
              action: 'apiCall',
              method: 'post',
              endpoint: apiBase,
              body: '${formData}',
            },
            { action: 'toast', level: 'success', content: '保存成功' },
            { action: 'navigateBack' },
          ],
        },
        onCancel: {
          type: 'flow',
          steps: [{ action: 'navigateBack' }],
        },
      },
      state: {
        recordId: null,
        formData: {},
        mode: 'create',
      },
    };
  },
};

interface FieldSection {
  title: string;
  fields: TemplateFieldMeta[];
}

function groupFieldsIntoSections(fields: TemplateFieldMeta[]): FieldSection[] {
  // Simple grouping: all fields in a single "Basic Info" section
  // For more complex models, fields could be grouped by naming convention
  const sections: FieldSection[] = [];
  const groups = new Map<string, TemplateFieldMeta[]>();

  for (const field of fields) {
    const prefix = extractSectionPrefix(field.field);
    if (!groups.has(prefix)) {
      groups.set(prefix, []);
    }
    groups.get(prefix)!.push(field);
  }

  for (const [prefix, groupFields] of groups) {
    sections.push({
      title: prefix === 'default' ? '基本信息' : prefix,
      fields: groupFields,
    });
  }

  return sections;
}

function extractSectionPrefix(fieldName: string): string {
  // Group by common prefixes: contact_name, contact_phone → "联系信息"
  // For now, treat all as "default" unless explicitly prefixed
  const parts = fieldName.split('_');
  if (parts.length >= 3) {
    return parts[0];
  }
  return 'default';
}

function buildFormSectionBlock(
  section: FieldSection,
  columns: number,
  styles: TemplateStyleSet,
  overrides: TemplateClassOverrides,
): BlockConfig {
  const fields: FieldConfig[] = section.fields.map((f) => {
    const component = mapFieldToComponent(f);
    const span = f.type === 'text' ? columns : 1;

    return {
      field: f.field,
      label: f.label,
      component,
      span,
      props: {
        placeholder: f.placeholder ?? getPlaceholder(f, component),
        ...(f.options ? { options: f.options } : {}),
        ...(f.maxLength ? { maxLength: f.maxLength } : {}),
      },
      validation: buildValidationRules(f),
      ...(f.dataSourceId ? { dataSource: f.dataSourceId } : {}),
    };
  });

  return {
    id: `section_${section.title}`,
    blockType: 'form-section',
    title: section.title,
    className: `${overrides.card ?? styles.card} ${styles.cardBody}`,
    fields,
    columns,
    gap: '16px',
  };
}

function buildFormButtonsBlock(
  styles: TemplateStyleSet,
  overrides: TemplateClassOverrides,
): BlockConfig {
  const buttons: ButtonConfig[] = [
    {
      code: 'submit',
      label: { 'zh-CN': '保存', 'en-US': 'Save' },
      variant: 'primary',
      handler: 'onSubmit',
      icon: 'Save',
    },
    {
      code: 'cancel',
      label: { 'zh-CN': '取消', 'en-US': 'Cancel' },
      variant: 'default',
      handler: 'onCancel',
    },
  ];

  return {
    id: 'block_buttons',
    blockType: 'form-buttons',
    className: overrides.buttonGroup ?? styles.buttonGroup,
    buttons,
  };
}

function buildFieldDataSources(fields: TemplateFieldMeta[]): Record<string, any> {
  const sources: Record<string, any> = {};
  for (const f of fields) {
    if (f.dataSourceId) {
      sources[f.dataSourceId] = {
        type: 'api',
        endpoint: '/api/datasource/list',
        method: 'get',
        params: { datasourceId: f.dataSourceId },
        adaptor: 'optionList',
        autoFetch: true,
      };
    }
  }
  return sources;
}

function getPlaceholder(field: TemplateFieldMeta, component: string): string {
  if (component.includes('Select') || component.includes('Date')) {
    return `请选择${field.label}`;
  }
  return `请输入${field.label}`;
}
