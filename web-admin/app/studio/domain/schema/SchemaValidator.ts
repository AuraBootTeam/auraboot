/**
 * Schema验证器
 * 用于验证导出的JSON格式是否与page_schema.sql兼容
 */

import { SchemaConverter } from '~/studio/domain/schema/converters/SchemaConverter';
import type { PageSchemaExport } from '~/studio/domain/schema/converters/SchemaConverter';

export interface ValidationResult {
  valid: boolean;
  errors: string[];
  warnings: string[];
  score: number; // 兼容性评分 0-100
}

export class SchemaValidator {
  /**
   * 验证完整的Schema结构
   */
  static validateSchema(schema: PageSchemaExport): ValidationResult {
    const errors: string[] = [];
    const warnings: string[] = [];
    let score = 100;

    // 验证必需的顶级字段
    const requiredFields = ['meta', 'regions'];
    requiredFields.forEach((field) => {
      if (!schema[field as keyof PageSchemaExport]) {
        errors.push(`缺少必需字段: ${field}`);
        score -= 20;
      }
    });

    // 验证meta字段
    if (schema.meta) {
      const metaValidation = this.validateMeta(schema.meta);
      errors.push(...metaValidation.errors);
      warnings.push(...metaValidation.warnings);
      score -= metaValidation.penalty;
    }

    // 验证regions字段
    if (schema.regions) {
      const regionsValidation = this.validateRegions(schema.regions);
      errors.push(...regionsValidation.errors);
      warnings.push(...regionsValidation.warnings);
      score -= regionsValidation.penalty;
    }

    // 验证events字段（可选）
    if (schema.events) {
      const eventsValidation = this.validateEvents(schema.events);
      errors.push(...eventsValidation.errors);
      warnings.push(...eventsValidation.warnings);
      score -= eventsValidation.penalty;
    }

    return {
      valid: errors.length === 0,
      errors,
      warnings,
      score: Math.max(0, score),
    };
  }

  /**
   * 验证meta字段
   */
  private static validateMeta(meta: any): {
    errors: string[];
    warnings: string[];
    penalty: number;
  } {
    const errors: string[] = [];
    const warnings: string[] = [];
    let penalty = 0;

    // 验证必需字段
    if (!meta.title) {
      errors.push('meta.title 字段缺失');
      penalty += 10;
    } else if (typeof meta.title !== 'object') {
      errors.push('meta.title 必须是多语言对象');
      penalty += 5;
    } else {
      // 验证多语言格式
      const requiredLangs = ['zh-CN', 'en-US'];
      requiredLangs.forEach((lang) => {
        if (!meta.title[lang]) {
          warnings.push(`meta.title 缺少 ${lang} 语言版本`);
          penalty += 2;
        }
      });
    }

    if (!meta.version) {
      errors.push('meta.version 字段缺失');
      penalty += 5;
    }

    if (!meta.dslVersion) {
      errors.push('meta.dslVersion 字段缺失');
      penalty += 5;
    }

    // 验证可选字段
    if (meta.entityCode && typeof meta.entityCode !== 'string') {
      warnings.push('meta.entityCode 应该是字符串类型');
      penalty += 1;
    }

    return { errors, warnings, penalty };
  }

  /**
   * 验证regions字段
   */
  private static validateRegions(regions: any[]): {
    errors: string[];
    warnings: string[];
    penalty: number;
  } {
    const errors: string[] = [];
    const warnings: string[] = [];
    let penalty = 0;

    if (!Array.isArray(regions)) {
      errors.push('regions 必须是数组');
      return { errors, warnings, penalty: 20 };
    }

    if (regions.length === 0) {
      warnings.push('regions 数组为空');
      penalty += 5;
    }

    // 验证每个region
    regions.forEach((region, index) => {
      const regionValidation = this.validateRegion(region, index);
      errors.push(...regionValidation.errors);
      warnings.push(...regionValidation.warnings);
      penalty += regionValidation.penalty;
    });

    // 验证region类型的完整性
    const regionTypes = regions.map((r) => r.type).filter(Boolean);
    const expectedTypes = ['filters', 'preset', 'action'];
    const missingTypes = expectedTypes.filter((type) => !regionTypes.includes(type));

    if (missingTypes.length > 0) {
      warnings.push(`建议包含以下region类型: ${missingTypes.join(', ')}`);
      penalty += missingTypes.length * 2;
    }

    return { errors, warnings, penalty };
  }

  /**
   * 验证单个region
   */
  private static validateRegion(
    region: any,
    index: number,
  ): { errors: string[]; warnings: string[]; penalty: number } {
    const errors: string[] = [];
    const warnings: string[] = [];
    let penalty = 0;

    if (!region.type) {
      errors.push(`Region ${index}: 缺少 type 字段`);
      penalty += 5;
      return { errors, warnings, penalty };
    }

    // 根据类型验证特定字段
    switch (region.type) {
      case 'filters':
        if (!region.fields || !Array.isArray(region.fields)) {
          errors.push(`Region ${index} (filters): 缺少 fields 数组`);
          penalty += 10;
        } else {
          region.fields.forEach((field: any, fieldIndex: number) => {
            const fieldValidation = this.validateField(field, index, fieldIndex);
            errors.push(...fieldValidation.errors);
            warnings.push(...fieldValidation.warnings);
            penalty += fieldValidation.penalty;
          });
        }
        break;

      case 'preset':
        if (!region.filters) {
          warnings.push(`Region ${index} (preset): 建议包含 filters 配置`);
          penalty += 2;
        }
        if (!region.pagination) {
          warnings.push(`Region ${index} (preset): 建议包含 pagination 配置`);
          penalty += 2;
        }
        break;

      case 'action':
        if (!region.actions || !Array.isArray(region.actions)) {
          errors.push(`Region ${index} (action): 缺少 actions 数组`);
          penalty += 10;
        } else if (region.actions.length === 0) {
          warnings.push(`Region ${index} (action): actions 数组为空`);
          penalty += 3;
        }
        break;

      default:
        warnings.push(`Region ${index}: 未知的 region 类型 '${region.type}'`);
        penalty += 3;
    }

    return { errors, warnings, penalty };
  }

  /**
   * 验证字段
   */
  private static validateField(
    field: any,
    regionIndex: number,
    fieldIndex: number,
  ): { errors: string[]; warnings: string[]; penalty: number } {
    const errors: string[] = [];
    const warnings: string[] = [];
    let penalty = 0;

    const prefix = `Region ${regionIndex}, Field ${fieldIndex}`;

    // 验证必需字段
    if (!field.code) {
      errors.push(`${prefix}: 缺少 code 字段`);
      penalty += 5;
    }

    if (!field.type) {
      errors.push(`${prefix}: 缺少 type 字段`);
      penalty += 5;
    }

    // 验证字段类型
    const validFieldTypes = [
      'input',
      'select',
      'textarea',
      'checkbox',
      'radio',
      'dateRange',
      'button',
      'upload',
    ];
    if (field.type && !validFieldTypes.includes(field.type)) {
      warnings.push(`${prefix}: 字段类型 '${field.type}' 可能不被支持`);
      penalty += 2;
    }

    // 验证props
    if (field.props) {
      const propsValidation = this.validateFieldProps(field.props, field.type, prefix);
      errors.push(...propsValidation.errors);
      warnings.push(...propsValidation.warnings);
      penalty += propsValidation.penalty;
    }

    // 验证layout
    if (field.layout) {
      if (
        typeof field.layout.span !== 'undefined' &&
        (field.layout.span < 1 || field.layout.span > 4)
      ) {
        warnings.push(`${prefix}: layout.span 值 ${field.layout.span} 超出推荐范围 (1-4)`);
        penalty += 1;
      }
    }

    // 验证validation
    if (field.validation) {
      const validationValidation = this.validateFieldValidation(field.validation, prefix);
      errors.push(...validationValidation.errors);
      warnings.push(...validationValidation.warnings);
      penalty += validationValidation.penalty;
    }

    return { errors, warnings, penalty };
  }

  /**
   * 验证字段属性
   */
  private static validateFieldProps(
    props: any,
    fieldType: string,
    prefix: string,
  ): { errors: string[]; warnings: string[]; penalty: number } {
    const errors: string[] = [];
    const warnings: string[] = [];
    let penalty = 0;

    // 验证多语言字段
    if (props.placeholder && typeof props.placeholder === 'object') {
      const requiredLangs = ['zh-CN', 'en-US'];
      requiredLangs.forEach((lang) => {
        if (!props.placeholder[lang]) {
          warnings.push(`${prefix}: placeholder 缺少 ${lang} 语言版本`);
          penalty += 1;
        }
      });
    }

    // 根据字段类型验证特定属性
    switch (fieldType) {
      case 'select':
        if (props.options && Array.isArray(props.options)) {
          props.options.forEach((option: any, optionIndex: number) => {
            if (!option.value) {
              errors.push(`${prefix}: 选项 ${optionIndex} 缺少 value 字段`);
              penalty += 2;
            }
            if (!option.label) {
              errors.push(`${prefix}: 选项 ${optionIndex} 缺少 label 字段`);
              penalty += 2;
            } else if (typeof option.label === 'object') {
              const requiredLangs = ['zh-CN', 'en-US'];
              requiredLangs.forEach((lang) => {
                if (!option.label[lang]) {
                  warnings.push(`${prefix}: 选项 ${optionIndex} label 缺少 ${lang} 语言版本`);
                  penalty += 1;
                }
              });
            }
          });
        }
        break;

      case 'input':
      case 'textarea':
        if (props.maxLength && (typeof props.maxLength !== 'number' || props.maxLength <= 0)) {
          warnings.push(`${prefix}: maxLength 应该是正数`);
          penalty += 1;
        }
        break;
    }

    return { errors, warnings, penalty };
  }

  /**
   * 验证字段验证规则
   */
  private static validateFieldValidation(
    validation: any[],
    prefix: string,
  ): { errors: string[]; warnings: string[]; penalty: number } {
    const errors: string[] = [];
    const warnings: string[] = [];
    let penalty = 0;

    if (!Array.isArray(validation)) {
      errors.push(`${prefix}: validation 必须是数组`);
      return { errors, warnings, penalty: 5 };
    }

    validation.forEach((rule, ruleIndex) => {
      if (!rule.type) {
        errors.push(`${prefix}: 验证规则 ${ruleIndex} 缺少 type 字段`);
        penalty += 2;
      }

      if (!rule.message) {
        errors.push(`${prefix}: 验证规则 ${ruleIndex} 缺少 message 字段`);
        penalty += 2;
      } else if (typeof rule.message === 'object') {
        const requiredLangs = ['zh-CN', 'en-US'];
        requiredLangs.forEach((lang) => {
          if (!rule.message[lang]) {
            warnings.push(`${prefix}: 验证规则 ${ruleIndex} message 缺少 ${lang} 语言版本`);
            penalty += 1;
          }
        });
      }
    });

    return { errors, warnings, penalty };
  }

  /**
   * 验证events字段
   */
  private static validateEvents(events: any[]): {
    errors: string[];
    warnings: string[];
    penalty: number;
  } {
    const errors: string[] = [];
    const warnings: string[] = [];
    let penalty = 0;

    if (!Array.isArray(events)) {
      errors.push('events 必须是数组');
      return { errors, warnings, penalty: 10 };
    }

    events.forEach((event, index) => {
      if (!event.on) {
        errors.push(`Event ${index}: 缺少 on 字段`);
        penalty += 3;
      }

      if (!event.do || !Array.isArray(event.do)) {
        errors.push(`Event ${index}: 缺少 do 数组`);
        penalty += 3;
      }

      // 验证事件处理器
      if (event.do && Array.isArray(event.do)) {
        event.do.forEach((action: any, actionIndex: number) => {
          if (!action.type) {
            errors.push(`Event ${index}, Action ${actionIndex}: 缺少 type 字段`);
            penalty += 2;
          }
        });
      }
    });

    return { errors, warnings, penalty };
  }

  /**
   * 生成验证报告
   */
  static generateReport(validation: ValidationResult): string {
    const lines: string[] = [];

    lines.push('=== Schema验证报告 ===');
    lines.push(`验证结果: ${validation.valid ? '✅ 通过' : '❌ 失败'}`);
    lines.push(`兼容性评分: ${validation.score}/100`);
    lines.push('');

    if (validation.errors.length > 0) {
      lines.push('🚨 错误:');
      validation.errors.forEach((error) => lines.push(`  - ${error}`));
      lines.push('');
    }

    if (validation.warnings.length > 0) {
      lines.push('⚠️ 警告:');
      validation.warnings.forEach((warning) => lines.push(`  - ${warning}`));
      lines.push('');
    }

    if (validation.valid && validation.warnings.length === 0) {
      lines.push('🎉 Schema完全兼容page_schema.sql格式！');
    }

    return lines.join('\n');
  }
}
