/**
 * BPMN设计器类型定义
 */

import type { Node, Edge } from '@xyflow/react';

// BPMN节点类型枚举
export enum BPMNNodeType {
  START_EVENT = 'startEvent',
  END_EVENT = 'endEvent',
  USER_TASK = 'userTask',
  SERVICE_TASK = 'serviceTask',
  RECEIVE_TASK = 'receiveTask',
  EXCLUSIVE_GATEWAY = 'exclusiveGateway',
  PARALLEL_GATEWAY = 'parallelGateway',
  INCLUSIVE_GATEWAY = 'inclusiveGateway',
  CALL_ACTIVITY = 'callActivity',
}

// 人员分配类型
export enum AssigneeType {
  USER = 'user', // 指定用户
  ROLE = 'role', // 指定角色
  DEPT = 'dept', // 指定部门
  EXPRESSION = 'expression', // 表达式
  STARTER = 'starter', // 流程发起人
}

// 人员分配配置
export interface AssigneeConfig {
  type: AssigneeType;
  userIds?: string[]; // 用户ID列表
  roleIds?: string[]; // 角色ID列表
  deptIds?: string[]; // 部门ID列表
  expression?: string; // 表达式
  multi?: boolean; // 是否允许多人
  assigneeMode?: 'single' | 'multi' | 'sequential'; // 单人/会签/依次审批
}

// 任务基础配置
export interface TaskBaseConfig {
  name: string;
  description?: string;
  formKey?: string; // 关联的表单
  dueDate?: string; // 截止时间表达式
  priority?: number; // 优先级
}

// 用户任务配置
export interface UserTaskConfig extends TaskBaseConfig {
  assignee?: AssigneeConfig; // 人员分配
  candidateUsers?: string[]; // 候选用户
  candidateGroups?: string[]; // 候选组
  skipable?: boolean; // 是否可跳过
  multiInstance?: MultiInstanceConfig; // 多实例配置
  formBindings?: FormBindingEntry[]; // Form bindings for this node
  hooks?: NodeHookEntry[]; // Pre/post execution hooks
}

// 服务任务配置
export interface ServiceTaskConfig extends TaskBaseConfig {
  serviceType?: 'http' | 'java' | 'script'; // 服务类型
  serviceUrl?: string; // HTTP服务地址
  className?: string; // Java类名
  scriptContent?: string; // 脚本内容
  scriptType?: 'javascript' | 'groovy'; // 脚本类型
  async?: boolean; // 是否异步执行
  hooks?: NodeHookEntry[]; // Pre/post execution hooks
}

// 接收任务配置
export interface ReceiveTaskConfig extends TaskBaseConfig {
  messageRef?: string; // 消息引用
  messageType?: string; // 消息类型
}

// 排他网关配置
export interface ExclusiveGatewayConfig {
  name: string;
  description?: string;
  defaultFlow?: string; // 默认流向
}

// Parallel gateway config
export interface ParallelGatewayConfig {
  name: string;
  description?: string;
  defaultFlow?: string;
}

// Inclusive gateway config
export interface InclusiveGatewayConfig {
  name: string;
  description?: string;
  defaultFlow?: string;
}

// Multi-instance configuration (for userTask/serviceTask)
export interface MultiInstanceConfig {
  enabled: boolean;
  sequential: boolean;
  collection?: string;
  elementVariable?: string;
  completionCondition?: string;
  loopCardinality?: number;
}

// Call activity config
export interface CallActivityConfig {
  name: string;
  description?: string;
  calledProcessKey: string;
  calledProcessVersion?: string;
  inputMappings?: Record<string, string>;
  outputMappings?: Record<string, string>;
}

// 开始事件配置
export interface StartEventConfig {
  name: string;
  description?: string;
  initiator?: string; // 发起人变量名
  formKey?: string; // 启动表单
}

// 结束事件配置
export interface EndEventConfig {
  name: string;
  description?: string;
  terminateAll?: boolean; // 是否终止所有流程实例
}

// BPMN节点数据
export interface BPMNNodeData extends Record<string, unknown> {
  type: BPMNNodeType;
  label: string;
  config?:
    | UserTaskConfig
    | ServiceTaskConfig
    | ReceiveTaskConfig
    | ExclusiveGatewayConfig
    | ParallelGatewayConfig
    | InclusiveGatewayConfig
    | CallActivityConfig
    | StartEventConfig
    | EndEventConfig;
}

// BPMN节点
export type BPMNNode = Node<BPMNNodeData>;

// Form binding entry for node-form associations
export interface FormBindingEntry {
  formRef: string; // pageKey from Page Designer
  formType?: 'page' | 'custom' | 'external'; // default: 'page'
  version?: string;
  variableBindings?: Record<string, string>; // formField → processVariable
  fieldPermissions?: Record<string, 'editable' | 'readonly' | 'hidden'>;
  saveStrategy?: 'business_only' | 'dual_write' | 'variable_only'; // default: business_only
  versionStrategy?: 'latest' | 'fixed'; // default: latest
  fixedVersion?: number;
  permissionMode?: 'merge' | 'override'; // default: merge
  builtinVariables?: {
    decision: string; // default: 'decision'
    comment: string;  // default: 'comment'
  };
}

// Node hook entry for pre/post execution hooks
export interface NodeHookEntry {
  hookType: 'pre_execute' | 'post_execute' | 'pre_complete' | 'post_complete';
  executionOrder?: number;
  hookConfig: Record<string, unknown>;
  failStrategy?: 'block' | 'ignore' | 'retry';
  async?: boolean;
  enabled?: boolean;
}

// 条件表达式类型
export interface ConditionExpression {
  type: 'expression' | 'script';
  content: string;
  language?: 'javascript' | 'groovy' | 'juel';
  ruleCode?: string; // Reference to BPM rule engine rule code
}

// BPMN连线数据
export interface BPMNEdgeData extends Record<string, unknown> {
  label?: string;
  condition?: ConditionExpression; // 条件表达式
  isDefault?: boolean; // 是否为默认流向
}

// BPMN连线
export type BPMNEdge = Edge<BPMNEdgeData>;

// BPMN流程定义
export interface BPMNProcessDefinition {
  id?: string;
  name: string;
  key: string; // 流程标识
  description?: string;
  category?: string; // 流程分类
  version?: number; // 版本号（数字）
  versionName?: string; // 语义化版本号（如 1.0.0）
  nodes: BPMNNode[];
  edges: BPMNEdge[];
  variables?: Record<string, any>; // 流程变量
  createdAt?: string;
  updatedAt?: string;
  createdBy?: string;
  status?: 'draft' | 'published' | 'suspended'; // 状态
}

// Node monitor status (used in monitor mode)
export type NodeMonitorStatus = 'active' | 'completed' | 'idle';

// 组件库项配置
export interface BPMNPaletteItem {
  type: BPMNNodeType;
  label: string;
  icon: string;
  category: 'event' | 'task' | 'gateway' | 'subprocess';
  description?: string;
}

// Validation result
export interface ValidationResult {
  valid: boolean;
  errors: Array<{
    nodeId?: string;
    edgeId?: string;
    /** i18n key for the message (e.g. "bpmn.validate.start_event_required") */
    message: string;
    /** Optional parameters for i18n interpolation (e.g. { label: "Start" }) */
    messageParams?: Record<string, string>;
    type: 'error' | 'warning';
  }>;
}
