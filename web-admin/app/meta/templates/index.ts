export { TemplateRegistry } from './TemplateRegistry';
export { ListTemplate } from './generators/ListTemplate';
export { FormTemplate } from './generators/FormTemplate';
export { DetailTemplate } from './generators/DetailTemplate';
export { mapFieldToComponent, mapFieldToValueType, buildValidationRules } from './utils';
export type {
  TemplateFieldMeta,
  TemplateModelMeta,
  TemplateOptions,
  TemplateType,
  TemplateVariant,
  TemplateGenerator,
  TemplateAction,
  TemplateClassOverrides,
  TemplateStyleSet,
  BatchGenerateRequest,
  BatchGenerateResult,
  FieldDataType,
} from './types';
export { TEMPLATE_STYLES } from './types';
