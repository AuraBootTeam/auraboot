/**
 * Template Registry
 *
 * Central registry for page template generators.
 * Provides batch generation and single-page generation APIs.
 *
 * @since 3.8.0
 */

import type { UnifiedSchema } from '~/framework/meta/schemas/types';
import type {
  TemplateType,
  TemplateGenerator,
  TemplateModelMeta,
  TemplateOptions,
  BatchGenerateRequest,
  BatchGenerateResult,
} from './types';
import { ListTemplate } from './generators/ListTemplate';
import { FormTemplate } from './generators/FormTemplate';
import { DetailTemplate } from './generators/DetailTemplate';

class TemplateRegistryImpl {
  private generators = new Map<TemplateType, TemplateGenerator>();

  constructor() {
    this.register(ListTemplate);
    this.register(FormTemplate);
    this.register(DetailTemplate);
  }

  /** Register a template generator */
  register(generator: TemplateGenerator): void {
    this.generators.set(generator.type, generator);
  }

  /** Get all registered template types */
  getTypes(): TemplateType[] {
    return Array.from(this.generators.keys());
  }

  /**
   * Generate a single page schema from model metadata.
   */
  generate(type: TemplateType, model: TemplateModelMeta, options?: TemplateOptions): UnifiedSchema {
    // Handle composite types
    if (type === 'list+form') {
      return this.generateListWithForm(model, options);
    }

    const generator = this.generators.get(type);
    if (!generator) {
      throw new Error(`Unknown template type: ${type}`);
    }
    return generator.generate(model, options);
  }

  /**
   * Batch generate multiple page schemas for a model.
   * Common use case: generate list + form + detail pages at once.
   */
  batchGenerate(request: BatchGenerateRequest): BatchGenerateResult {
    const pages = request.pages.map(({ type, options }) => ({
      type,
      schema: this.generate(type, request.model, options),
    }));

    return {
      modelCode: request.model.modelCode,
      pages,
    };
  }

  /**
   * Generate a standard CRUD page set (list + form + detail).
   */
  generateCrudSet(model: TemplateModelMeta, options?: TemplateOptions): BatchGenerateResult {
    return this.batchGenerate({
      model,
      pages: [
        { type: 'list', options },
        { type: 'form', options },
        { type: 'detail', options },
      ],
    });
  }

  /**
   * Generate a list page that includes an inline form dialog.
   */
  private generateListWithForm(model: TemplateModelMeta, options?: TemplateOptions): UnifiedSchema {
    const listSchema = this.generate('list', model, options);
    const formFields = model.fields.filter((f) => f.formVisible !== false);

    // Add dialog form block to the list schema
    const formBlock = {
      id: 'block_form_dialog',
      blockType: 'form-section',
      title: model.displayName,
      visibleWhen: '${state.formDialogOpen}',
      className: 'fixed inset-0 z-50 flex items-center justify-center bg-black/50',
      fields: formFields.map((f) => ({
        field: f.field,
        label: f.label,
        component: mapFieldToComponent(f),
        props: {
          placeholder: f.placeholder ?? `请输入${f.label}`,
          ...(f.options ? { options: f.options } : {}),
        },
      })),
      columns: options?.formColumns ?? 2,
      buttons: [
        { code: 'submit', label: '保存', variant: 'primary' as const, handler: 'onFormSubmit' },
        { code: 'cancel', label: '取消', handler: 'onFormCancel' },
      ],
    };

    listSchema.blocks.push(formBlock);

    // Override handlers for dialog mode
    listSchema.handlers = {
      ...listSchema.handlers,
      onAdd: {
        type: 'flow',
        steps: [
          { action: 'setState', args: { formDialogOpen: true, formMode: 'create', formData: {} } },
        ],
      },
      onEdit: {
        type: 'flow',
        steps: [
          { action: 'setState', args: { formDialogOpen: true, formMode: 'edit' } },
          { action: 'loadRecord', args: { sourceId: 'ds_detail' } },
        ],
      },
      onFormSubmit: {
        type: 'flow',
        steps: [
          { action: 'validateForm' },
          { action: 'submitForm' },
          { action: 'toast', level: 'success', content: '保存成功' },
          { action: 'setState', args: { formDialogOpen: false } },
          { action: 'refreshDataSource', args: { sourceId: 'ds_list' } },
        ],
      },
      onFormCancel: {
        type: 'flow',
        steps: [{ action: 'setState', args: { formDialogOpen: false } }],
      },
    };

    listSchema.state = {
      ...listSchema.state,
      formDialogOpen: false,
      formMode: 'create',
      formData: {},
    };

    return listSchema;
  }
}

// Avoid circular import issue
function mapFieldToComponent(field: {
  type: string;
  dataSourceId?: string;
  options?: any[];
}): string {
  if (field.dataSourceId || field.options) return 'SmartSelect';
  switch (field.type) {
    case 'number':
      return 'SmartNumber';
    case 'boolean':
      return 'SmartSwitch';
    case 'date':
      return 'SmartDatePicker';
    case 'datetime':
      return 'SmartDateTimePicker';
    case 'enum':
      return 'SmartSelect';
    case 'text':
      return 'SmartTextarea';
    default:
      return 'SmartInput';
  }
}

/** Singleton template registry instance */
export const TemplateRegistry = new TemplateRegistryImpl();
