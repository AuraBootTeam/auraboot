// web-admin/app/flow-designer-sdk/nodes/NodeRegistry.ts
import type { FlowNodeDefinition } from './types';
import { DesignerRegistry } from '~/shared/designer';

/**
 * NodeRegistry extends the shared DesignerRegistry with FlowNodeDefinition type.
 */
export class NodeRegistry extends DesignerRegistry<FlowNodeDefinition> {}

// Singleton instance
export const nodeRegistry = new NodeRegistry();
