/**
 * Design copilot — tools-off AI block generation for the unified designer.
 *
 * Builds a system prompt that instructs an LLM to emit unified-designer V3 blocks
 * (DslBlockV3) for the current page kind, parses the response into blocks, and
 * applies them into the live document inside the workbench's undo/save lifecycle.
 *
 * The completion runs through the dedicated tools-off endpoint
 * (POST /api/agent/nl-modeling/generate-page) — NOT the AuraBot chat agent, which
 * would inject an agent system prompt + business tools and reply conversationally
 * instead of emitting design JSON. The kernel owns the V3 vocabulary; consumers
 * (e.g. the QR landing designer) only supply domain flavor via `domainGuidance`.
 *
 * @since 4.2.0
 */

import type { DslBlockV3, PageSchemaV3, PageSchemaV3Kind } from '../types';

export type DesignMergeMode = 'replace' | 'append';

export interface DesignCopilotPromptOptions {
  kind: PageSchemaV3Kind;
  /** Block types allowed for this kind (from kindPolicy), excluding the root container. */
  allowedBlockTypes: string[];
  /** The kind's single root container block type, or null for composite. */
  rootBlockType: string | null;
  /** Available model fields for context, if the page is bound to a model. */
  modelFields?: Array<{ code: string; name: string; type: string }>;
  /** Current content blocks (the root container's children) — baseline for modify/append. */
  currentBlocks?: DslBlockV3[];
  /** Consumer-supplied domain guidance (e.g. "this is a public QR scan-landing page"). */
  domainGuidance?: string;
}

const BLOCK_TYPE_HINTS: Record<string, string> = {
  'form-section': 'a titled group of fields',
  field: 'a single input bound to a model field (set "field" to the field code)',
  'sub-table': 'an editable table of related records',
  repeater: 'a repeating group of blocks',
  subform: 'an embedded sub-form',
  tabs: 'a tabbed container (children are "tab" blocks)',
  tab: 'one tab inside a tabs container',
  'action-bar': 'a row of action buttons (children are "action" blocks)',
  action: 'a single button (set props.label and props.command)',
  'detail-section': 'a titled group of read-only fields',
  table: 'a data table (children are "column" blocks)',
  column: 'one table column (set "field")',
  'filter-bar': 'search/filter controls (children are "filter-field")',
  'filter-field': 'one filter control (set "field")',
  widget: 'a dashboard widget (set props.widgetType)',
  'activity-timeline': 'a record activity/history timeline',
  'field-history': 'change history for a field',
};

/**
 * Build the tools-off system prompt for the design copilot. Describes the V3
 * output contract scoped to the current page kind + allowed block types, plus
 * any model fields, current canvas content, and consumer domain guidance.
 */
export function buildDesignCopilotPrompt(opts: DesignCopilotPromptOptions): string {
  const { kind, allowedBlockTypes, rootBlockType, modelFields, currentBlocks, domainGuidance } = opts;

  const allowedList = allowedBlockTypes
    .map((t) => `- ${t}${BLOCK_TYPE_HINTS[t] ? `: ${BLOCK_TYPE_HINTS[t]}` : ''}`)
    .join('\n');

  const fieldContext = modelFields?.length
    ? `\n\n## Available model fields\n${modelFields.map((f) => `- ${f.code} (${f.type}): ${f.name}`).join('\n')}`
    : '';

  const canvasContext = currentBlocks?.length
    ? `\n\n## Current content (${currentBlocks.length} block(s))\n\`\`\`json\n${JSON.stringify(currentBlocks, null, 2)}\n\`\`\`\nUse this as the baseline when the user asks to modify or add to the page.`
    : '\n\nThe page currently has no content blocks.';

  const rootNote = rootBlockType
    ? `These blocks are placed INSIDE the page's "${rootBlockType}" container — do NOT emit the "${rootBlockType}" wrapper itself, only its content blocks.`
    : 'These blocks are placed at the page root.';

  const domainNote = domainGuidance ? `\n\n## Domain context\n${domainGuidance}` : '';

  return `You are a layout copilot for the AuraBoot unified page designer (schemaVersion 3).

The current page kind is "${kind}". Given the user's description, generate the content blocks for this page.
${rootNote}

## Output format

Output ONLY a single JSON object (no markdown fences, no prose, no comments):

{
  "_mergeMode": "replace" | "append",
  "blocks": [ ...block objects... ]
}

- "_mergeMode": "replace" replaces all current content blocks (default). "append" adds the new blocks after the current ones.
- Use "append" when the user says add / insert / append; use "replace" when they say redesign / replace / create / generate.

## Block object

{
  "blockType": "<one of the allowed types below>",
  "title": "<optional human label>",
  "field": "<model field code — only for field/column/filter-field blocks>",
  "props": { ...block-specific props... },
  "layout": { "span": <1-12> },
  "blocks": [ ...child blocks... ]
}

Do NOT include "id" — the designer assigns stable ids.

## Allowed block types for kind "${kind}"
${allowedList || '- (none)'}

## Nesting rules
- Respect containment: e.g. "form-section" contains "field"; "tabs" contains "tab"; "table" contains "column"; "action-bar" contains "action".
- Bind input/column/filter blocks to a real model field via "field" when fields are listed below.
- Use reasonable layout.span (full width = 12, half = 6).${fieldContext}${canvasContext}${domainNote}

Output the JSON object only.`;
}

export interface ParsedDesign {
  blocks: DslBlockV3[];
  mergeMode: DesignMergeMode;
}

/**
 * Parse the copilot response into V3 blocks. Tolerates markdown fences and
 * surrounding prose. Assigns stable, unique ids to every block (the prompt asks
 * the model NOT to emit ids; any it does emit are overwritten to guarantee
 * uniqueness against the existing document).
 */
export function parseDesignCopilotResponse(
  response: string,
  opts: { idFactory?: () => string; existingIds?: Set<string> } = {},
): ParsedDesign {
  let jsonStr = (response ?? '').trim();

  if (jsonStr.startsWith('```')) {
    const lines = jsonStr.split('\n');
    const startIdx = lines[0].startsWith('```') ? 1 : 0;
    const endIdx = lines[lines.length - 1].trim() === '```' ? lines.length - 1 : lines.length;
    jsonStr = lines.slice(startIdx, endIdx).join('\n');
  }

  const firstBrace = jsonStr.indexOf('{');
  const lastBrace = jsonStr.lastIndexOf('}');
  if (firstBrace >= 0 && lastBrace > firstBrace) {
    jsonStr = jsonStr.slice(firstBrace, lastBrace + 1);
  }

  const parsed = JSON.parse(jsonStr);
  if (!Array.isArray(parsed.blocks)) {
    throw new Error('Invalid design response: missing "blocks" array');
  }

  const used = new Set<string>(opts.existingIds ?? []);
  let counter = 0;
  const idFactory =
    opts.idFactory ??
    (() => {
      // Deterministic-by-position id; uniqueness enforced against `used` below.
      counter += 1;
      return `ai-block-${counter}`;
    });
  const nextId = (): string => {
    let id = idFactory();
    while (used.has(id)) {
      counter += 1;
      id = `${id}-${counter}`;
    }
    used.add(id);
    return id;
  };

  const assignIds = (block: any): DslBlockV3 => {
    const next: DslBlockV3 = { ...block, id: nextId() };
    if (Array.isArray(block.blocks)) {
      next.blocks = block.blocks.map(assignIds);
    }
    return next;
  };

  const blocks = (parsed.blocks as any[]).map(assignIds);
  const mergeMode: DesignMergeMode = parsed._mergeMode === 'append' ? 'append' : 'replace';
  return { blocks, mergeMode };
}

/**
 * Apply parsed copilot blocks into a document. For a kind with a single root
 * container, the blocks become that container's children (replace/append per
 * mergeMode); manual content is preserved on append. For composite (no root
 * container) the blocks merge at the page root.
 *
 * Pure: returns a new document, never mutates the input.
 */
export function applyDesignBlocks(
  document: PageSchemaV3,
  parsed: ParsedDesign,
  rootBlockType: string | null,
): PageSchemaV3 {
  const { blocks, mergeMode } = parsed;

  if (!rootBlockType) {
    const nextRootBlocks =
      mergeMode === 'append' ? [...document.blocks, ...blocks] : blocks;
    return { ...document, blocks: nextRootBlocks };
  }

  const rootIndex = document.blocks.findIndex((b) => b.blockType === rootBlockType);
  if (rootIndex < 0) {
    // No root container yet — wrap the AI blocks in a fresh root container.
    const root: DslBlockV3 = { id: `${rootBlockType}-root`, blockType: rootBlockType, blocks };
    return { ...document, blocks: [root] };
  }

  const root = document.blocks[rootIndex];
  const existingChildren = root.blocks ?? [];
  const nextChildren = mergeMode === 'append' ? [...existingChildren, ...blocks] : blocks;
  const nextRoot: DslBlockV3 = { ...root, blocks: nextChildren };
  const nextBlocks = [...document.blocks];
  nextBlocks[rootIndex] = nextRoot;
  return { ...document, blocks: nextBlocks };
}
