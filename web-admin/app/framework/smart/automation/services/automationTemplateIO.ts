import type { FlowData } from '~/plugins/core-designer/components/flow-designer-sdk';

export const AUTOMATION_TEMPLATE_MAX_BYTES = 2 * 1024 * 1024;
export const AUTOMATION_TEMPLATE_MAX_NODES = 500;
export const AUTOMATION_TEMPLATE_MAX_EDGES = 1000;

export interface AutomationTemplateDocument {
  name: string;
  description?: string;
  flowConfig: FlowData;
}

export class AutomationTemplateError extends Error {
  constructor(public readonly reason: 'size' | 'version' | 'invalid') {
    super(`Invalid automation template: ${reason}`);
  }
}

function invalid(): never { throw new AutomationTemplateError('invalid'); }
function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
function safeTree(value: unknown, depth = 0): void {
  if (depth > 40) invalid();
  if (Array.isArray(value)) value.forEach(child => safeTree(child, depth + 1));
  else if (object(value)) for (const [key, child] of Object.entries(value)) {
    if (['__proto__', 'prototype', 'constructor'].includes(key)) invalid();
    safeTree(child, depth + 1);
  }
}

/** Accept historical unversioned DTO exports, but never import identity or enablement. */
export function parseAutomationTemplate(text: string): AutomationTemplateDocument {
  if (new TextEncoder().encode(text).byteLength > AUTOMATION_TEMPLATE_MAX_BYTES)
    throw new AutomationTemplateError('size');
  let value: unknown;
  try { value = JSON.parse(text); } catch { return invalid(); }
  safeTree(value);
  if (!object(value)) invalid();
  if (value.version !== undefined && value.version !== 1)
    throw new AutomationTemplateError('version');
  if (typeof value.name !== 'string' || !value.name.trim() || value.name.length > 200) invalid();
  if (value.description !== undefined && (typeof value.description !== 'string' || value.description.length > 5000)) invalid();
  const flow = value.flowConfig ?? value.flowData;
  if (!object(flow) || !Array.isArray(flow.nodes) || !Array.isArray(flow.edges)
      || flow.nodes.length > AUTOMATION_TEMPLATE_MAX_NODES || flow.edges.length > AUTOMATION_TEMPLATE_MAX_EDGES) invalid();
  const ids = new Set<string>();
  for (const node of flow.nodes) {
    if (!object(node) || typeof node.id !== 'string' || !node.id.trim() || ids.has(node.id)
        || typeof node.type !== 'string' || !node.type.trim()
        || !object(node.position) || !Number.isFinite(node.position.x) || !Number.isFinite(node.position.y)
        || !object(node.data) || !object(node.data.config) || typeof node.data.label !== 'string') invalid();
    ids.add(node.id);
  }
  const edges = new Set<string>();
  for (const edge of flow.edges) {
    if (!object(edge) || typeof edge.id !== 'string' || !edge.id.trim() || edges.has(edge.id)
        || typeof edge.source !== 'string' || !ids.has(edge.source)
        || typeof edge.target !== 'string' || !ids.has(edge.target)) invalid();
    edges.add(edge.id);
  }
  return {
    name: value.name.trim(),
    ...(value.description === undefined ? {} : {description: value.description as string}),
    flowConfig: {nodes: flow.nodes, edges: flow.edges} as FlowData,
  };
}

