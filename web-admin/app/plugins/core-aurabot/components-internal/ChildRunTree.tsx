/**
 * ChildRunTree
 *
 * Renders a flat list of agent runs as an indented tree based on
 * `parentRunId` linkage. Used by AgentRunDetailDrawer to surface
 * sub-agent hierarchy that was previously hidden by a flat list.
 *
 * Contract:
 *   - Input is a flat array of `AgentRunListItem`. Each item that has
 *     `parentRunId` matching another item's `runId` is nested under
 *     that parent. Items whose `parentRunId` does not appear in the
 *     list (orphans) and items with `parentRunId === null` are treated
 *     as tree roots — together they form the top level under the
 *     parent run whose drawer this section belongs to.
 *   - Rendering is depth-capped at MAX_DEPTH = 5. Anything beyond is
 *     replaced by a single "(... N more nested runs)" placeholder so
 *     adversarial / cyclic data cannot blow up the React tree.
 *   - Cycles (A -> B -> A) are broken via a `seen` set carried down
 *     the recursion; a node already on the current ancestor path is
 *     skipped silently (no infinite recursion).
 *
 * No external tree library — vanilla recursion + `pl-{n}` indentation.
 */

import type { AgentRunListItem } from '../services/agentRunsApi';

const MAX_DEPTH = 5;
// Avoid Tailwind dynamic class names — JIT can't see `pl-${n*4}` as a string.
const INDENT_CLASS = ['pl-0', 'pl-4', 'pl-8', 'pl-12', 'pl-16', 'pl-20'] as const;

function shortPid(pid: string | null | undefined): string {
  if (!pid) return '-';
  return pid.length > 10 ? `${pid.slice(0, 8)}…` : pid;
}

interface Props {
  rows: AgentRunListItem[];
  /**
   * The parent run whose drawer is open. Children whose `parentRunId`
   * matches this id are top-level. Pass `null` to render strictly by
   * `parentRunId === null` semantics.
   */
  parentRunId: string | null;
  onSelectRun: (runId: string) => void;
}

/**
 * Build a Map<parentRunId, AgentRunListItem[]> for O(1) child lookups.
 * Items whose parentRunId is the prop's `parentRunId` (or null when the
 * caller passed a non-null root) are grouped under that key. Items
 * whose declared parent isn't in the list are also surfaced as roots
 * so we never drop data silently.
 */
function indexByParent(
  rows: AgentRunListItem[],
  rootParentId: string | null,
): Map<string | null, AgentRunListItem[]> {
  const idSet = new Set(rows.map((r) => r.runId));
  const byParent = new Map<string | null, AgentRunListItem[]>();
  const push = (key: string | null, item: AgentRunListItem) => {
    const list = byParent.get(key);
    if (list) list.push(item);
    else byParent.set(key, [item]);
  };
  for (const row of rows) {
    const parent = row.parentRunId;
    // Treat as root if: matches drawer's runId, has no parent, or its
    // declared parent isn't in this list (orphan defensive case).
    if (parent === rootParentId || parent === null || !idSet.has(parent)) {
      push(rootParentId, row);
    } else {
      push(parent, row);
    }
  }
  return byParent;
}

function statusColor(status: string | null | undefined): string {
  switch ((status ?? '').toLowerCase()) {
    case 'succeeded':
    case 'success':
      return 'bg-emerald-100 text-emerald-800';
    case 'failed':
    case 'error':
      return 'bg-red-100 text-red-800';
    case 'running':
    case 'pending':
      return 'bg-blue-100 text-blue-800';
    case 'cancelled':
      return 'bg-gray-200 text-gray-700';
    case 'timeout':
      return 'bg-amber-100 text-amber-800';
    default:
      return 'bg-gray-100 text-gray-600';
  }
}

interface NodeProps {
  row: AgentRunListItem;
  byParent: Map<string | null, AgentRunListItem[]>;
  depth: number;
  ancestors: Set<string>;
  onSelectRun: (runId: string) => void;
}

function countDescendants(
  rootRunId: string,
  byParent: Map<string | null, AgentRunListItem[]>,
  seen: Set<string>,
): number {
  if (seen.has(rootRunId)) return 0;
  const nextSeen = new Set(seen);
  nextSeen.add(rootRunId);
  let total = 0;
  const direct = byParent.get(rootRunId) ?? [];
  for (const child of direct) {
    if (nextSeen.has(child.runId)) continue;
    total += 1 + countDescendants(child.runId, byParent, nextSeen);
  }
  return total;
}

function ChildRunNode({ row, byParent, depth, ancestors, onSelectRun }: NodeProps) {
  // Cycle break: the current node is already on the path above.
  if (ancestors.has(row.runId)) return null;

  const indent = INDENT_CLASS[Math.min(depth, INDENT_CLASS.length - 1)];
  const children = byParent.get(row.runId) ?? [];
  const hasChildren = children.length > 0;
  // MAX_DEPTH = 5 means at most 5 visible levels (depth 0..4). A node at
  // depth 4 still renders itself but collapses its descendants.
  const atDepthCap = depth >= MAX_DEPTH - 1;

  return (
    <li data-testid={`child-run-node-${row.runId}`} data-depth={depth}>
      <div className={`flex items-center gap-2 ${indent}`}>
        {depth > 0 && (
          <span aria-hidden className="text-gray-300 font-mono text-xs">
            └─
          </span>
        )}
        <button
          type="button"
          onClick={() => onSelectRun(row.runId)}
          className="text-xs font-mono text-blue-600 hover:underline"
          data-testid={`child-run-${row.runId}`}
        >
          {shortPid(row.runId)}
        </button>
        <span className="text-xs text-gray-600">{row.agentCode ?? '-'}</span>
        <span
          className={`px-1.5 py-0.5 text-[10px] rounded ${statusColor(row.runStatus)}`}
        >
          {row.runStatus}
        </span>
      </div>
      {hasChildren && atDepthCap && (
        <div
          className={INDENT_CLASS[Math.min(depth + 1, INDENT_CLASS.length - 1)]}
          data-testid={`child-run-depth-cap-${row.runId}`}
        >
          <span className="text-[11px] italic text-gray-400">
            (... {countDescendants(row.runId, byParent, ancestors)} more nested runs)
          </span>
        </div>
      )}
      {hasChildren && !atDepthCap && (
        <ul className="space-y-1 mt-1">
          {children.map((c) => {
            const nextAncestors = new Set(ancestors);
            nextAncestors.add(row.runId);
            return (
              <ChildRunNode
                key={c.runId}
                row={c}
                byParent={byParent}
                depth={depth + 1}
                ancestors={nextAncestors}
                onSelectRun={onSelectRun}
              />
            );
          })}
        </ul>
      )}
    </li>
  );
}

export default function ChildRunTree({ rows, parentRunId, onSelectRun }: Props) {
  if (rows.length === 0) {
    return <div className="text-xs text-gray-500">No child runs.</div>;
  }
  const byParent = indexByParent(rows, parentRunId);
  const roots = byParent.get(parentRunId) ?? [];
  if (roots.length === 0) {
    return <div className="text-xs text-gray-500">No child runs.</div>;
  }
  return (
    <ul className="space-y-1" data-testid="child-run-tree">
      {roots.map((r) => (
        <ChildRunNode
          key={r.runId}
          row={r}
          byParent={byParent}
          depth={0}
          ancestors={new Set<string>()}
          onSelectRun={onSelectRun}
        />
      ))}
    </ul>
  );
}

export { MAX_DEPTH };
