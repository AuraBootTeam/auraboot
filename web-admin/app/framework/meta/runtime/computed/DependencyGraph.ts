/**
 * DependencyGraph - manages field dependency relationships and topological ordering.
 * Detects circular dependencies and provides evaluation order.
 *
 * @since 3.7.0
 */

export class DependencyGraph {
  /** fieldCode → set of fields it depends on */
  private dependencies = new Map<string, Set<string>>();
  /** fieldCode → set of fields that depend on it (reverse edges) */
  private dependents = new Map<string, Set<string>>();
  /** Cached topological order (invalidated on graph change) */
  private cachedOrder: string[] | null = null;

  /**
   * Add a computed field with its dependencies.
   */
  addField(fieldCode: string, deps: string[]): void {
    this.dependencies.set(fieldCode, new Set(deps));
    for (const dep of deps) {
      if (!this.dependents.has(dep)) {
        this.dependents.set(dep, new Set());
      }
      this.dependents.get(dep)!.add(fieldCode);
    }
    this.cachedOrder = null;
  }

  /**
   * Remove a field from the graph.
   */
  removeField(fieldCode: string): void {
    const deps = this.dependencies.get(fieldCode);
    if (deps) {
      for (const dep of deps) {
        this.dependents.get(dep)?.delete(fieldCode);
      }
    }
    this.dependencies.delete(fieldCode);
    this.dependents.delete(fieldCode);
    this.cachedOrder = null;
  }

  /**
   * Get all fields that need re-evaluation when `changedField` changes.
   * Returns fields in topological order (dependencies first).
   */
  getAffectedFields(changedField: string): string[] {
    const affected = new Set<string>();
    const queue = [changedField];

    while (queue.length > 0) {
      const current = queue.shift()!;
      const deps = this.dependents.get(current);
      if (deps) {
        for (const dep of deps) {
          if (!affected.has(dep)) {
            affected.add(dep);
            queue.push(dep);
          }
        }
      }
    }

    // Sort affected fields in topological order
    return this.topologicalSort([...affected]);
  }

  /**
   * Get all computed fields in evaluation order (topological sort).
   */
  getEvaluationOrder(): string[] {
    if (this.cachedOrder) return this.cachedOrder;
    const allFields = [...this.dependencies.keys()];
    this.cachedOrder = this.topologicalSort(allFields);
    return this.cachedOrder;
  }

  /**
   * Detect circular dependencies. Returns the cycle path if found.
   */
  detectCycle(): string[] | null {
    const visited = new Set<string>();
    const inStack = new Set<string>();
    const path: string[] = [];

    const dfs = (node: string): string[] | null => {
      if (inStack.has(node)) {
        const cycleStart = path.indexOf(node);
        return [...path.slice(cycleStart), node];
      }
      if (visited.has(node)) return null;

      visited.add(node);
      inStack.add(node);
      path.push(node);

      const deps = this.dependencies.get(node);
      if (deps) {
        for (const dep of deps) {
          if (this.dependencies.has(dep)) {
            const cycle = dfs(dep);
            if (cycle) return cycle;
          }
        }
      }

      path.pop();
      inStack.delete(node);
      return null;
    };

    for (const field of this.dependencies.keys()) {
      const cycle = dfs(field);
      if (cycle) return cycle;
    }
    return null;
  }

  /**
   * Topological sort using Kahn's algorithm.
   * Only sorts the given subset of fields.
   */
  private topologicalSort(fields: string[]): string[] {
    const fieldSet = new Set(fields);
    const inDegree = new Map<string, number>();
    const localDeps = new Map<string, Set<string>>();

    // Initialize in-degrees for the subset
    for (const field of fields) {
      inDegree.set(field, 0);
      localDeps.set(field, new Set());
    }

    // Count in-degrees within the subset
    for (const field of fields) {
      const deps = this.dependencies.get(field);
      if (deps) {
        for (const dep of deps) {
          if (fieldSet.has(dep)) {
            inDegree.set(field, (inDegree.get(field) ?? 0) + 1);
            localDeps.get(dep)!.add(field);
          }
        }
      }
    }

    // Kahn's algorithm
    const queue: string[] = [];
    for (const [field, degree] of inDegree) {
      if (degree === 0) queue.push(field);
    }

    const result: string[] = [];
    while (queue.length > 0) {
      const current = queue.shift()!;
      result.push(current);

      const dependents = localDeps.get(current);
      if (dependents) {
        for (const dep of dependents) {
          const newDegree = (inDegree.get(dep) ?? 1) - 1;
          inDegree.set(dep, newDegree);
          if (newDegree === 0) queue.push(dep);
        }
      }
    }

    // If result doesn't include all fields, there's a cycle
    // Add remaining fields at the end (they'll be skipped during evaluation)
    if (result.length < fields.length) {
      for (const field of fields) {
        if (!result.includes(field)) {
          result.push(field);
        }
      }
    }

    return result;
  }

  /**
   * Check if a field is a computed field (has dependencies registered).
   */
  isComputed(fieldCode: string): boolean {
    return this.dependencies.has(fieldCode);
  }

  /**
   * Get direct dependencies of a field.
   */
  getDependencies(fieldCode: string): string[] {
    return [...(this.dependencies.get(fieldCode) ?? [])];
  }

  /**
   * Get direct dependents of a field (fields that depend on it).
   */
  getDependents(fieldCode: string): string[] {
    return [...(this.dependents.get(fieldCode) ?? [])];
  }

  /**
   * Clear all graph data.
   */
  clear(): void {
    this.dependencies.clear();
    this.dependents.clear();
    this.cachedOrder = null;
  }
}
