import type { BlockConfig, DetailBodyGroupConfig, PageUxProfile } from '../../schemas/types';

const PAGE_PROFILES = new Set<PageUxProfile>(['work-list', 'configuration', 'lifecycle-detail']);

/** Only explicitly declared shared page families opt into the layered canvas. */
export function resolvePageUxProfile(extension: Record<string, unknown> | undefined): PageUxProfile | undefined {
  const value = extension?.uxProfile;
  return typeof value === 'string' && PAGE_PROFILES.has(value as PageUxProfile)
    ? value as PageUxProfile : undefined;
}

export interface DetailBodyUnit {
  key: string;
  group?: DetailBodyGroupConfig;
  blocks: BlockConfig[];
}

/** Group existing top-level blocks without changing their identities or bindings. */
export function resolveDetailBodyGroups(groups: DetailBodyGroupConfig[], blocks: BlockConfig[]): DetailBodyUnit[] {
  if (!Array.isArray(groups)) throw new Error('Detail body groups must be an array');
  const byId = new Map(blocks.map(block => [block.id, block]));
  const owner = new Map<string, DetailBodyGroupConfig>();
  const groupIds = new Set<string>();
  for (const group of groups) {
    if (!group || typeof group.id !== 'string' || !group.id.trim() || groupIds.has(group.id)
      || !Array.isArray(group.blockIds) || !group.blockIds.length
      || (group.layout !== undefined && !['stack', 'rows'].includes(group.layout))) {
      throw new Error('Invalid or duplicate detail body group');
    }
    groupIds.add(group.id);
    for (const id of group.blockIds) {
      if (typeof id !== 'string' || !byId.has(id) || owner.has(id)) {
        throw new Error(`Invalid or repeated detail body block: ${String(id)}`);
      }
      owner.set(id, group);
    }
  }
  const emitted = new Set<string>();
  const result: DetailBodyUnit[] = [];
  for (const block of blocks) {
    const group = owner.get(block.id);
    if (!group) result.push({ key: `block:${block.id}`, blocks: [block] });
    else if (!emitted.has(group.id)) {
      emitted.add(group.id);
      result.push({ key: `group:${group.id}`, group, blocks: group.blockIds.map(id => byId.get(id)!) });
    }
  }
  return result;
}
