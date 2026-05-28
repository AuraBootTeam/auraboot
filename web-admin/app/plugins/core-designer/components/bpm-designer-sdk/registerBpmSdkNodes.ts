/**
 * B2b — first batch SDK node + editor registration entry point.
 *
 * Registers 4 Tier-1 BPMN node types with the SDK's NodeRegistry, each wired
 * to its bespoke property editor via the G2 propertyEditor slot. No edge
 * registration in this batch — bpmn-designer keeps shipping the live
 * ConditionalEdge until B2c migrates useBPMNStore + the edge schema.
 *
 * Importing this module does NOT auto-register; call `registerBpmSdkBatch1`
 * with isolated or default registries.
 */

import type {
  FlowNodeDefinition,
  NodeRegistry,
} from '~/plugins/core-designer/components/flow-designer-sdk';
import { nodeRegistry as defaultNodeRegistry } from '~/plugins/core-designer/components/flow-designer-sdk';

import {
  StartEventNode,
  EndEventNode,
  ParallelGatewayNode,
  ServiceTaskNode,
} from './nodes/BpmSdkNodes';
import {
  StartEventEditor,
  EndEventEditor,
  ParallelGatewayEditor,
  ServiceTaskEditor,
} from './editors/BpmSdkEditors';

/**
 * The 4 BPMN node types this batch ports. Mirrors the legacy
 * BPMNNodeType.START_EVENT / END_EVENT / PARALLEL_GATEWAY / SERVICE_TASK
 * string values so JSON state is interchangeable.
 */
export const BPM_SDK_BATCH1_NODE_TYPES = [
  'startEvent',
  'endEvent',
  'parallelGateway',
  'serviceTask',
] as const;
export type BpmSdkBatch1NodeType = (typeof BPM_SDK_BATCH1_NODE_TYPES)[number];

export function buildBpmSdkBatch1NodeDefinitions(): FlowNodeDefinition[] {
  return [
    {
      type: 'startEvent',
      label: { 'en-US': 'Start', 'zh-CN': '开始' },
      icon: '▶',
      category: 'bpm.events',
      component: StartEventNode,
      propertyEditor: StartEventEditor,
      defaultConfig: { name: '', initiator: 'initiator' },
      validation: { maxInputs: 0, minOutputs: 1 },
    },
    {
      type: 'endEvent',
      label: { 'en-US': 'End', 'zh-CN': '结束' },
      icon: '■',
      category: 'bpm.events',
      component: EndEventNode,
      propertyEditor: EndEventEditor,
      defaultConfig: { name: '' },
      validation: { minInputs: 1, maxOutputs: 0 },
    },
    {
      type: 'parallelGateway',
      label: { 'en-US': 'Parallel Gateway', 'zh-CN': '并行网关' },
      icon: '+',
      category: 'bpm.gateways',
      component: ParallelGatewayNode,
      propertyEditor: ParallelGatewayEditor,
      defaultConfig: { name: '' },
      validation: { minInputs: 1, minOutputs: 1 },
    },
    {
      type: 'serviceTask',
      label: { 'en-US': 'Service Task', 'zh-CN': '服务任务' },
      icon: '⚙',
      category: 'bpm.tasks',
      component: ServiceTaskNode,
      propertyEditor: ServiceTaskEditor,
      defaultConfig: { name: '', serviceType: 'http' },
      validation: { minInputs: 1, minOutputs: 1 },
    },
  ];
}

/**
 * Imperative side-effect: registers all batch1 nodes in the supplied registry
 * (default: SDK singleton). Tests should pass an isolated registry instance.
 */
export function registerBpmSdkBatch1(nodes: NodeRegistry = defaultNodeRegistry): void {
  nodes.registerAll(buildBpmSdkBatch1NodeDefinitions());
}
