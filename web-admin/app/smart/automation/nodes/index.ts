// web-admin/app/smart/automation/nodes/index.ts
import { triggerNodes } from './triggers';
import { actionNodes } from './actions';
import { controlNodes } from './controls';
import type { FlowNodeDefinition } from '~/flow-designer-sdk';

/**
 * All Automation node definitions
 */
export const automationNodes: FlowNodeDefinition[] = [
  ...triggerNodes,
  ...actionNodes,
  ...controlNodes,
];

/**
 * Category order for the palette
 */
export const automationCategoryOrder = ['trigger', 'action', 'control'];

// Re-export individual node arrays
export { triggerNodes } from './triggers';
export { actionNodes } from './actions';
export { controlNodes } from './controls';
