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

export interface StudioAuthoringCreate {
  blockId: string;
  blockType: string;
  parentBlockId: string | null;
  beforeBlockId: string | null;
  manifestChecksum: string;
}

export interface StudioAuthoringRemove {
  blockId: string;
  manifestChecksum: string;
}

export interface StudioAuthoringRelocation {
  blockId: string;
  targetParentBlockId: string;
  beforeBlockId: string | null;
  manifestChecksum: string;
}

export interface StudioAuthoringPatchPlan {
  patches: StudioAuthoringPatch[];
  moves: StudioAuthoringMove[];
  creates: StudioAuthoringCreate[];
  removes: StudioAuthoringRemove[];
  relocations: StudioAuthoringRelocation[];
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
export const STUDIO_CREATE_BLOCK_PATH = '/$structure/create';
export const STUDIO_REMOVE_BLOCK_PATH = '/$structure/remove';
export const STUDIO_RELOCATE_BLOCK_PATH = '/$structure/parent';

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
  if (Number(snapshot.schemaVersion) === 3 || hasRecursiveKindRoot(blocks, snapshot.kind)) {
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

function hasRecursiveKindRoot(blocks: unknown[], kind: unknown): blocks is DslBlockV3[] {
  if (blocks.length !== 1 || !blocks[0] || typeof blocks[0] !== 'object') return false;
  const root = blocks[0] as Record<string, unknown>;
  return root.blockType === normalizeKind(kind) && Array.isArray(root.blocks);
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
  const manifests = new Map(
    capabilities.manifests.map((manifest) => [manifest.blockType, manifest]),
  );
  const patches: StudioAuthoringPatch[] = [];
  const creates: StudioAuthoringCreate[] = [];
  const removes: StudioAuthoringRemove[] = [];
  const relocations: StudioAuthoringRelocation[] = [];

  baselineIndex.forEach((entry, blockId) => {
    if (candidateIndex.has(blockId)) return;
    if (entry.parentId !== null && !candidateIndex.has(entry.parentId)) return;
    const manifest = manifests.get(entry.block.blockType);
    if (!manifest?.properties[STUDIO_REMOVE_BLOCK_PATH]) {
      unsupported.push(`区块 ${blockId} 的删除能力未由服务端声明`);
      return;
    }
    removes.push({ blockId, manifestChecksum: manifest.checksum });
  });

  candidateIndex.forEach((entry, blockId) => {
    if (baselineIndex.has(blockId)) return;
    const manifest = manifests.get(entry.block.blockType);
    if (!manifest?.properties[STUDIO_CREATE_BLOCK_PATH]) {
      unsupported.push(`区块 ${blockId} 的创建能力未由服务端声明`);
      return;
    }
    creates.push({
      blockId,
      blockType: entry.block.blockType,
      parentBlockId: entry.parentId,
      beforeBlockId: null,
      manifestChecksum: manifest.checksum,
    });
  });

  creates.forEach((create) => {
    const entry = candidateIndex.get(create.blockId);
    const manifest = manifests.get(create.blockType);
    if (!entry || !manifest) return;
    const reconstructed: Record<string, unknown> = {
      id: create.blockId,
      blockType: create.blockType,
    };
    Object.values(manifest.properties)
      .filter((property) => !isStructuralCapabilityPath(property.propertyPath))
      .sort((left, right) => left.propertyPath.localeCompare(right.propertyPath))
      .forEach((property) => {
        const value = readPointer(
          entry.block as unknown as Record<string, unknown>,
          property.propertyPath,
        );
        if (value === undefined) return;
        patches.push({
          blockId: create.blockId,
          propertyPath: property.propertyPath,
          operation: 'ADD',
          value,
          manifestChecksum: manifest.checksum,
        });
        applyPointer(reconstructed, property.propertyPath, value, false);
      });
    if (!deepEqual(reconstructed, withoutChildren(entry.block))) {
      unsupported.push(`新区块 ${create.blockId} 包含能力清单未声明的初始属性`);
    }
  });

  baselineIndex.forEach((entry, blockId) => {
    const nextEntry = candidateIndex.get(blockId);
    if (!nextEntry || entry.parentId === nextEntry.parentId) return;
    if (nextEntry.parentId === null) {
      unsupported.push(`区块 ${blockId} 不能通过现场结构适配器迁移为页面根区块`);
      return;
    }
    const manifest = manifests.get(entry.block.blockType);
    if (!manifest?.properties[STUDIO_RELOCATE_BLOCK_PATH]) {
      unsupported.push(`区块 ${blockId} 的跨父级移动能力未由服务端声明`);
      return;
    }
    relocations.push({
      blockId,
      targetParentBlockId: nextEntry.parentId,
      beforeBlockId: null,
      manifestChecksum: manifest.checksum,
    });
  });

  const structuralBaseline = cloneJson(baseline);
  removes.forEach((remove) => removeBlockForPlanning(structuralBaseline.blocks, remove.blockId));
  creates.forEach((create) => appendBlockForPlanning(structuralBaseline, create));
  relocations.forEach((relocation) => relocateBlockForPlanning(structuralBaseline, relocation));
  const structuralIndex = indexBlocks(structuralBaseline.blocks);
  const moves: StudioAuthoringMove[] = [];
  const candidateOrders = siblingOrders(candidate);
  const structuralOrders = siblingOrders(structuralBaseline);
  candidateOrders.forEach((desiredOrder, parentId) => {
    const currentOrder = structuralOrders.get(parentId);
    if (!currentOrder || deepEqual(currentOrder, desiredOrder)) return;
    if (!sameStringMembers(currentOrder, desiredOrder)) {
      unsupported.push(`父级 ${parentId} 的结构集合无法由受治理适配器精确重建`);
      return;
    }
    const orderPlan = planSiblingMoves(currentOrder, desiredOrder, structuralIndex, manifests);
    moves.push(...orderPlan.moves);
    unsupported.push(...orderPlan.unsupported);
  });

  baselineIndex.forEach((baseEntry, blockId) => {
    const nextEntry = candidateIndex.get(blockId);
    if (!nextEntry) return;
    if (baseEntry.block.blockType !== nextEntry.block.blockType) {
      unsupported.push(`区块 ${blockId} 的类型变更尚未声明为 typed patch`);
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

  return {
    patches,
    moves,
    creates,
    removes,
    relocations,
    unsupported: [...new Set(unsupported)],
  };
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
  if (minePlan.creates.length || minePlan.removes.length || minePlan.relocations.length) {
    unsupported.push('结构变更暂不参与三方自动合并，请刷新后重新执行该结构操作');
  }
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

export function studioCreatableBlockTypes(capabilities: CapabilityRegistry): string[] {
  return studioStructuralBlockTypes(capabilities, STUDIO_CREATE_BLOCK_PATH, 'ADD');
}

export function studioRemovableBlockTypes(capabilities: CapabilityRegistry): string[] {
  return studioStructuralBlockTypes(capabilities, STUDIO_REMOVE_BLOCK_PATH, 'REMOVE');
}

export function studioRelocatableBlockTypes(capabilities: CapabilityRegistry): string[] {
  return studioStructuralBlockTypes(capabilities, STUDIO_RELOCATE_BLOCK_PATH, 'MOVE');
}

function studioStructuralBlockTypes(
  capabilities: CapabilityRegistry,
  propertyPath: string,
  operation: string,
): string[] {
  return capabilities.manifests
    .filter((manifest) => manifest.properties[propertyPath]?.allowedOperations.includes(operation))
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

function removeBlockForPlanning(blocks: DslBlockV3[], blockId: string): DslBlockV3 | null {
  const directIndex = blocks.findIndex((block) => block.id === blockId);
  if (directIndex >= 0) return blocks.splice(directIndex, 1)[0] ?? null;
  for (const block of blocks) {
    const removed = removeBlockForPlanning(block.blocks ?? [], blockId);
    if (removed) return removed;
  }
  return null;
}

function appendBlockForPlanning(
  document: PageSchemaV3,
  create: StudioAuthoringCreate,
): void {
  const block: DslBlockV3 = { id: create.blockId, blockType: create.blockType, blocks: [] };
  if (create.parentBlockId === null) {
    document.blocks.push(block);
    return;
  }
  const parent = indexBlocks(document.blocks).get(create.parentBlockId)?.block;
  if (!parent) return;
  (parent.blocks ??= []).push(block);
}

function relocateBlockForPlanning(
  document: PageSchemaV3,
  relocation: StudioAuthoringRelocation,
): void {
  const block = removeBlockForPlanning(document.blocks, relocation.blockId);
  if (!block) return;
  const parent = indexBlocks(document.blocks).get(relocation.targetParentBlockId)?.block;
  if (!parent) return;
  (parent.blocks ??= []).push(block);
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
  if (Object.is(left, right)) return true;
  if (Array.isArray(left) || Array.isArray(right)) {
    return Array.isArray(left)
      && Array.isArray(right)
      && left.length === right.length
      && left.every((value, index) => deepEqual(value, right[index]));
  }
  if (
    !left
    || !right
    || typeof left !== 'object'
    || typeof right !== 'object'
  ) {
    return false;
  }
  const leftObject = left as Record<string, unknown>;
  const rightObject = right as Record<string, unknown>;
  const leftKeys = Object.keys(leftObject).sort();
  const rightKeys = Object.keys(rightObject).sort();
  return leftKeys.length === rightKeys.length
    && leftKeys.every((key, index) => (
      key === rightKeys[index] && deepEqual(leftObject[key], rightObject[key])
    ));
}
