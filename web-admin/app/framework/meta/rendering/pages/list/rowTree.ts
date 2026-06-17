/**
 * rowTree — pure tree builder for the DSL list table's expandable / tree rows
 * (T10). Turns a flat array of rows that self-reference a parent (e.g.
 * `parent_id`) into a nested structure, and flattens it back to a visible,
 * depth-annotated list given a set of expanded node ids.
 *
 * Design goals (vs the SubTable-scoped `useTreeData` hook):
 * - **Pure** (no React) so it is unit-testable in jsdom with zero geometry and
 *   reusable from the list renderer.
 * - **Orphan-safe**: a row whose parent id is not present in the dataset is
 *   promoted to a root (never silently dropped).
 * - **Cycle-safe**: a parent chain that loops (a→b→a, or a→a) terminates — each
 *   row appears exactly once and is never walked twice.
 *
 * Aggregation/sorting is intentionally NOT done here; rows are kept in their
 * incoming order so the caller's existing sort (DSL defaultSort / user sort)
 * is preserved within each sibling group.
 */

export interface RowTreeOptions {
  /** Field holding the row's own id. Falls back to `id` when the configured
   *  field is absent on a given row (rows are keyed by `pid` or `id`). */
  idField: string;
  /** Self-referencing field pointing at the parent row's id (e.g. `parent_id`). */
  parentField: string;
}

export interface RowTreeNode {
  row: Record<string, any>;
  /** 0 = root. */
  depth: number;
  children: RowTreeNode[];
}

export interface FlattenedRow {
  row: Record<string, any>;
  depth: number;
  hasChildren: boolean;
}

function readId(row: Record<string, any>, idField: string): string | null {
  const raw = row[idField] ?? row.id ?? row.pid;
  if (raw == null || raw === '') return null;
  return String(raw);
}

function readParentId(row: Record<string, any>, parentField: string): string | null {
  const raw = row[parentField];
  if (raw == null || raw === '') return null;
  return String(raw);
}

/**
 * Build a nested tree from flat rows.
 *
 * Roots = rows with no parent, OR rows whose parent id does not resolve to any
 * row in the dataset (orphans). Sibling order follows input order. The walk is
 * cycle-safe: every row is attached to the tree exactly once; a row that would
 * be revisited via a parent loop is skipped on the second encounter so the
 * structure stays finite.
 */
export function buildRowTree(rows: Record<string, any>[], options: RowTreeOptions): RowTreeNode[] {
  const { idField, parentField } = options;

  // Index rows by id, preserving input order for sibling iteration.
  const byId = new Map<string, Record<string, any>>();
  const order: string[] = [];
  for (const row of rows) {
    const id = readId(row, idField);
    if (id == null) continue;
    if (!byId.has(id)) order.push(id);
    byId.set(id, row);
  }

  // children[parentId] = ordered list of child ids. Roots collected separately.
  const childIds = new Map<string, string[]>();
  const rootIds: string[] = [];
  for (const id of order) {
    const row = byId.get(id)!;
    const parentId = readParentId(row, parentField);
    // A row is a root when it has no parent, when its parent points at itself
    // (self-cycle), or when the parent id is not present in the dataset (orphan).
    if (parentId == null || parentId === id || !byId.has(parentId)) {
      rootIds.push(id);
      continue;
    }
    if (!childIds.has(parentId)) childIds.set(parentId, []);
    childIds.get(parentId)!.push(id);
  }

  // Depth-first attach. `placed` guards against cycles (a→b→a): once an id is
  // placed it is never expanded again, so a back-edge into an ancestor is
  // dropped instead of looping forever.
  const placed = new Set<string>();

  function buildNode(id: string, depth: number): RowTreeNode | null {
    if (placed.has(id)) return null;
    placed.add(id);
    const node: RowTreeNode = { row: byId.get(id)!, depth, children: [] };
    for (const childId of childIds.get(id) ?? []) {
      const child = buildNode(childId, depth + 1);
      if (child) node.children.push(child);
    }
    return node;
  }

  const tree: RowTreeNode[] = [];
  for (const id of rootIds) {
    const node = buildNode(id, 0);
    if (node) tree.push(node);
  }

  // Any row not reachable from a root belongs to a parentful cycle (e.g.
  // a→b→a where neither is a root). Surface each such row as a root in input
  // order so the cycle's members are still shown rather than silently dropped;
  // `placed` keeps the rest of the loop from being walked again.
  for (const id of order) {
    if (placed.has(id)) continue;
    const node = buildNode(id, 0);
    if (node) tree.push(node);
  }
  return tree;
}

/**
 * Depth-first flatten of a tree, descending into a node's children only when
 * the node's id is in `expandedIds`. Each returned row carries its `depth` and
 * whether it `hasChildren` (so the renderer can draw indentation + a chevron).
 *
 * Nodes are keyed by `idField` (falling back to `id`/`pid`) to match
 * `expandedIds`, which the renderer toggles by the same id.
 */
export function flattenVisible(
  tree: RowTreeNode[],
  expandedIds: Set<string>,
  options: RowTreeOptions = { idField: 'pid', parentField: 'parent_id' },
): FlattenedRow[] {
  const out: FlattenedRow[] = [];

  function walk(nodes: RowTreeNode[]): void {
    for (const node of nodes) {
      const hasChildren = node.children.length > 0;
      out.push({ row: node.row, depth: node.depth, hasChildren });
      if (hasChildren) {
        const id = readId(node.row, options.idField);
        if (id != null && expandedIds.has(id)) {
          walk(node.children);
        }
      }
    }
  }

  walk(tree);
  return out;
}

/** Collect every node id in a tree — convenience for "expand all" defaults. */
export function collectAllNodeIds(
  tree: RowTreeNode[],
  options: RowTreeOptions = { idField: 'pid', parentField: 'parent_id' },
): Set<string> {
  const ids = new Set<string>();
  function walk(nodes: RowTreeNode[]): void {
    for (const node of nodes) {
      const id = readId(node.row, options.idField);
      if (id != null) ids.add(id);
      walk(node.children);
    }
  }
  walk(tree);
  return ids;
}
