/**
 * AI Page Generation — Prompt template and response parser
 *
 * Builds a system prompt that instructs the LLM to output valid page DSL JSON.
 * Parses the streamed response to extract blocks + layout.
 *
 * @since 4.1.0
 */

import { CURRENT_SCHEMA_VERSION } from '~/meta/migration';

/**
 * Options for building a context-aware prompt (multi-turn).
 */
export interface ContextPromptOptions {
  modelFields?: Array<{ code: string; name: string; type: string }>;
  currentBlocks?: any[];
  schemaVersion?: number;
  modelCode?: string;
}

/**
 * Build the system prompt for AI page generation.
 * If modelFields are provided, they're included as context.
 */
export function buildPageGenerationPrompt(modelFields?: Array<{ code: string; name: string; type: string }>): string {
  return buildContextPrompt({ modelFields });
}

/**
 * Build a context-aware system prompt that includes the current canvas DSL
 * and model fields. Used by the multi-turn AiPagePanel.
 */
export function buildContextPrompt(opts: ContextPromptOptions = {}): string {
  const { modelFields, currentBlocks, schemaVersion, modelCode } = opts;

  const fieldContext = modelFields?.length
    ? `\n\nAvailable model fields:\n${modelFields.map((f) => `- ${f.code} (${f.type}): ${f.name}`).join('\n')}`
    : '';

  const canvasContext = currentBlocks?.length
    ? `\n\n## Current canvas DSL (${currentBlocks.length} blocks)\n\`\`\`json\n${JSON.stringify(currentBlocks, null, 2)}\n\`\`\`\nWhen the user asks to modify or add to the page, use this as the baseline.`
    : '';

  const modelContext = modelCode
    ? `\n\nModel code: \`${modelCode}\``
    : '';

  const versionInfo = schemaVersion ?? CURRENT_SCHEMA_VERSION;

  return `You are a page layout generator for the AuraBoot low-code platform.

Given a user's description, generate a page schema as a single JSON object.
${modelContext}

## Output Format

{
  "kind": "list" | "form" | "detail" | "dashboard" | "composite",
  "schemaVersion": ${versionInfo},
  "_mergeMode": "replace" | "append",
  "blocks": [ ...BlockConfig objects... ],
  "layout": { "type": "grid", "cols": 12 }
}

- **_mergeMode**: "replace" replaces all existing blocks (default). "append" adds the new blocks after existing ones.
- When the user says "add", "insert", or "append", use "append" mode.
- When the user says "redesign", "replace", "create", or "generate", use "replace" mode.

## Available blockTypes

| blockType | Use for |
|-----------|---------|
| table | Data tables with pagination, sort, filter |
| form-section | Form fields for create/edit |
| chart | Bar, line, pie, and 17 other chart types |
| stat-card | KPI metric cards |
| toolbar | Action buttons (submit, approve, etc.) |
| filters | Search and filter controls |
| tabs | Tabbed container with nested blocks |
| sub-table | Related records (master-detail) |
| divider | Visual separator |
| rich-text | Static text/markdown content |
| detail-section | Read-only field display |
| monthly-grid | 12-month pivot table |

## BlockConfig structure

Each block in the blocks array:
{
  "blockType": "table",
  "layout": { "colSpan": 12 },  // 1-12 columns out of 12
  "config": {
    // block-specific config
  }
}

## Common config patterns

- table: { "dataSource": { "modelCode": "xxx" }, "columns": ["field1", "field2"] }
- form-section: { "fields": ["field1", "field2"], "columns": 2 }
- chart: { "chartType": "bar", "dataSource": { "modelCode": "xxx" } }
- stat-card: { "metrics": [{ "label": "Total", "field": "amount", "aggregation": "SUM" }] }
- toolbar: { "buttons": [{ "code": "submit", "label": "Submit", "action": { "type": "command", "command": "xxx" } }] }
- filters: { "fields": ["status", "created_at"] }
${fieldContext}${canvasContext}

## Rules

1. Output ONLY valid JSON. No markdown fences, no explanation, no comments.
2. Use reasonable colSpan values: stat-cards typically 4 (3 across), charts 6 (half), tables 12 (full).
3. Include a toolbar with relevant action buttons for the page kind.
4. For list pages: always include filters + toolbar + table blocks.
5. For form pages: include form-section + toolbar (submit/cancel) blocks.
6. For dashboards: include stat-cards + charts blocks.`;
}

/**
 * Merge mode for AI-generated blocks.
 * - "replace": replace all existing blocks (default)
 * - "append": append new blocks after existing ones
 */
export type MergeMode = 'replace' | 'append';

export interface ParsedPageDsl {
  kind: string;
  blocks: any[];
  layout: any;
  schemaVersion: number;
  mergeMode: MergeMode;
}

/**
 * Parse the AI response to extract page DSL.
 * Handles common issues: markdown fences, extra text, partial JSON.
 * Extracts _mergeMode for the caller to decide how to apply blocks.
 */
export function parsePageDslResponse(response: string): ParsedPageDsl {
  let jsonStr = response.trim();

  // Strip markdown code fences if present
  if (jsonStr.startsWith('```')) {
    const lines = jsonStr.split('\n');
    // Remove first line (```json) and last line (```)
    const startIdx = lines[0].startsWith('```') ? 1 : 0;
    const endIdx = lines[lines.length - 1].trim() === '```' ? lines.length - 1 : lines.length;
    jsonStr = lines.slice(startIdx, endIdx).join('\n');
  }

  // Find the outermost { ... } if there's extra text
  const firstBrace = jsonStr.indexOf('{');
  const lastBrace = jsonStr.lastIndexOf('}');
  if (firstBrace >= 0 && lastBrace > firstBrace) {
    jsonStr = jsonStr.slice(firstBrace, lastBrace + 1);
  }

  const parsed = JSON.parse(jsonStr);

  if (!parsed.kind || !Array.isArray(parsed.blocks)) {
    throw new Error('Invalid page DSL: missing "kind" or "blocks" array');
  }

  const mergeMode: MergeMode = parsed._mergeMode === 'append' ? 'append' : 'replace';

  return {
    kind: parsed.kind,
    blocks: parsed.blocks,
    layout: parsed.layout || { type: 'grid', cols: 12 },
    schemaVersion: CURRENT_SCHEMA_VERSION,
    mergeMode,
  };
}
