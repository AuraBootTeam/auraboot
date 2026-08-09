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

export interface StudioAuthoringMove {
  blockId: string;
  beforeBlockId: string | null;
  manifestChecksum: string;
}

export interface StudioAuthoringPatchPlan {
  patches: StudioAuthoringPatch[];
  moves: StudioAuthoringMove[];
  unsupported: string[];
}

export type StudioMergeResolution = 'MINE' | 'LATEST';

export interface StudioMergeConflict {
  id: string;
  kind: 'PROPERTY' | 'ORDER';
  blockId: string;
  blockType?: string;
  propertyPath: string;
  baseValue: unknown;
  mineValue: unknown;
  latestValue: unknown;
}

export interface StudioThreeWayMerge {
  autoMergedDocument: PageSchemaV3;
  conflicts: StudioMergeConflict[];
  autoMergedChanges: number;
  unsupported: string[];
}

export const STUDIO_REORDER_WITHIN_PARENT_PATH = '/$structure/order';

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
  const moves: StudioAuthoringMove[] = [];
  const processedOrderParents = new Set<string>();

  baselineIndex.forEach((baseEntry, blockId) => {
    const nextEntry = candidateIndex.get(blockId);
    if (!nextEntry) return;
    if (baseEntry.block.blockType !== nextEntry.block.blockType) {
      unsupported.push(`区块 ${blockId} 的类型变更尚未声明为 typed patch`);
    }
    if (baseEntry.parentId !== nextEntry.parentId) {
      unsupported.push(`区块 ${blockId} 的跨父级移动必须使用专业结构变更流程`);
    } else if (!deepEqual(baseEntry.siblingIds, nextEntry.siblingIds)) {
      const parentKey = baseEntry.parentId ?? '$page-root';
      if (!processedOrderParents.has(parentKey)) {
        processedOrderParents.add(parentKey);
        const orderPlan = planSiblingMoves(
          baseEntry.siblingIds,
          nextEntry.siblingIds,
          baselineIndex,
          manifests,
        );
        moves.push(...orderPlan.moves);
        unsupported.push(...orderPlan.unsupported);
      }
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
      .filter((property) => !isStructuralCapabilityPath(property.propertyPath))
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

  return { patches, moves, unsupported: [...new Set(unsupported)] };
}

/**
 * Build a stable-ID three-way merge without ever applying an unresolved
 * conflict. Non-overlapping declared changes are rebased onto Latest; paths
 * changed differently by Mine and Latest remain on Latest until a user makes
 * an explicit per-item decision in Studio.
 */
export function buildStudioThreeWayMerge(
  base: PageSchemaV3,
  mine: PageSchemaV3,
  latest: PageSchemaV3,
  capabilities: CapabilityRegistry,
): StudioThreeWayMerge {
  const minePlan = planStudioAuthoringPatches(base, mine, capabilities);
  const unsupported = [...minePlan.unsupported];
  const autoMergedDocument = cloneJson(latest);
  const baseIndex = indexBlocks(base.blocks);
  const mineIndex = indexBlocks(mine.blocks);
  const latestIndex = indexBlocks(latest.blocks);
  const mergedIndex = indexBlocks(autoMergedDocument.blocks);
  const conflicts: StudioMergeConflict[] = [];
  let autoMergedChanges = 0;

  if (!sameKeys(baseIndex, latestIndex)) {
    unsupported.push('Latest 包含区块新增或删除，必须先完成结构变更裁决');
  }

  minePlan.patches.forEach((patch) => {
    const baseBlock = baseIndex.get(patch.blockId)?.block;
    const mineBlock = mineIndex.get(patch.blockId)?.block;
    const latestBlock = latestIndex.get(patch.blockId)?.block;
    const mergedBlock = mergedIndex.get(patch.blockId)?.block;
    if (!baseBlock || !mineBlock || !latestBlock || !mergedBlock) {
      unsupported.push(`区块 ${patch.blockId} 无法进行稳定 ID 三方合并`);
      return;
    }
    const baseValue = readPointer(
      baseBlock as unknown as Record<string, unknown>,
      patch.propertyPath,
    );
    const mineValue = readPointer(
      mineBlock as unknown as Record<string, unknown>,
      patch.propertyPath,
    );
    const latestValue = readPointer(
      latestBlock as unknown as Record<string, unknown>,
      patch.propertyPath,
    );
    const latestChanged = !deepEqual(baseValue, latestValue);
    if (latestChanged && !deepEqual(mineValue, latestValue)) {
      conflicts.push({
        id: `PROPERTY:${patch.blockId}:${patch.propertyPath}`,
        kind: 'PROPERTY',
        blockId: patch.blockId,
        blockType: baseBlock.blockType,
        propertyPath: patch.propertyPath,
        baseValue: cloneJson(baseValue),
        mineValue: cloneJson(mineValue),
        latestValue: cloneJson(latestValue),
      });
      return;
    }
    autoMergedChanges += 1;
    if (!latestChanged) {
      applyPointer(
        mergedBlock as unknown as Record<string, unknown>,
        patch.propertyPath,
        mineValue,
        mineValue === undefined,
      );
    }
  });

  const baseOrders = siblingOrders(base);
  const mineOrders = siblingOrders(mine);
  const latestOrders = siblingOrders(latest);
  baseOrders.forEach((baseOrder, parentId) => {
    const mineOrder = mineOrders.get(parentId);
    if (!mineOrder || deepEqual(baseOrder, mineOrder)) return;
    const latestOrder = latestOrders.get(parentId);
    if (!latestOrder || !sameStringMembers(baseOrder, latestOrder)) {
      unsupported.push(`父级 ${parentId} 的 Latest 子区块集合已变化，不能自动合并顺序`);
      return;
    }
    const latestChanged = !deepEqual(baseOrder, latestOrder);
    if (latestChanged && !deepEqual(mineOrder, latestOrder)) {
      conflicts.push({
        id: `ORDER:${parentId}`,
        kind: 'ORDER',
        blockId: parentId,
        propertyPath: STUDIO_REORDER_WITHIN_PARENT_PATH,
        baseValue: [...baseOrder],
        mineValue: [...mineOrder],
        latestValue: [...latestOrder],
      });
      return;
    }
    autoMergedChanges += 1;
    if (!latestChanged) applySiblingOrder(autoMergedDocument, parentId, mineOrder);
  });

  return {
    autoMergedDocument,
    conflicts,
    autoMergedChanges,
    unsupported: [...new Set(unsupported)],
  };
}

export function resolveStudioThreeWayMerge(
  merge: StudioThreeWayMerge,
  resolutions: Record<string, StudioMergeResolution>,
): PageSchemaV3 {
  const unresolved = merge.conflicts.filter((conflict) => !resolutions[conflict.id]);
  if (unresolved.length > 0) {
    throw new Error(`仍有 ${unresolved.length} 个三方冲突未裁决`);
  }
  if (merge.unsupported.length > 0) {
    throw new Error(merge.unsupported.join('；'));
  }

  const resolved = cloneJson(merge.autoMergedDocument);
  const resolvedIndex = indexBlocks(resolved.blocks);
  merge.conflicts.forEach((conflict) => {
    if (resolutions[conflict.id] !== 'MINE') return;
    if (conflict.kind === 'ORDER') {
      applySiblingOrder(resolved, conflict.blockId, conflict.mineValue as string[]);
      return;
    }
    const block = resolvedIndex.get(conflict.blockId)?.block;
    if (!block) throw new Error(`区块 ${conflict.blockId} 已不存在，不能应用 Mine`);
    applyPointer(
      block as unknown as Record<string, unknown>,
      conflict.propertyPath,
      conflict.mineValue,
      conflict.mineValue === undefined,
    );
  });
  return resolved;
}

export function studioEditablePropertyPaths(
  capabilities: CapabilityRegistry,
): Record<string, string[]> {
  return Object.fromEntries(
    capabilities.manifests.map((manifest) => [
      manifest.blockType,
      Object.values(manifest.properties)
        .map((property) => property.propertyPath)
        .filter((propertyPath) => !isStructuralCapabilityPath(propertyPath)),
    ]),
  );
}

export function studioReorderableBlockTypes(capabilities: CapabilityRegistry): string[] {
  return capabilities.manifests
    .filter((manifest) => {
      const capability = manifest.properties[STUDIO_REORDER_WITHIN_PARENT_PATH];
      return capability?.allowedOperations.includes('MOVE');
    })
    .map((manifest) => manifest.blockType);
}

function planSiblingMoves(
  baselineIds: string[],
  candidateIds: string[],
  baselineIndex: Map<string, IndexedBlock>,
  manifests: Map<string, CapabilityRegistry['manifests'][number]>,
): { moves: StudioAuthoringMove[]; unsupported: string[] } {
  if (!deepEqual([...baselineIds].sort(), [...candidateIds].sort())) {
    return { moves: [], unsupported: ['区块顺序变更包含新增、删除或跨父级移动'] };
  }

  const current = [...baselineIds];
  const moves: StudioAuthoringMove[] = [];
  const unsupported: string[] = [];
  candidateIds.forEach((desiredId, targetIndex) => {
    if (current[targetIndex] === desiredId) return;
    const moving = baselineIndex.get(desiredId);
    const manifest = moving ? manifests.get(moving.block.blockType) : undefined;
    const capability = manifest?.properties[STUDIO_REORDER_WITHIN_PARENT_PATH];
    if (!manifest || !capability?.allowedOperations.includes('MOVE')) {
      unsupported.push(`区块 ${desiredId} 未声明同级顺序调整能力`);
      return;
    }
    const beforeBlockId = current[targetIndex] ?? null;
    moves.push({ blockId: desiredId, beforeBlockId, manifestChecksum: manifest.checksum });
    current.splice(current.indexOf(desiredId), 1);
    current.splice(targetIndex, 0, desiredId);
  });

  return unsupported.length > 0 ? { moves: [], unsupported } : { moves, unsupported: [] };
}

function isStructuralCapabilityPath(propertyPath: string): boolean {
  return propertyPath.startsWith('/$structure/');
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

function siblingOrders(document: PageSchemaV3): Map<string, string[]> {
  const orders = new Map<string, string[]>();
  const visit = (blocks: DslBlockV3[], parentId: string) => {
    orders.set(parentId, blocks.map((block) => block.id));
    blocks.forEach((block) => visit(block.blocks ?? [], block.id));
  };
  visit(document.blocks, '$page-root');
  return orders;
}

function applySiblingOrder(
  document: PageSchemaV3,
  parentId: string,
  order: string[],
): void {
  const siblings = parentId === '$page-root'
    ? document.blocks
    : indexBlocks(document.blocks).get(parentId)?.block.blocks;
  if (!siblings || !sameStringMembers(siblings.map((block) => block.id), order)) {
    throw new Error(`父级 ${parentId} 的子区块集合已变化，不能应用顺序裁决`);
  }
  const byId = new Map(siblings.map((block) => [block.id, block]));
  const reordered = order.map((id) => byId.get(id)!);
  siblings.splice(0, siblings.length, ...reordered);
}

function sameStringMembers(left: string[], right: string[]): boolean {
  return deepEqual([...left].sort(), [...right].sort());
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
