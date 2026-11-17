// web-admin/app/flow-designer-sdk/edges/EdgeRegistry.ts
import type { FlowEdgeDefinition } from './types';

/**
 * Registry of edge type definitions, keyed by `type`. Kept deliberately minimal
 * (edges don't need icon/category like nodes), parallel to {@link NodeRegistry}.
 */
export class EdgeRegistry {
  private definitions = new Map<string, FlowEdgeDefinition>();

  register(definition: FlowEdgeDefinition): void {
    this.definitions.set(definition.type, definition);
  }

  registerAll(definitions: FlowEdgeDefinition[]): void {
    definitions.forEach((def) => this.register(def));
  }

  get(type: string): FlowEdgeDefinition | undefined {
    return this.definitions.get(type);
  }

  getAll(): FlowEdgeDefinition[] {
    return Array.from(this.definitions.values());
  }

  has(type: string): boolean {
    return this.definitions.has(type);
  }

  clear(): void {
    this.definitions.clear();
  }
}

// Singleton instance
export const edgeRegistry = new EdgeRegistry();
