/**
 * B2b batch3 — final SDK node + editor registration entry point.
 *
 * Registers the last legacy BPMN node type (callActivity) with the SDK's
 * NodeRegistry. After this batch, all 9 legacy BPMN node types are SDK-native.
 *
 * Importing this module does NOT auto-register; call `registerBpmSdkBatch3`
 * (or the convenience aggregate `registerBpmSdkAll`) explicitly.
 */

import type {
  FlowNodeDefinition,
  NodeRegistry,
} from '~/plugins/core-designer/components/flow-designer-sdk';
import { nodeRegistry as defaultNodeRegistry } from '~/plugins/core-designer/components/flow-designer-sdk';

import { CallActivityNode } from './nodes/BpmSdkBatch3Nodes';
import { CallActivityEditor } from './editors/BpmSdkBatch3Editors';
import { registerBpmSdkBatch1 } from './registerBpmSdkNodes';
import { registerBpmSdkBatch2 } from './registerBpmSdkBatch2Nodes';

/**
 * The 1 BPMN node type this batch ports. Mirrors BPMNNodeType.CALL_ACTIVITY
 * so JSON state is interchangeable with the legacy renderer.
 */
export const BPM_SDK_BATCH3_NODE_TYPES = ['callActivity'] as const;
export type BpmSdkBatch3NodeType = (typeof BPM_SDK_BATCH3_NODE_TYPES)[number];

export function buildBpmSdkBatch3NodeDefinitions(): FlowNodeDefinition[] {
  return [
    {
      type: 'callActivity',
      label: { 'en-US': 'Call Activity', 'zh-CN': '调用活动' },
      icon: '⊙',
      category: 'bpm.activities',
      component: CallActivityNode,
      propertyEditor: CallActivityEditor,
      defaultConfig: { name: '', calledProcessKey: '', calledProcessVersion: 'latest' },
      validation: { minInputs: 1, minOutputs: 1 },
    },
  ];
}

/**
 * Imperative side-effect: registers all batch3 nodes in the supplied registry
 * (default: SDK singleton). Tests should pass an isolated registry instance.
 */
export function registerBpmSdkBatch3(nodes: NodeRegistry = defaultNodeRegistry): void {
  nodes.registerAll(buildBpmSdkBatch3NodeDefinitions());
}

/**
 * Convenience: registers all 9 BPMN node types (batch1 + batch2 + batch3) in
 * a single call. This is the recommended entry point for B2c/B2d once the
 * legacy bpmn-designer renderers are retired.
 */
export function registerBpmSdkAll(nodes: NodeRegistry = defaultNodeRegistry): void {
  registerBpmSdkBatch1(nodes);
  registerBpmSdkBatch2(nodes);
  registerBpmSdkBatch3(nodes);
}
