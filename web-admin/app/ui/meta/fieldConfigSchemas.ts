import type { ExtendedPropertySchema } from '~/shared/designer/SchemaBlockConfigPanel';
import type { LocalizedText } from '~/framework/meta/schemas/types';

const lt = (zh: string, en: string): LocalizedText => ({ 'zh-CN': zh, 'en-US': en });

export const fieldConfigSchemas: ExtendedPropertySchema<LocalizedText>[] = [
  // ===== Basic =====
  {
    key: 'required',
    label: lt('必填字段', 'Required'),
    type: 'boolean',
    group: lt('基本配置', 'Basic'),
  },
  {
    key: 'readonly',
    label: lt('只读', 'Read-only'),
    type: 'boolean',
    group: lt('基本配置', 'Basic'),
  },
  {
    key: 'visible',
    label: lt('可见', 'Visible'),
    type: 'boolean',
    group: lt('基本配置', 'Basic'),
    defaultValue: true,
  },
  {
    key: 'displayOrder',
    label: lt('显示顺序', 'Display order'),
    type: 'number',
    group: lt('基本配置', 'Basic'),
    description: lt('数字越小越靠前', 'Lower numbers appear first'),
    defaultValue: 0,
  },

  // ===== Default value =====
  {
    key: 'defaultValueMode',
    label: lt('默认值类型', 'Default value type'),
    type: 'select',
    group: lt('默认值', 'Default value'),
    options: [
      { label: lt('静态值', 'Static'), value: 'static' },
      { label: lt('表达式', 'Expression'), value: 'expression' },
    ],
    defaultValue: 'static',
  },
  {
    key: 'defaultValue',
    label: lt('默认值', 'Default value'),
    type: 'text',
    group: lt('默认值', 'Default value'),
    placeholder: lt('请输入默认值', 'Enter default value'),
    dependsOn: { field: 'defaultValueMode', value: 'static' },
  },
  {
    key: 'defaultValueExpression',
    label: lt('默认值表达式', 'Expression'),
    type: 'formula',
    group: lt('默认值', 'Default value'),
    placeholder: lt('例如: #NOW() 或 #currentUser', 'e.g. #NOW() or #currentUser'),
    dependsOn: { field: 'defaultValueMode', value: 'expression' },
  },

  // ===== Dictionary =====
  {
    key: 'dictCode',
    label: lt('关联字典', 'Linked dictionary'),
    type: 'dict-select',
    group: lt('字典关联', 'Dictionary'),
    description: lt(
      '关联字典后,字段将使用字典中的选项作为可选值',
      'Linked dictionary supplies the field option list',
    ),
    placeholder: lt('不关联字典', 'No dictionary'),
  },

  // ===== Validation rules =====
  {
    key: 'validationRules',
    label: lt('验证规则', 'Validation rules'),
    type: 'array',
    group: lt('验证规则', 'Validation rules'),
    addButtonLabel: lt('+ 添加规则', '+ Add rule'),
    placeholder: lt('暂无验证规则', 'No rules'),
    itemLabel: (item: any, idx: number) => `${idx + 1}: ${(item?.type as string) ?? '?'}`,
    itemSchema: [
      {
        key: 'type',
        label: lt('类型', 'Type'),
        type: 'select',
        options: [
          { label: lt('必填', 'Required'), value: 'required' },
          { label: lt('正则表达式', 'Pattern'), value: 'pattern' },
          { label: lt('最小长度', 'Min length'), value: 'minLength' },
          { label: lt('最大长度', 'Max length'), value: 'maxLength' },
          { label: lt('最小值', 'Min value'), value: 'min' },
          { label: lt('最大值', 'Max value'), value: 'max' },
          { label: lt('自定义', 'Custom'), value: 'custom' },
        ],
      },
      {
        key: 'value',
        label: lt('规则值', 'Rule value'),
        type: 'text',
        placeholder: lt('请输入规则值', 'Enter rule value'),
        dependsOn: {
          field: 'type',
          anyOf: ['pattern', 'minLength', 'maxLength', 'min', 'max'],
        },
      },
      {
        key: 'message',
        label: lt('错误消息', 'Error message'),
        type: 'text',
        placeholder: lt('错误提示消息', 'Validation message'),
      },
    ],
  },
];
