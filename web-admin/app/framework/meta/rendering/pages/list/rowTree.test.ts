import { describe, expect, it } from 'vitest';
import { buildRowTree, flattenVisible, type RowTreeOptions } from './rowTree';

// Default options used by most cases — id keyed by `pid`, parent by `parent_id`.
const OPTS: RowTreeOptions = { idField: 'pid', parentField: 'parent_id' };

/** Collect ids from a flattened result for terse assertions. */
function ids(rows: ReturnType<typeof flattenVisible>): string[] {
  return rows.map((r) => String(r.row.pid));
}

/** Set of every node id in a tree (for "expand all" helpers). */
function allIds(rows: Record<string, any>[]): Set<string> {
  return new Set(rows.map((r) => String(r.pid)));
}

describe('buildRowTree', () => {
  it('treats flat rows with no parents as all roots at depth 0', () => {
    const rows = [
      { pid: 'a', name: 'A' },
      { pid: 'b', name: 'B' },
      { pid: 'c', name: 'C' },
    ];
    const tree = buildRowTree(rows, OPTS);
    expect(tree).toHaveLength(3);
    expect(tree.map((n) => n.row.pid)).toEqual(['a', 'b', 'c']);
    expect(tree.every((n) => n.children.length === 0)).toBe(true);
    // flattenVisible (with no expansion) shows all roots at depth 0, no children.
    const flat = flattenVisible(tree, new Set());
    expect(ids(flat)).toEqual(['a', 'b', 'c']);
    expect(flat.every((f) => f.depth === 0)).toBe(true);
    expect(flat.every((f) => f.hasChildren === false)).toBe(true);
  });

  it('nests 2-3 levels deep under the correct parents', () => {
    const rows = [
      { pid: 'root', parent_id: null },
      { pid: 'child', parent_id: 'root' },
      { pid: 'grandchild', parent_id: 'child' },
    ];
    const tree = buildRowTree(rows, OPTS);
    expect(tree).toHaveLength(1);
    expect(tree[0].row.pid).toBe('root');
    expect(tree[0].children).toHaveLength(1);
    expect(tree[0].children[0].row.pid).toBe('child');
    expect(tree[0].children[0].children[0].row.pid).toBe('grandchild');
  });

  it('keeps multiple roots and groups their respective children', () => {
    const rows = [
      { pid: 'r1', parent_id: null },
      { pid: 'r2', parent_id: null },
      { pid: 'r1a', parent_id: 'r1' },
      { pid: 'r2a', parent_id: 'r2' },
      { pid: 'r2b', parent_id: 'r2' },
    ];
    const tree = buildRowTree(rows, OPTS);
    expect(tree.map((n) => n.row.pid)).toEqual(['r1', 'r2']);
    expect(tree[0].children.map((n) => n.row.pid)).toEqual(['r1a']);
    expect(tree[1].children.map((n) => n.row.pid)).toEqual(['r2a', 'r2b']);
  });

  it('promotes an orphan (parent missing from the dataset) to a root', () => {
    const rows = [
      { pid: 'root', parent_id: null },
      { pid: 'child', parent_id: 'root' },
      { pid: 'orphan', parent_id: 'ghost-parent-not-in-set' },
    ];
    const tree = buildRowTree(rows, OPTS);
    // root + orphan are both top-level; child stays under root.
    expect(tree.map((n) => n.row.pid).sort()).toEqual(['orphan', 'root']);
    const orphanNode = tree.find((n) => n.row.pid === 'orphan')!;
    expect(orphanNode.depth).toBe(0);
    expect(orphanNode.children).toHaveLength(0);
  });

  it('does not infinite-loop on a 2-cycle (a -> b -> a)', () => {
    const rows = [
      { pid: 'a', parent_id: 'b' },
      { pid: 'b', parent_id: 'a' },
    ];
    // The mere fact this returns (no hang / no RangeError) is the assertion.
    const tree = buildRowTree(rows, OPTS);
    // Every input row must appear exactly once across the whole tree.
    const seen: string[] = [];
    function visit(nodes: ReturnType<typeof buildRowTree>): void {
      for (const n of nodes) {
        seen.push(String(n.row.pid));
        visit(n.children);
      }
    }
    visit(tree);
    expect(seen.sort()).toEqual(['a', 'b']);
  });

  it('does not infinite-loop on a self-cycle (a -> a)', () => {
    const rows = [{ pid: 'a', parent_id: 'a' }];
    const tree = buildRowTree(rows, OPTS);
    const flat = flattenVisible(tree, new Set(['a']));
    expect(ids(flat)).toEqual(['a']);
  });

  it('supports `id` fallback when `idField` row value is on `id` not `pid`', () => {
    const rows = [
      { id: 'p', parent_id: null },
      { id: 'c', parent_id: 'p' },
    ];
    const tree = buildRowTree(rows, { idField: 'id', parentField: 'parent_id' });
    expect(tree).toHaveLength(1);
    expect(tree[0].children[0].row.id).toBe('c');
  });

  it('marks hasChildren correctly on nodes', () => {
    const rows = [
      { pid: 'root', parent_id: null },
      { pid: 'leaf', parent_id: 'root' },
    ];
    const tree = buildRowTree(rows, OPTS);
    const flat = flattenVisible(tree, new Set(['root']));
    const byId = new Map(flat.map((f) => [String(f.row.pid), f]));
    expect(byId.get('root')!.hasChildren).toBe(true);
    expect(byId.get('leaf')!.hasChildren).toBe(false);
  });
});

describe('flattenVisible', () => {
  const rows = [
    { pid: 'root', parent_id: null },
    { pid: 'child1', parent_id: 'root' },
    { pid: 'child2', parent_id: 'root' },
    { pid: 'grandchild', parent_id: 'child1' },
    { pid: 'root2', parent_id: null },
  ];
  const tree = buildRowTree(rows, OPTS);

  it('shows only roots when nothing is expanded', () => {
    const flat = flattenVisible(tree, new Set());
    expect(ids(flat)).toEqual(['root', 'root2']);
    const rootRow = flat.find((f) => f.row.pid === 'root')!;
    expect(rootRow.hasChildren).toBe(true);
    expect(rootRow.depth).toBe(0);
  });

  it('reveals direct children when a node is expanded', () => {
    const flat = flattenVisible(tree, new Set(['root']));
    expect(ids(flat)).toEqual(['root', 'child1', 'child2', 'root2']);
    const child1 = flat.find((f) => f.row.pid === 'child1')!;
    expect(child1.depth).toBe(1);
    expect(child1.hasChildren).toBe(true);
  });

  it('descends into deeper levels only when intermediate nodes are also expanded', () => {
    // root expanded but child1 NOT → grandchild stays hidden
    expect(ids(flattenVisible(tree, new Set(['root'])))).not.toContain('grandchild');
    // both expanded → grandchild visible at depth 2
    const flat = flattenVisible(tree, new Set(['root', 'child1']));
    expect(ids(flat)).toEqual(['root', 'child1', 'grandchild', 'child2', 'root2']);
    expect(flat.find((f) => f.row.pid === 'grandchild')!.depth).toBe(2);
  });

  it('collapsing a node hides its whole subtree', () => {
    const expandedAll = allIds(rows);
    const allFlat = flattenVisible(tree, expandedAll);
    expect(ids(allFlat)).toContain('grandchild');
    // Collapse child1 → grandchild gone, child2 still there.
    expandedAll.delete('child1');
    const collapsed = flattenVisible(tree, expandedAll);
    expect(ids(collapsed)).not.toContain('grandchild');
    expect(ids(collapsed)).toContain('child2');
  });

  it('returns an empty array for an empty tree', () => {
    expect(flattenVisible([], new Set())).toEqual([]);
  });
});
