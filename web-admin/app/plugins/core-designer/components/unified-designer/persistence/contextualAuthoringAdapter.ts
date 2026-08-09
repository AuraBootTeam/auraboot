import type {
  CapabilityRegistry,
  PatchOperation,
} from '~/framework/meta/authoring/types';
import { migratePageSchemaV2ToV3 } from '../migration/migrateToV3';
import type {
  DslBlockV3,
  LegacyPageSchemaV2,
  PageSchemaV3,
  PageSchemaV3Kind,
} from '../types';

export interface StudioAuthoringPatch {
  blockId: string;
  propertyPath: string;
  operation: PatchOperation;
  value: unknown;
  manifestChecksum: string;
}

export interface StudioAuthoringPatchPlan {
  patches: StudioAuthoringPatch[];
  unsupported: string[];
}

interface IndexedBlock {
  block: DslBlockV3;
  parentId: string | null;
  siblingIds: string[];
}

/** Materialize the isolated server snapshot as a V3 designer document. */
export function authoringSnapshotToPageSchemaV3(
  snapshot: Record<string, unknown>,
): PageSchemaV3 {
  const id = stringValue(snapshot.pageKey) || stringValue(snapshot.pid) || 'authoring-page';
  const blocks = Array.isArray(snapshot.blocks) ? snapshot.blocks : [];
  if (Number(snapshot.schemaVersion) === 3) {
    return {
      schemaVersion: 3,
      kind: normalizeKind(snapshot.kind),
      id,
      pageKey: stringValue(snapshot.pageKey) || undefined,
      modelCode: stringValue(snapshot.modelCode) || undefined,
      title: localizedValue(snapshot.title),
      layout: objectValue(snapshot.layout),
      blocks: blocks as DslBlockV3[],
    };
  }

  return migratePageSchemaV2ToV3({
    schemaVersion: numberValue(snapshot.schemaVersion),
    kind: stringValue(snapshot.kind) || 'composite',
    id,
    pageKey: stringValue(snapshot.pageKey) || undefined,
    modelCode: stringValue(snapshot.modelCode) || undefined,
    title: localizedValue(snapshot.title),
    layout: objectValue(snapshot.layout),
    blocks: blocks as LegacyPageSchemaV2['blocks'],
  });
}

/**
 * Translate a Studio document edit into server-declared property patches.
 * Structural, identity and undeclared changes fail closed and never fall back
 * to the legacy PageSchema save API.
 */
export function planStudioAuthoringPatches(
  baseline: PageSchemaV3,
  candidate: PageSchemaV3,
  capabilities: CapabilityRegistry,
): StudioAuthoringPatchPlan {
  const unsupported: string[] = [];
  if (
    baseline.id !== candidate.id ||
    baseline.pageKey !== candidate.pageKey ||
    baseline.kind !== candidate.kind ||
    baseline.modelCode !== candidate.modelCode
  ) {
    unsupported.push('页面标识、路由、类型或模型变更必须使用专用结构变更流程');
  }
  if (!deepEqual(baseline.title, candidate.title) || !deepEqual(baseline.layout, candidate.layout)) {
    unsupported.push('页面级标题或布局尚未声明为 Studio typed patch');
  }

  const baselineIndex = indexBlocks(baseline.blocks);
  const candidateIndex = indexBlocks(candidate.blocks);
  if (!sameKeys(baselineIndex, candidateIndex)) {
    unsupported.push('新增或删除区块尚未声明为 Studio typed patch');
  }

  const manifests = new Map(
    capabilities.manifests.map((manifest) => [manifest.blockType, manifest]),
  );
  const patches: StudioAuthoringPatch[] = [];

  baselineIndex.forEach((baseEntry, blockId) => {
    const nextEntry = candidateIndex.get(blockId);
    if (!nextEntry) return;
    if (
      baseEntry.block.blockType !== nextEntry.block.blockType ||
      baseEntry.parentId !== nextEntry.parentId ||
      !deepEqual(baseEntry.siblingIds, nextEntry.siblingIds)
    ) {
      unsupported.push(`区块 ${blockId} 的类型、父级或顺序变更尚未声明为 typed patch`);
    }

    const manifest = manifests.get(baseEntry.block.blockType);
    if (!manifest) {
      if (!deepEqual(withoutChildren(baseEntry.block), withoutChildren(nextEntry.block))) {
        unsupported.push(`区块 ${blockId} 没有可信能力清单`);
      }
      return;
    }

    const reconstructed = cloneJson(withoutChildren(baseEntry.block));
    Object.values(manifest.properties)
      .sort((left, right) => left.propertyPath.localeCompare(right.propertyPath))
      .forEach((property) => {
        const previous = readPointer(
          baseEntry.block as unknown as Record<string, unknown>,
          property.propertyPath,
        );
        const value = readPointer(
          nextEntry.block as unknown as Record<string, unknown>,
          property.propertyPath,
        );
        if (deepEqual(previous, value)) return;
        const operation: PatchOperation =
          value === undefined ? 'REMOVE' : previous === undefined ? 'ADD' : 'REPLACE';
        patches.push({
          blockId,
          propertyPath: property.propertyPath,
          operation,
          value,
          manifestChecksum: manifest.checksum,
        });
        applyPointer(reconstructed, property.propertyPath, value, operation === 'REMOVE');
      });

    if (!deepEqual(reconstructed, withoutChildren(nextEntry.block))) {
      unsupported.push(`区块 ${blockId} 包含能力清单未声明的属性变更`);
    }
  });

  return { patches, unsupported: [...new Set(unsupported)] };
}

export function studioEditablePropertyPaths(
  capabilities: CapabilityRegistry,
): Record<string, string[]> {
  return Object.fromEntries(
    capabilities.manifests.map((manifest) => [
      manifest.blockType,
      Object.values(manifest.properties).map((property) => property.propertyPath),
    ]),
  );
}

function indexBlocks(blocks: DslBlockV3[]): Map<string, IndexedBlock> {
  const index = new Map<string, IndexedBlock>();
  const visit = (items: DslBlockV3[], parentId: string | null) => {
    const siblingIds = items.map((item) => item.id);
    items.forEach((block) => {
      index.set(block.id, { block, parentId, siblingIds });
      visit(block.blocks ?? [], block.id);
    });
  };
  visit(blocks, null);
  return index;
}

function sameKeys(left: Map<string, unknown>, right: Map<string, unknown>): boolean {
  return left.size === right.size && [...left.keys()].every((key) => right.has(key));
}

function withoutChildren(block: DslBlockV3): Record<string, unknown> {
  const { blocks: _blocks, ...rest } = block;
  return rest;
}

function readPointer(object: Record<string, unknown>, pointer: string): unknown {
  let value: unknown = object;
  for (const segment of pointerSegments(pointer)) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
    value = (value as Record<string, unknown>)[segment];
  }
  return value;
}

function applyPointer(
  object: Record<string, unknown>,
  pointer: string,
  value: unknown,
  remove: boolean,
): void {
  const segments = pointerSegments(pointer);
  if (segments.length === 0) return;
  let parent = object;
  segments.slice(0, -1).forEach((segment) => {
    const child = parent[segment];
    if (!child || typeof child !== 'object' || Array.isArray(child)) parent[segment] = {};
    parent = parent[segment] as Record<string, unknown>;
  });
  const leaf = segments[segments.length - 1];
  if (remove) delete parent[leaf];
  else parent[leaf] = cloneJson(value);
}

function pointerSegments(pointer: string): string[] {
  if (!pointer.startsWith('/')) return [];
  return pointer
    .slice(1)
    .split('/')
    .map((segment) => segment.replace(/~1/g, '/').replace(/~0/g, '~'));
}

function normalizeKind(value: unknown): PageSchemaV3Kind {
  return value === 'list' ||
    value === 'detail' ||
    value === 'form' ||
    value === 'dashboard' ||
    value === 'composite'
    ? value
    : 'composite';
}

function stringValue(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function numberValue(value: unknown): number | undefined {
  return typeof value === 'number' ? value : undefined;
}

function localizedValue(value: unknown): PageSchemaV3['title'] {
  return typeof value === 'string' || objectValue(value) ? (value as PageSchemaV3['title']) : undefined;
}

function objectValue(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

function cloneJson<T>(value: T): T {
  if (value === undefined) return value;
  return JSON.parse(JSON.stringify(value)) as T;
}

function deepEqual(left: unknown, right: unknown): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}
