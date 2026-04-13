/**
 * 动作注册表
 * 管理可用的动作类型和基础校验
 */

import type { Action, ActionRegistryEntry } from '~/plugins/core-designer/components/studio/services/runtime/execution/types';
import { ActionType } from '~/plugins/core-designer/components/studio/services/runtime/execution/types';

export class ActionRegistry {
  private static instance: ActionRegistry;
  private registry: Map<ActionType, ActionRegistryEntry> = new Map();
  private categories: Map<string, ActionType[]> = new Map();

  private constructor() {}

  static getInstance(): ActionRegistry {
    if (!ActionRegistry.instance) {
      ActionRegistry.instance = new ActionRegistry();
    }
    return ActionRegistry.instance;
  }

  register(entry: ActionRegistryEntry): void {
    this.registry.set(entry.type, entry);
    if (entry.category) {
      const existing = this.categories.get(entry.category) ?? [];
      if (!existing.includes(entry.type)) {
        existing.push(entry.type);
        this.categories.set(entry.category, existing);
      }
    }
  }

  get(type: ActionType): ActionRegistryEntry | undefined {
    return this.registry.get(type);
  }

  getAll(): ActionRegistryEntry[] {
    return Array.from(this.registry.values());
  }

  getByCategory(category: string): ActionRegistryEntry[] {
    const types = this.categories.get(category) ?? [];
    return types.map((type) => this.registry.get(type)).filter(Boolean) as ActionRegistryEntry[];
  }

  getCategories(): string[] {
    return Array.from(this.categories.keys());
  }

  search(keyword: string): ActionRegistryEntry[] {
    const lower = keyword.toLowerCase();
    return this.getAll().filter(
      (entry) =>
        entry.name.toLowerCase().includes(lower) || entry.description.toLowerCase().includes(lower),
    );
  }

  createAction(type: ActionType, params: Record<string, any> = {}): Action {
    const entry = this.registry.get(type);
    return {
      id: `action_${Date.now()}`,
      name: entry?.name,
      description: entry?.description,
      enabled: true,
      type,
      params: {
        type,
        ...params,
      } as any,
    };
  }

  validateAction(_action: Action): { valid: boolean; errors: string[] } {
    return { valid: true, errors: [] };
  }
}

export const globalActionRegistry = ActionRegistry.getInstance();
