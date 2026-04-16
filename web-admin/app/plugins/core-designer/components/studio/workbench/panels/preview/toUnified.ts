import type { UnifiedSchema, BlockConfig, FieldConfig, ThemeConfig } from '~/framework/meta/schemas/types';
import type { FormSchema, Block, Component } from '~/plugins/core-designer/components/studio/workbench/canvas/types';

/**
 * Convert the studio FormSchema into the runtime UnifiedSchema format.
 * Currently mirrors the legacy designer implementation to keep behaviour
 * identical while the migration progresses.
 */
export function convertSchemaToUnified(schema: FormSchema): UnifiedSchema {
  const layoutColumns = schema.layout?.columns || 1;
  const spacing = schema.layout?.spacing ?? (schema.layout as any)?.gap ?? 16;
  const padding = schema.layout?.padding ?? 16;

  return {
    kind: 'form',
    version: schema.version || '1.0.0',
    id: schema.id,
    title: schema.title,
    description: schema.description,
    layout: {
      type: 'grid',
      cols: layoutColumns,
      rowGap: spacing,
      colGap: spacing,
    },
    blocks: flattenBlocks(schema.components),
    dataSources: (schema as any).dataSources || {},
    handlers: (schema as any).handlers || {},
    theme: convertTheme(schema),
    linkageRules: schema.linkageRules,
  };
}

function flattenBlocks(blocks: Block[] = []): BlockConfig[] {
  return blocks.flatMap((block, index) => {
    const fields = convertComponentsToFields(block.components);
    const childBlocks = flattenBlocks(block.children || []);

    const currentBlock: BlockConfig[] =
      fields.length > 0
        ? [
            {
              id: block.id || `block_${index}`,
              blockType: block.type || 'form-section',
              title: block.name,
              className: block.styles?.className,
              layout: block.layout
                ? {
                    colSpan: (block.layout as any).columns,
                    rowSpan: (block.layout as any).rows,
                  }
                : undefined,
              fields,
            },
          ]
        : [];

    return [...currentBlock, ...childBlocks];
  });
}

function convertComponentsToFields(components: Component[] = []): FieldConfig[] {
  return components.map((component, index) => {
    const fieldName = String(component.props?.name || component.id || `field_${index}`);

    const validation = buildValidation(component.props) ?? [];

    return {
      field: fieldName,
      label: component.props?.label || component.name || fieldName,
      component: component.type,
      props: {
        ...component.props,
      },
      layout: component.span
        ? {
            colSpan: Number(component.span),
          }
        : undefined,
      validation: validation.length > 0 ? validation : undefined,
      visibleWhen: component.props?.visibleWhen,
      enableWhen: component.props?.enableWhen,
      disableWhen: component.props?.disableWhen,
      dataSource: component.props?.dataSource,
    };
  });
}

function buildValidation(props: Record<string, any> = {}): FieldConfig['validation'] {
  const rules: NonNullable<FieldConfig['validation']> = [];

  if (props.required) {
    rules.push({
      type: 'required',
      message: props.requiredMessage || { 'zh-CN': '该字段必填' },
    });
  }

  if (typeof props.minLength === 'number') {
    rules.push({
      type: 'min',
      min: props.minLength,
      message: props.minLengthMessage || { 'zh-CN': `最少输入 ${props.minLength} 个字符` },
    });
  }

  if (typeof props.maxLength === 'number') {
    rules.push({
      type: 'max',
      max: props.maxLength,
      message: props.maxLengthMessage || { 'zh-CN': `最多输入 ${props.maxLength} 个字符` },
    });
  }

  if (props.pattern) {
    rules.push({
      type: 'pattern',
      pattern: props.pattern,
      message: props.patternMessage || { 'zh-CN': '格式不正确' },
    });
  }

  return rules;
}

function convertTheme(schema: FormSchema): ThemeConfig | undefined {
  if (!schema.theme) {
    return undefined;
  }

  return {
    tokens: {
      'color.primary': schema.theme.primaryColor,
      'color.surface': schema.theme.backgroundColor,
      'color.text': schema.theme.textColor,
      'border.radius': String(schema.theme.borderRadius ?? 8),
    },
  };
}
