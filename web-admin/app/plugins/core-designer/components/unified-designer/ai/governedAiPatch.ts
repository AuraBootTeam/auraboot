import type {
  AuthoringAiPatchProposalItemRequest,
  CapabilityRegistry,
  PatchOperation,
} from '~/framework/meta/authoring/types';
import type { DslBlockV3, PageSchemaV3 } from '../types';

const MAX_ITEMS = 50;
const PATCH_OPERATIONS = new Set<PatchOperation>(['ADD', 'REPLACE', 'REMOVE']);
const ITEM_KEYS = new Set(['blockId', 'propertyPath', 'operation', 'value']);

interface GovernedTarget {
  blockId: string;
  blockType: string;
  propertyPath: string;
  currentValue?: unknown;
  allowedOperations: PatchOperation[];
  manifestChecksum: string;
}

export interface GovernedAiPatchContext {
  document: PageSchemaV3;
  capabilities: CapabilityRegistry;
}

/**
 * Build a minimized, metadata-only prompt. It exposes stable block IDs and declared property
 * capabilities, but never runtime records, authoring credentials, or governance internals.
 */
export function buildGovernedAiPatchPrompt({
  document,
  capabilities,
}: GovernedAiPatchContext): string {
  const targets = collectGovernedTargets(document, capabilities);
  return `You are a typed patch proposal generator for AuraBoot Studio.

You do not edit the page. You only propose property patches for an explicit human review.
Output ONLY one JSON object with this exact shape:
{"items":[{"blockId":"...","propertyPath":"/...","operation":"ADD|REPLACE|REMOVE","value":null}]}

Rules:
- Select only blockId/propertyPath pairs listed in AVAILABLE_TARGETS.
- Use only the allowed operation listed for that exact target.
- Do not add, remove, copy, move, or rename blocks.
- Do not emit page-level, model, permission, workflow, integration, or navigation changes.
- Omit value for REMOVE. Include value for ADD or REPLACE.
- Emit 1-${MAX_ITEMS} items, with no duplicate blockId/propertyPath pair.
- Do not include explanations, markdown, comments, checksums, or extra keys.
- The server will independently revalidate policy, permissions, risk, revision, impact, and approval.

PAGE_KIND: ${JSON.stringify(document.kind)}
AVAILABLE_TARGETS:
${JSON.stringify(targets)}`;
}

/** Parse and fail closed against the current document and capability registry. */
export function parseGovernedAiPatchResponse(
  response: string,
  context: GovernedAiPatchContext,
): AuthoringAiPatchProposalItemRequest[] {
  const parsed = parseSingleJsonObject(response);
  if (!Array.isArray(parsed.items) || parsed.items.length < 1 || parsed.items.length > MAX_ITEMS) {
    throw new Error(`items must contain 1-${MAX_ITEMS} typed patches`);
  }
  if (Object.keys(parsed).some((key) => key !== 'items')) {
    throw new Error('response contains undeclared top-level keys');
  }

  const targets = new Map(
    collectGovernedTargets(context.document, context.capabilities).map((target) => [
      targetKey(target.blockId, target.propertyPath),
      target,
    ]),
  );
  const seen = new Set<string>();

  return parsed.items.map((candidate: unknown) => {
    if (!isRecord(candidate)) throw new Error('each item must be an object');
    if (Object.keys(candidate).some((key) => !ITEM_KEYS.has(key))) {
      throw new Error('item contains undeclared keys');
    }
    const blockId = requireString(candidate.blockId, 'blockId');
    const propertyPath = requireString(candidate.propertyPath, 'propertyPath');
    const operation = requireOperation(candidate.operation);
    const key = targetKey(blockId, propertyPath);
    const target = targets.get(key);
    if (!target) throw new Error(`unknown typed target ${blockId}${propertyPath}`);
    if (!target.allowedOperations.includes(operation)) {
      throw new Error(`operation ${operation} is not allowed for ${blockId}${propertyPath}`);
    }
    if (seen.has(key)) throw new Error(`duplicate typed target ${blockId}${propertyPath}`);
    seen.add(key);
    if (operation !== 'REMOVE' && !Object.prototype.hasOwnProperty.call(candidate, 'value')) {
      throw new Error(`value is required for ${operation}`);
    }
    return {
      blockId,
      propertyPath,
      operation,
      ...(operation === 'REMOVE' ? {} : { value: candidate.value }),
      manifestChecksum: target.manifestChecksum,
    };
  });
}

export function collectGovernedTargets(
  document: PageSchemaV3,
  capabilities: CapabilityRegistry,
): GovernedTarget[] {
  const manifests = new Map(
    capabilities.manifests.map((manifest) => [manifest.blockType, manifest]),
  );
  const targets: GovernedTarget[] = [];
  walkBlocks(document.blocks, (block) => {
    const manifest = manifests.get(block.blockType);
    if (!manifest) return;
    Object.values(manifest.properties)
      .filter((property) => !property.propertyPath.startsWith('/$structure/'))
      .forEach((property) => {
        const allowedOperations = property.allowedOperations.filter(
          (operation): operation is PatchOperation =>
            PATCH_OPERATIONS.has(operation as PatchOperation),
        );
        if (allowedOperations.length === 0) return;
        targets.push({
          blockId: block.id,
          blockType: block.blockType,
          propertyPath: property.propertyPath,
          currentValue: readPointer(block, property.propertyPath),
          allowedOperations,
          manifestChecksum: manifest.checksum,
        });
      });
  });
  return targets.sort((left, right) =>
    `${left.blockId}:${left.propertyPath}`.localeCompare(`${right.blockId}:${right.propertyPath}`),
  );
}

function parseSingleJsonObject(response: string): Record<string, unknown> {
  const text = (response ?? '').trim();
  if (!text.startsWith('{') || !text.endsWith('}')) {
    throw new Error('response must be one JSON object without prose or markdown');
  }
  const parsed: unknown = JSON.parse(text);
  if (!isRecord(parsed)) throw new Error('response must be a JSON object');
  return parsed;
}

function requireOperation(value: unknown): PatchOperation {
  if (typeof value !== 'string' || !PATCH_OPERATIONS.has(value as PatchOperation)) {
    throw new Error('operation must be ADD, REPLACE, or REMOVE');
  }
  return value as PatchOperation;
}

function requireString(value: unknown, name: string): string {
  if (typeof value !== 'string' || !value.trim()) throw new Error(`${name} must be a string`);
  return value;
}

function walkBlocks(blocks: DslBlockV3[], visit: (block: DslBlockV3) => void): void {
  blocks.forEach((block) => {
    visit(block);
    if (block.blocks?.length) walkBlocks(block.blocks, visit);
  });
}

function readPointer(source: DslBlockV3, pointer: string): unknown {
  if (pointer === '' || pointer === '/') return source;
  return pointer
    .split('/')
    .slice(1)
    .map((segment) => segment.replace(/~1/g, '/').replace(/~0/g, '~'))
    .reduce<unknown>((value, segment) => {
      if (!isRecord(value) && !Array.isArray(value)) return undefined;
      return (value as Record<string, unknown>)[segment];
    }, source);
}

function targetKey(blockId: string, propertyPath: string): string {
  return `${blockId}\n${propertyPath}`;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
