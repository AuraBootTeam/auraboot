/**
 * Registers the 4 BPMN-shaped node types + 1 conditional edge type with the
 * SDK's registries (G1 + G2 injection points). Importing this module is the
 * only step needed to enable BPM-shaped editing inside any FlowDesigner host
 * that consumes these singletons.
 *
 * Exported as a function so tests can use isolated registries; the module
 * top-level only declares the definitions, it does not auto-register.
 */

import type {
  FlowNodeDefinition,
  FlowEdgeDefinition,
  NodeRegistry,
  EdgeRegistry,
} from '~/plugins/core-designer/components/flow-designer-sdk';
import {
  nodeRegistry as defaultNodeRegistry,
  edgeRegistry as defaultEdgeRegistry,
} from '~/plugins/core-designer/components/flow-designer-sdk';

import {
  StartEventNode,
  ExclusiveGatewayNode,
  ServiceTaskNode,
  EndEventNode,
} from './nodes/BpmNodes';
import { BpmConditionalEdge } from './edges/BpmConditionalEdge';
import {
  StartEventEditor,
  ExclusiveGatewayEditor,
  ServiceTaskEditor,
  EndEventEditor,
  BpmConditionalEdgeEditor,
} from './editors/BpmEditors';

export const BPM_SMOKE_NODE_TYPES = [
  'startEvent',
  'exclusiveGateway',
  'serviceTask',
  'endEvent',
] as const;
export type BpmSmokeNodeType = (typeof BPM_SMOKE_NODE_TYPES)[number];

export const BPM_SMOKE_EDGE_TYPE = 'bpmConditional' as const;

export function buildBpmSmokeNodeDefinitions(): FlowNodeDefinition[] {
  return [
    {
      type: 'startEvent',
      label: 'Start',
      icon: '▶',
      category: 'bpm.events',
      component: StartEventNode,
      defaultConfig: { name: '' },
      propertyEditor: StartEventEditor,
      validation: { maxInputs: 0, minOutputs: 1 },
    },
    {
      type: 'exclusiveGateway',
      label: 'Exclusive Gateway',
      icon: '×',
      category: 'bpm.gateways',
      component: ExclusiveGatewayNode,
      defaultConfig: { name: '', defaultFlow: '' },
      propertyEditor: ExclusiveGatewayEditor,
      validation: { minInputs: 1, minOutputs: 2 },
    },
    {
      type: 'serviceTask',
      label: 'Service Task',
      icon: '⚙',
      category: 'bpm.tasks',
      component: ServiceTaskNode,
      defaultConfig: { name: '', implementation: '' },
      propertyEditor: ServiceTaskEditor,
      validation: { minInputs: 1, minOutputs: 1 },
    },
    {
      type: 'endEvent',
      label: 'End',
      icon: '■',
      category: 'bpm.events',
      component: EndEventNode,
      defaultConfig: { name: '' },
      propertyEditor: EndEventEditor,
      validation: { minInputs: 1, maxOutputs: 0 },
    },
  ];
}

export function buildBpmSmokeEdgeDefinitions(): FlowEdgeDefinition[] {
  return [
    {
      type: BPM_SMOKE_EDGE_TYPE,
      label: 'Conditional Flow',
      component: BpmConditionalEdge,
      editor: BpmConditionalEdgeEditor,
    },
  ];
}

/**
 * Imperative side-effect: registers all PoC nodes + edges in the supplied
 * registries (default: SDK singletons). Tests should pass isolated instances.
 */
export function registerBpmSmoke(
  nodes: NodeRegistry = defaultNodeRegistry,
  edges: EdgeRegistry = defaultEdgeRegistry,
): void {
  nodes.registerAll(buildBpmSmokeNodeDefinitions());
  edges.registerAll(buildBpmSmokeEdgeDefinitions());
}
