/**
 * platformFieldPreview.test.ts
 *
 * `buildPreviewFieldConfig` is the designer → platform translation layer for WYSIWYG field
 * previews. These cases pin the translations that have no 1:1 platform counterpart:
 *
 *  - `picker` (the designer's generic picker) → a real platform picker chosen from the
 *    field's relation metadata. The platform registry has no `picker`, so before this
 *    translation every picker — including every dragged-in relation field, which is
 *    auto-configured as a picker — rendered "Unknown component: picker".
 *  - `date` → `SmartDatePicker` (the same-named legacy platform control is a bare
 *    `<input type="date">` that emits the change *event* to `onChange`).
 *  - upload `maxFiles` → SmartUpload `maxCount` (otherwise the uploader keeps its
 *    default limit of 1 and drops every file past the first).
 */
import { describe, expect, it } from 'vitest';
import {
  buildPreviewFieldConfig,
  resolvePickerPlatformComponent,
} from '../runtime/platformFieldPreview';
import type { DslBlockV3, ModelFieldDefinition } from '../types';

function fieldBlock(props: Record<string, unknown>, field = 'owner'): DslBlockV3 {
  return { id: `field_${field}`, blockType: 'field', field, props } as DslBlockV3;
}

function modelField(overrides: Partial<ModelFieldDefinition> = {}): ModelFieldDefinition {
  return {
    modelCode: 'demo_model',
    code: 'owner',
    label: 'Owner',
    type: 'relation',
    ...overrides,
  } as ModelFieldDefinition;
}

describe('buildPreviewFieldConfig — designer picker translation', () => {
  it('renders a user relation picker as the platform user select', () => {
    const config = buildPreviewFieldConfig(
      fieldBlock({ component: 'picker', valueField: 'pid', displayField: 'displayName' }),
      modelField({ refTarget: { modelCode: 'user', valueField: 'pid', displayField: 'displayName' } }),
    );

    expect(config.component).toBe('userselect');
  });

  it('renders a multi-value user relation picker as the platform member picker', () => {
    const config = buildPreviewFieldConfig(
      fieldBlock({ component: 'picker', multiple: true }),
      modelField({ refTarget: { modelCode: 'sys_user' } }),
    );

    expect(config.component).toBe('memberpicker');
  });

  it('renders a tree-configured picker as the platform tree select', () => {
    const config = buildPreviewFieldConfig(
      fieldBlock({ component: 'picker', tree: true }),
      modelField({ refTarget: { modelCode: 'catalog_category' } }),
    );

    expect(config.component).toBe('treeselect');
  });

  it('falls back to the platform select for a plain model picker and normalizes the relation dataType', () => {
    const config = buildPreviewFieldConfig(
      fieldBlock({ component: 'picker', pickerDataSource: 'model', pickerSource: 'invoice' }),
      modelField({ refTarget: { modelCode: 'invoice' } }),
    );

    expect(config.component).toBe('SmartSelect');
    // ControlledFieldRenderer builds the `/api/dynamic/<model>/list` option data source
    // for `reference` fields only, so a relation field must reach it as `reference`.
    expect(config.type).toBe('reference');
  });

  it('uses the inspector-authored pickerSource when the model field has no refTarget', () => {
    const config = buildPreviewFieldConfig(
      fieldBlock({ component: 'picker', pickerSource: 'user' }),
      modelField({ type: 'string', refTarget: undefined }),
    );

    expect(config.component).toBe('userselect');
  });

  it('never leaves the unrenderable generic picker in the platform field config', () => {
    const config = buildPreviewFieldConfig(
      fieldBlock({ component: 'picker' }),
      modelField({ type: 'string', refTarget: undefined }),
    );

    expect(config.component).not.toBe('picker');
    expect(config.component).toBe('SmartSelect');
  });

  it('keeps designer-only picker props out of the control props bag', () => {
    const config = buildPreviewFieldConfig(
      fieldBlock({
        component: 'picker',
        pickerDataSource: 'named-query',
        pickerQueryCode: 'udw_page_options',
        pickerSource: 'page_schema',
        displayField: 'name',
        pageSize: 1000,
      }),
      modelField({ refTarget: { modelCode: 'page_schema' } }),
    );

    expect(config.props).not.toHaveProperty('pickerDataSource');
    expect(config.props).not.toHaveProperty('pickerQueryCode');
    expect(config.props).not.toHaveProperty('pickerSource');
    expect(config.props).not.toHaveProperty('displayField');
    expect(config.props).not.toHaveProperty('pageSize');
  });

  it('exposes the picker resolution as a reusable pure helper', () => {
    expect(resolvePickerPlatformComponent({}, modelField({ refTarget: { modelCode: 'user' } }))).toBe(
      'userselect',
    );
    expect(resolvePickerPlatformComponent({}, modelField({ refTarget: { modelCode: 'sys_org' } }))).toBe(
      'organizationselect',
    );
  });
});

describe('buildPreviewFieldConfig — date translation', () => {
  it('maps the designer date component to the real platform date picker', () => {
    const config = buildPreviewFieldConfig(
      fieldBlock({ component: 'date', placeholder: 'YYYY-MM-DD' }, 'created_at'),
      modelField({ code: 'created_at', type: 'datetime' }),
    );

    // The legacy `date` control emits the raw change event to onChange (binding
    // `[object Object]`); SmartDatePicker round-trips a string through useSmartField.
    expect(config.component).toBe('SmartDatePicker');
  });

  it('maps the designer datetime component to the real platform date picker', () => {
    const config = buildPreviewFieldConfig(
      fieldBlock({ component: 'datetime' }, 'created_at'),
      modelField({ code: 'created_at', type: 'datetime' }),
    );

    expect(config.component).toBe('SmartDatePicker');
  });

  it('leaves components that already resolve to a real platform control untouched', () => {
    const config = buildPreviewFieldConfig(
      fieldBlock({ component: 'textarea' }, 'notes'),
      modelField({ code: 'notes', type: 'text' }),
    );

    expect(config.component).toBe('textarea');
  });
});

describe('buildPreviewFieldConfig — upload translation', () => {
  it('maps designer maxFiles to the platform uploader file limit', () => {
    const config = buildPreviewFieldConfig(
      fieldBlock(
        { component: 'upload', accept: '.pdf,.docx', multiple: true, maxFiles: 2 },
        'attachment',
      ),
      modelField({ code: 'attachment', type: 'file' }),
    );

    expect(config.props?.maxCount).toBe(2);
    expect(config.props?.accept).toBe('.pdf,.docx');
    expect(config.props?.multiple).toBe(true);
    expect(config.props).not.toHaveProperty('maxFiles');
  });

  it('does not override an explicitly configured maxCount', () => {
    const config = buildPreviewFieldConfig(
      fieldBlock({ component: 'upload', maxFiles: 2, maxCount: 5 }, 'attachment'),
      modelField({ code: 'attachment', type: 'file' }),
    );

    expect(config.props?.maxCount).toBe(5);
  });

  it('ignores a non-positive maxFiles instead of disabling the uploader', () => {
    const config = buildPreviewFieldConfig(
      fieldBlock({ component: 'upload', maxFiles: 0 }, 'attachment'),
      modelField({ code: 'attachment', type: 'file' }),
    );

    expect(config.props?.maxCount).toBeUndefined();
  });
});
