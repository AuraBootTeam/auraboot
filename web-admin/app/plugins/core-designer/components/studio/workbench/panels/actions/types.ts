/**
 * Action Panel Types
 *
 * Defines the action configuration model that bridges the designer's
 * UI actions with the backend Command Pipeline (PRE → VALIDATE → EXECUTE → POST).
 *
 * @since 3.3.0
 */

/**
 * Complete action configuration for a button/trigger in the designer.
 */
export interface ActionConfig {
  id: string;
  commandCode: string; // Associated CommandDefinition.code
  displayName: string;
  phases: ActionPhases;
  condition?: string; // Visibility expression (SpEL)
  variant?: 'primary' | 'secondary' | 'danger' | 'default';
  confirm?: ConfirmConfig;
}

export interface ActionPhases {
  pre: ActionPhase[]; // Client-side: validation, confirm dialog
  validate: ActionPhase[]; // Server-side: call validate API
  execute: ActionPhase[]; // Server-side: call command execute
  post: ActionPhase[]; // Client-side: refresh, navigate, notify
}

export type ActionPhaseType =
  | 'clientValidate'
  | 'apiValidate'
  | 'apiCall'
  | 'navigate'
  | 'refresh'
  | 'notify'
  | 'setState'
  | 'openModal'
  | 'custom';

export interface ActionPhase {
  id: string;
  type: ActionPhaseType;
  label?: string;
  config: Record<string, any>;
  onError?: 'stop' | 'continue' | 'retry';
  enabled?: boolean;
}

export interface ConfirmConfig {
  title: string;
  message: string;
  confirmText?: string;
  cancelText?: string;
}

/**
 * Backend DTOs mapped to frontend types.
 */
export interface CommandDefinitionDTO {
  pid: string;
  code: string;
  displayName: string;
  description?: string;
  modelCode: string;
  inputSchema?: string;
  executionConfig?: string;
  version?: number;
  status?: string;
  bindingRules?: BindingRuleDTO[];
}

export interface BindingRuleDTO {
  pid: string;
  ruleType: string;
  expression?: string;
  targetModel?: string;
  targetField?: string;
  sourceField?: string;
  handlerClass?: string;
  eventType?: string;
  config?: string;
  sequence?: number;
  enabled?: boolean;
}

/**
 * Phase type metadata for rendering.
 */
export const PHASE_TYPE_INFO: Record<
  ActionPhaseType,
  { label: string; icon: string; category: 'pre' | 'validate' | 'execute' | 'post' }
> = {
  clientValidate: { label: '客户端校验', icon: '✓', category: 'pre' },
  apiValidate: { label: 'API 校验', icon: '🔍', category: 'validate' },
  apiCall: { label: '执行命令', icon: '⚡', category: 'execute' },
  navigate: { label: '页面跳转', icon: '→', category: 'post' },
  refresh: { label: '刷新数据', icon: '↻', category: 'post' },
  notify: { label: '消息通知', icon: '🔔', category: 'post' },
  setState: { label: '设置状态', icon: '📝', category: 'post' },
  openModal: { label: '打开弹窗', icon: '◻', category: 'post' },
  custom: { label: '自定义', icon: '⚙', category: 'post' },
};

/**
 * Phase category labels.
 */
export const PHASE_CATEGORIES = {
  pre: { label: '前置阶段', description: '客户端校验、确认弹窗' },
  validate: { label: '校验阶段', description: '调用后端校验接口' },
  execute: { label: '执行阶段', description: '执行 Command' },
  post: { label: '后置阶段', description: '刷新、跳转、通知' },
} as const;

/**
 * Create a default ActionConfig for a command.
 */
export function createDefaultActionConfig(command: CommandDefinitionDTO): ActionConfig {
  const id = `action-${Date.now().toString(36)}`;
  return {
    id,
    commandCode: command.code,
    displayName: command.displayName || command.code,
    phases: {
      pre: [],
      validate: [],
      execute: [
        {
          id: `phase-${Date.now().toString(36)}`,
          type: 'apiCall',
          config: {
            endpoint: `/api/meta/commands/execute/${command.code}`,
            method: 'post',
          },
          onError: 'stop',
        },
      ],
      post: [
        {
          id: `phase-${Date.now().toString(36)}-notify`,
          type: 'notify',
          config: { message: '操作成功', type: 'success' },
        },
      ],
    },
    variant: 'primary',
  };
}

/**
 * Create a new ActionPhase with defaults.
 */
export function createActionPhase(type: ActionPhaseType): ActionPhase {
  return {
    id: `phase-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 5)}`,
    type,
    config: getDefaultPhaseConfig(type),
    onError: type === 'apiCall' || type === 'apiValidate' ? 'stop' : 'continue',
    enabled: true,
  };
}

function getDefaultPhaseConfig(type: ActionPhaseType): Record<string, any> {
  switch (type) {
    case 'clientValidate':
      return { expression: '', message: '校验不通过' };
    case 'apiValidate':
      return { endpoint: '', method: 'post' };
    case 'apiCall':
      return { endpoint: '', method: 'post' };
    case 'navigate':
      return { path: '', replace: false };
    case 'refresh':
      return { target: 'list' };
    case 'notify':
      return { message: '', type: 'success' };
    case 'setState':
      return { key: '', value: '' };
    case 'openModal':
      return { modalId: '' };
    case 'custom':
      return { handler: '' };
  }
}
