/**
 * BPMN设计器常量定义
 */

import { BPMNNodeType, type BPMNPaletteItem } from '~/bpmn-designer/types';

// BPMN节点样式配置
export const BPMN_NODE_STYLES = {
  [BPMNNodeType.START_EVENT]: {
    width: 36,
    height: 36,
    borderRadius: '50%',
    borderWidth: 2,
    borderColor: '#22c55e',
    backgroundColor: '#f0fdf4',
  },
  [BPMNNodeType.END_EVENT]: {
    width: 36,
    height: 36,
    borderRadius: '50%',
    borderWidth: 3,
    borderColor: '#ef4444',
    backgroundColor: '#fef2f2',
  },
  [BPMNNodeType.USER_TASK]: {
    width: 120,
    height: 80,
    borderRadius: 8,
    borderWidth: 2,
    borderColor: '#3b82f6',
    backgroundColor: '#eff6ff',
  },
  [BPMNNodeType.SERVICE_TASK]: {
    width: 120,
    height: 80,
    borderRadius: 8,
    borderWidth: 2,
    borderColor: '#8b5cf6',
    backgroundColor: '#f5f3ff',
  },
  [BPMNNodeType.RECEIVE_TASK]: {
    width: 120,
    height: 80,
    borderRadius: 8,
    borderWidth: 2,
    borderColor: '#06b6d4',
    backgroundColor: '#ecfeff',
  },
  [BPMNNodeType.EXCLUSIVE_GATEWAY]: {
    width: 50,
    height: 50,
    borderRadius: 0,
    borderWidth: 2,
    borderColor: '#f59e0b',
    backgroundColor: '#fffbeb',
  },
  [BPMNNodeType.PARALLEL_GATEWAY]: {
    width: 50,
    height: 50,
    borderRadius: 0,
    borderWidth: 2,
    borderColor: '#3b82f6',
    backgroundColor: '#eff6ff',
  },
  [BPMNNodeType.INCLUSIVE_GATEWAY]: {
    width: 50,
    height: 50,
    borderRadius: 0,
    borderWidth: 2,
    borderColor: '#8b5cf6',
    backgroundColor: '#f5f3ff',
  },
  [BPMNNodeType.CALL_ACTIVITY]: {
    width: 120,
    height: 80,
    borderRadius: 8,
    borderWidth: 3,
    borderColor: '#059669',
    backgroundColor: '#ecfdf5',
  },
};

// 组件库配置
export const BPMN_PALETTE_ITEMS: BPMNPaletteItem[] = [
  {
    type: BPMNNodeType.START_EVENT,
    label: '开始事件', // i18n: bpmn.palette.startEvent
    icon: '▶',
    category: 'event',
    description: '流程的开始节点', // i18n: bpmn.palette.startEventDesc
  },
  {
    type: BPMNNodeType.END_EVENT,
    label: '结束事件', // i18n: bpmn.palette.endEvent
    icon: '⬛',
    category: 'event',
    description: '流程的结束节点', // i18n: bpmn.palette.endEventDesc
  },
  {
    type: BPMNNodeType.USER_TASK,
    label: '用户任务', // i18n: bpmn.palette.userTask
    icon: '👤',
    category: 'task',
    description: '需要人工处理的任务', // i18n: bpmn.palette.userTaskDesc
  },
  {
    type: BPMNNodeType.SERVICE_TASK,
    label: '服务任务', // i18n: bpmn.palette.serviceTask
    icon: '⚙',
    category: 'task',
    description: '自动执行的服务任务', // i18n: bpmn.palette.serviceTaskDesc
  },
  {
    type: BPMNNodeType.RECEIVE_TASK,
    label: '接收任务', // i18n: bpmn.palette.receiveTask
    icon: '📨',
    category: 'task',
    description: '等待接收消息的任务', // i18n: bpmn.palette.receiveTaskDesc
  },
  {
    type: BPMNNodeType.EXCLUSIVE_GATEWAY,
    label: '排他网关', // i18n: bpmn.palette.exclusiveGateway
    icon: '◆',
    category: 'gateway',
    description: '条件分支，只能选择一条路径', // i18n: bpmn.palette.exclusiveGatewayDesc
  },
  {
    type: BPMNNodeType.PARALLEL_GATEWAY,
    label: '并行网关', // i18n: bpmn.palette.parallelGateway
    icon: '＋',
    category: 'gateway',
    description: '并行分支，所有路径同时执行', // i18n: bpmn.palette.parallelGatewayDesc
  },
  {
    type: BPMNNodeType.INCLUSIVE_GATEWAY,
    label: '包容网关', // i18n: bpmn.palette.inclusiveGateway
    icon: '○',
    category: 'gateway',
    description: '条件分支，满足条件的路径都执行', // i18n: bpmn.palette.inclusiveGatewayDesc
  },
  {
    type: BPMNNodeType.CALL_ACTIVITY,
    label: '子流程', // i18n: bpmn.palette.callActivity
    icon: '⧉',
    category: 'task' as const,
    description: '调用另一个流程定义', // i18n: bpmn.palette.callActivityDesc
  },
];

// 默认节点配置
export const DEFAULT_NODE_CONFIGS = {
  [BPMNNodeType.START_EVENT]: {
    name: '开始',
    description: '',
    initiator: 'initiator',
  },
  [BPMNNodeType.END_EVENT]: {
    name: '结束',
    description: '',
    terminateAll: false,
  },
  [BPMNNodeType.USER_TASK]: {
    name: '用户任务',
    description: '',
    assignee: {
      type: 'user' as const,
      multi: false,
      assigneeMode: 'single' as const,
    },
    priority: 50,
    skipable: false,
  },
  [BPMNNodeType.SERVICE_TASK]: {
    name: '服务任务',
    description: '',
    serviceType: 'http' as const,
    async: false,
    priority: 50,
  },
  [BPMNNodeType.RECEIVE_TASK]: {
    name: '接收任务',
    description: '',
    priority: 50,
  },
  [BPMNNodeType.EXCLUSIVE_GATEWAY]: {
    name: '排他网关',
    description: '',
  },
  [BPMNNodeType.PARALLEL_GATEWAY]: {
    name: '并行网关',
    description: '',
  },
  [BPMNNodeType.INCLUSIVE_GATEWAY]: {
    name: '包容网关',
    description: '',
  },
  [BPMNNodeType.CALL_ACTIVITY]: {
    name: '子流程',
    description: '',
    calledProcessKey: '',
  },
};

// 连线样式
export const EDGE_STYLES = {
  default: {
    stroke: '#94a3b8',
    strokeWidth: 2,
  },
  selected: {
    stroke: '#3b82f6',
    strokeWidth: 3,
  },
  conditional: {
    stroke: '#f59e0b',
    strokeWidth: 2,
    strokeDasharray: '5,5',
  },
};

// 网格配置
export const GRID_CONFIG = {
  size: 20,
  color: '#e2e8f0',
  style: 'dots' as const,
};

// 画布配置
export const CANVAS_CONFIG = {
  minZoom: 0.5,
  maxZoom: 2,
  defaultZoom: 1,
  snapToGrid: true,
  snapGrid: [20, 20] as [number, number],
};
