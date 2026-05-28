/**
 * B2b batch2 — second batch SDK node + editor registration entry point.
 *
 * Registers 4 additional BPMN node types with the SDK's NodeRegistry, each
 * wired to its bespoke property editor via the G2 propertyEditor slot.
 *
 * Importing this module does NOT auto-register; call `registerBpmSdkBatch2`
 * with isolated or default registries.
 */

import type {
  FlowNodeDefinition,
  NodeRegistry,
} from '~/plugins/core-designer/components/flow-designer-sdk';
import { nodeRegistry as defaultNodeRegistry } from '~/plugins/core-designer/components/flow-designer-sdk';

import {
  ExclusiveGatewayNode,
  InclusiveGatewayNode,
  ReceiveTaskNode,
  UserTaskNode,
} from './nodes/BpmSdkBatch2Nodes';
import {
  ExclusiveGatewayEditor,
  InclusiveGatewayEditor,
  ReceiveTaskEditor,
  UserTaskEditor,
} from './editors/BpmSdkBatch2Editors';

/**
 * The 4 BPMN node types this batch ports. Mirrors the legacy
 * BPMNNodeType.EXCLUSIVE_GATEWAY / INCLUSIVE_GATEWAY / RECEIVE_TASK / USER_TASK
 * string values so JSON state is interchangeable.
 */
export const BPM_SDK_BATCH2_NODE_TYPES = [
  'exclusiveGateway',
  'inclusiveGateway',
  'receiveTask',
  'userTask',
] as const;
export type BpmSdkBatch2NodeType = (typeof BPM_SDK_BATCH2_NODE_TYPES)[number];

export function buildBpmSdkBatch2NodeDefinitions(): FlowNodeDefinition[] {
  return [
    {
      type: 'exclusiveGateway',
      label: { 'en-US': 'Exclusive Gateway', 'zh-CN': '排他网关' },
      icon: '×',
      category: 'bpm.gateways',
      component: ExclusiveGatewayNode,
      propertyEditor: ExclusiveGatewayEditor,
      defaultConfig: { name: '' },
      validation: { minInputs: 1, minOutputs: 2 },
    },
    {
      type: 'inclusiveGateway',
      label: { 'en-US': 'Inclusive Gateway', 'zh-CN': '包容网关' },
      icon: '○',
      category: 'bpm.gateways',
      component: InclusiveGatewayNode,
      propertyEditor: InclusiveGatewayEditor,
      defaultConfig: { name: '' },
      validation: { minInputs: 1, minOutputs: 1 },
    },
    {
      type: 'receiveTask',
      label: { 'en-US': 'Receive Task', 'zh-CN': '接收任务' },
      icon: '📨',
      category: 'bpm.tasks',
      component: ReceiveTaskNode,
      propertyEditor: ReceiveTaskEditor,
      defaultConfig: { name: '' },
      validation: { minInputs: 1, minOutputs: 1 },
    },
    {
      type: 'userTask',
      label: { 'en-US': 'User Task', 'zh-CN': '用户任务' },
      icon: '👤',
      category: 'bpm.tasks',
      component: UserTaskNode,
      propertyEditor: UserTaskEditor,
      defaultConfig: { name: '', assignee: { type: 'user', userIds: [] } },
      validation: { minInputs: 1, minOutputs: 1 },
    },
  ];
}

/**
 * Imperative side-effect: registers all batch2 nodes in the supplied registry
 * (default: SDK singleton). Tests should pass an isolated registry instance.
 */
export function registerBpmSdkBatch2(nodes: NodeRegistry = defaultNodeRegistry): void {
  nodes.registerAll(buildBpmSdkBatch2NodeDefinitions());
}
