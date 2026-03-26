/**
 * Schema转换器
 * 将设计器的内部状态转换为与page_schema.sql兼容的JSON格式
 */

import type { Component, PageSchema, FormSchema, LayoutConfig } from '~/studio/domain/schema/types';

// 目标Schema格式接口定义（基于page_schema.sql）
export interface PageSchemaExport {
  meta: {
    title: Record<string, string>; // 多语言标题
    version: string;
    dslVersion: string;
    entityCode?: string;
  };
  events?: Array<{
    on: string;
    do: Array<{
      type: string;
      props?: Record<string, any>;
      target?: string;
      payload?: Record<string, any>;
    }>;
    if?: string;
    catch?: Array<{
      type: string;
      level?: string;
      message: string;
    }>;
    finally?: Array<{
      type: string;
      value?: any;
      target?: string;
    }>;
    concurrency?: string;
  }>;
  regions: Array<{
    type: string;
    fields?: Array<{
      code: string;
      type: string;
      props?: Record<string, any>;
      layout?: {
        span?: number;
        row?: number;
        column?: number;
      };
      validation?: Array<{
        type: string;
        message: Record<string, string>;
      }>;
    }>;
    filters?: {
      default?: Record<string, any>;
      security?: Array<{
        if?: string;
        op: string;
        field: string;
        value: any;
        required?: boolean;
      }>;
      contextual?: Array<{
        op: string;
        field: string;
        value: any;
        required?: boolean;
      }>;
    };
    pagination?: {
      pageSize: number;
    };
    actions?: Array<{
      code: string;
      props: {
        label: Record<string, string>;
        primary?: boolean;
      };
      permission?: string;
    }>;
  }>;
}

// 组件类型映射
const COMPONENT_TYPE_MAPPING: Record<string, string> = {
  SmartInput: 'input',
  SmartSelect: 'select',
  SmartTextarea: 'textarea',
  SmartCheckbox: 'checkbox',
  SmartRadio: 'radio',
  SmartDatePicker: 'dateRange',
  SmartButton: 'button',
  // 添加更多映射
};

// 验证规则映射
const VALIDATION_TYPE_MAPPING: Record<string, string> = {
  required: 'required',
  minLength: 'minLength',
  maxLength: 'maxLength',
  pattern: 'pattern',
  email: 'email',
  number: 'number',
  // 添加更多映射
};

export class SchemaConverter {
  /**
   * 将设计器状态转换为导出格式
   */
  static convertToExportSchema(
    pageSchema: PageSchema,
    layoutConfig: LayoutConfig,
    components: Component[],
  ): PageSchemaExport {
    const exportSchema: PageSchemaExport = {
      meta: {
        title: {
          'zh-CN': pageSchema.title || 'Untitled',
          'en-US': pageSchema.title || 'Untitled',
        },
        version: pageSchema.version || '1.0.0',
        dslVersion: '1.0.0',
        entityCode: pageSchema.id,
      },
      regions: [],
    };

    // 转换组件为字段
    const fields = this.convertComponentsToFields(components);

    if (fields.length > 0) {
      // 创建表单区域
      exportSchema.regions.push({
        type: 'filters',
        fields: fields,
      });

      // 创建预设区域
      exportSchema.regions.push({
        type: 'preset',
        filters: {
          default: {},
          security: [],
          contextual: [
            {
              op: 'EQ',
              field: 'tenant_id',
              value: '${context.tenantId}',
              required: true,
            },
          ],
        },
        pagination: {
          pageSize: 20,
        },
      });

      // 创建操作区域
      exportSchema.regions.push({
        type: 'action',
        actions: [
          {
            code: 'search',
            props: {
              label: {
                'zh-CN': '查询',
                'en-US': 'Search',
              },
              primary: true,
            },
          },
          {
            code: 'reset',
            props: {
              label: {
                'zh-CN': '重置',
                'en-US': 'Reset',
              },
            },
          },
        ],
      });
    }

    // 添加事件处理
    exportSchema.events = this.generateDefaultEvents();

    return exportSchema;
  }

  /**
   * 将组件转换为字段
   */
  private static convertComponentsToFields(components: Component[]): any[] {
    return components
      .filter((component) => (component as any).visible !== false)
      .map((component) => this.convertComponentToField(component))
      .filter((field) => field !== null);
  }

  /**
   * 将单个组件转换为字段
   */
  private static convertComponentToField(component: Component): any | null {
    const mappedType = COMPONENT_TYPE_MAPPING[component.type];
    if (!mappedType) {
      console.warn(`Unknown component type: ${component.type}`);
      return null;
    }

    const field: any = {
      code: component.props.name || component.id,
      type: mappedType,
      props: this.convertComponentProps(component.props, mappedType),
    };

    // 添加布局信息
    if (component.span || component.size?.span) {
      field.layout = {
        span: component.span || component.size?.span || 1,
      };
    }

    // 添加验证规则
    const validation = this.convertValidationRules(component.props);
    if (validation.length > 0) {
      field.validation = validation;
    }

    return field;
  }

  /**
   * 转换组件属性
   */
  private static convertComponentProps(
    props: Record<string, any>,
    fieldType: string,
  ): Record<string, any> {
    const convertedProps: Record<string, any> = {};

    // 通用属性
    if (props.placeholder) {
      convertedProps.placeholder = this.convertToI18n(props.placeholder);
    }
    if (props.allowClear !== undefined) {
      convertedProps.allowClear = props.allowClear;
    }
    if (props.disabled !== undefined) {
      convertedProps.disabled = props.disabled;
    }

    // 特定类型属性
    switch (fieldType) {
      case 'input':
      case 'textarea':
        if (props.maxLength) {
          convertedProps.maxLength = props.maxLength;
        }
        break;

      case 'select':
        if (props.options) {
          convertedProps.options = props.options.map((option: any) => ({
            label: this.convertToI18n(option.label || option.text),
            value: option.value,
          }));
        }
        if (props.multiple !== undefined) {
          convertedProps.multiple = props.multiple;
        }
        break;

      case 'dateRange':
        if (props.format) {
          convertedProps.format = props.format;
        }
        if (props.showTime !== undefined) {
          convertedProps.showTime = props.showTime;
        }
        if (props.ranges) {
          convertedProps.ranges = props.ranges;
        }
        break;
    }

    return convertedProps;
  }

  /**
   * 转换验证规则
   */
  private static convertValidationRules(props: Record<string, any>): any[] {
    const validation: any[] = [];

    if (props.required) {
      validation.push({
        type: 'required',
        message: {
          'zh-CN': `${props.label || '此字段'}不能为空`,
          'en-US': `${props.label || 'This field'} is required`,
        },
      });
    }

    if (props.minLength) {
      validation.push({
        type: 'minLength',
        value: props.minLength,
        message: {
          'zh-CN': `最少输入${props.minLength}个字符`,
          'en-US': `Minimum ${props.minLength} characters required`,
        },
      });
    }

    if (props.maxLength) {
      validation.push({
        type: 'maxLength',
        value: props.maxLength,
        message: {
          'zh-CN': `最多输入${props.maxLength}个字符`,
          'en-US': `Maximum ${props.maxLength} characters allowed`,
        },
      });
    }

    if (props.pattern) {
      validation.push({
        type: 'pattern',
        value: props.pattern,
        message: {
          'zh-CN': '格式不正确',
          'en-US': 'Invalid format',
        },
      });
    }

    return validation;
  }

  /**
   * 转换为多语言格式
   */
  private static convertToI18n(value: string | Record<string, string>): Record<string, string> {
    if (typeof value === 'string') {
      return {
        'zh-CN': value,
        'en-US': value,
      };
    }
    return value;
  }

  /**
   * 生成默认事件
   */
  private static generateDefaultEvents(): any[] {
    return [
      {
        on: 'search.click',
        do: [
          {
            type: 'apiCall',
            query: '${filters}',
            assign: 'dataList',
            target: 'api.listData',
          },
          {
            data: '${ds.dataList}',
            type: 'table.update',
            target: 'dataTable',
          },
          {
            type: 'toast',
            message: '查询完成',
          },
        ],
        concurrency: 'switch',
      },
      {
        on: 'row.edit',
        do: [
          {
            side: 'right',
            type: 'openDrawer',
            params: {
              id: '${row.id}',
            },
          },
        ],
      },
    ];
  }

  /**
   * 验证导出的Schema格式
   */
  static validateExportSchema(schema: PageSchemaExport): { valid: boolean; errors: string[] } {
    const errors: string[] = [];

    // 验证必需字段
    if (!schema.meta) {
      errors.push('Missing meta field');
    } else {
      if (!schema.meta.title) {
        errors.push('Missing meta.title field');
      }
      if (!schema.meta.version) {
        errors.push('Missing meta.version field');
      }
      if (!schema.meta.dslVersion) {
        errors.push('Missing meta.dslVersion field');
      }
    }

    if (!schema.regions || !Array.isArray(schema.regions)) {
      errors.push('Missing or invalid regions field');
    }

    // 验证regions结构
    if (schema.regions) {
      schema.regions.forEach((region, index) => {
        if (!region.type) {
          errors.push(`Region ${index}: missing type field`);
        }

        if (region.type === 'filters' && region.fields) {
          region.fields.forEach((field, fieldIndex) => {
            if (!field.code) {
              errors.push(`Region ${index}, Field ${fieldIndex}: missing code field`);
            }
            if (!field.type) {
              errors.push(`Region ${index}, Field ${fieldIndex}: missing type field`);
            }
          });
        }
      });
    }

    return {
      valid: errors.length === 0,
      errors,
    };
  }
}
