/**
 * Linkage rule types for field-to-field cascade/visibility configuration.
 *
 * @since 3.5.0
 */

export type TriggerEvent = 'change' | 'blur' | 'focus';

export type LinkageActionType =
  | 'show'
  | 'hide'
  | 'enable'
  | 'disable'
  | 'setRequired'
  | 'setValue'
  | 'setOptions'
  | 'validate';

export interface LinkageTrigger {
  fieldCode: string;
  event: TriggerEvent;
  condition?: string; // SpEL expression
}

export type LinkageAction =
  | { type: 'show'; targets: string[] }
  | { type: 'hide'; targets: string[] }
  | { type: 'enable'; targets: string[] }
  | { type: 'disable'; targets: string[] }
  | { type: 'setRequired'; targets: string[]; required: boolean }
  | { type: 'setValue'; target: string; value: string }
  | { type: 'setOptions'; target: string; dataSource: DataSourceConfig }
  | { type: 'validate'; targets: string[]; rules: ValidationRule[] };

export interface DataSourceConfig {
  type: 'dict' | 'api' | 'parent';
  dictCode?: string;
  apiUrl?: string;
  parentFieldCode?: string;
  labelField?: string;
  valueField?: string;
}

export interface ValidationRule {
  type: 'required' | 'minLength' | 'maxLength' | 'pattern' | 'custom';
  value?: string | number;
  message: string;
}

export interface LinkageRule {
  id: string;
  name?: string;
  trigger: LinkageTrigger;
  actions: LinkageAction[];
  enabled: boolean;
}

export const TRIGGER_EVENT_INFO: Record<TriggerEvent, { label: string }> = {
  change: { label: '值变化' },
  blur: { label: '失焦' },
  focus: { label: '聚焦' },
};

export const ACTION_TYPE_INFO: Record<
  LinkageActionType,
  { label: string; description: string; needsTargets: boolean }
> = {
  show: { label: '显示', description: '显示目标字段', needsTargets: true },
  hide: { label: '隐藏', description: '隐藏目标字段', needsTargets: true },
  enable: { label: '启用', description: '启用目标字段', needsTargets: true },
  disable: { label: '禁用', description: '禁用目标字段', needsTargets: true },
  setRequired: { label: '必填', description: '设置字段必填状态', needsTargets: true },
  setValue: { label: '设值', description: '设置字段的值', needsTargets: false },
  setOptions: { label: '设选项', description: '动态加载选项', needsTargets: false },
  validate: { label: '校验', description: '触发字段校验规则', needsTargets: true },
};

export function createLinkageRule(): LinkageRule {
  return {
    id: crypto.randomUUID(),
    trigger: { fieldCode: '', event: 'change' },
    actions: [],
    enabled: true,
  };
}

export function createLinkageAction(type: LinkageActionType): LinkageAction {
  switch (type) {
    case 'show':
    case 'hide':
    case 'enable':
    case 'disable':
      return { type, targets: [] };
    case 'setRequired':
      return { type, targets: [], required: true };
    case 'setValue':
      return { type, target: '', value: '' };
    case 'setOptions':
      return { type, target: '', dataSource: { type: 'dict' } };
    case 'validate':
      return { type, targets: [], rules: [] };
  }
}
