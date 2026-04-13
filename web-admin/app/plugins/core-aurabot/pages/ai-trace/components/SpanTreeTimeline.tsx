import { useState, useMemo } from 'react';

interface Span {
  spanId: string;
  parentSpanId: string | null;
  type: string;
  name: string;
  status: string;
  startTime: string;
  endTime: string | null;
  durationMs: number | null;
  model: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  cost: number | null;
  sequenceOrder: number;
}

interface Props {
  spans: Span[];
  totalDurationMs: number;
  traceStartTime: string;
  selectedSpanId: string | null;
  onSelectSpan: (spanId: string) => void;
}

// Span type visual configuration
const TYPE_CONFIG: Record<
  string,
  { icon: string; color: string; barColor: string; label: string }
> = {
  GENERATION: {
    icon: '\uD83E\uDDE0',
    color: 'text-blue-600 dark:text-blue-400',
    barColor: 'bg-blue-400 dark:bg-blue-500',
    label: 'llm',
  },
  TOOL: {
    icon: '\uD83D\uDD27',
    color: 'text-emerald-600 dark:text-emerald-400',
    barColor: 'bg-emerald-400 dark:bg-emerald-500',
    label: 'Tool',
  },
  SPAN: {
    icon: '\uD83D\uDCCE',
    color: 'text-purple-600 dark:text-purple-400',
    barColor: 'bg-purple-400 dark:bg-purple-500',
    label: 'Span',
  },
  EVENT: {
    icon: '\uD83D\uDCCC',
    color: 'text-amber-600 dark:text-amber-400',
    barColor: 'bg-amber-400 dark:bg-amber-500',
    label: 'Event',
  },
};

const STATUS_BAR_COLORS: Record<string, string> = {
  success: 'bg-green-400 dark:bg-green-500',
  confirmed: 'bg-green-400 dark:bg-green-500',
  ERROR: 'bg-red-400 dark:bg-red-500',
  pending: 'bg-amber-400 dark:bg-amber-500',
  in_progress: 'bg-blue-300 dark:bg-blue-400',
  cancelled: 'bg-gray-300 dark:bg-gray-500',
};

interface TreeNode extends Span {
  children: TreeNode[];
  depth: number;
}

function buildTree(spans: Span[]): TreeNode[] {
  const nodeMap = new Map<string, TreeNode>();
  const roots: TreeNode[] = [];

  for (const span of spans) {
    nodeMap.set(span.spanId, { ...span, children: [], depth: 0 });
  }

  for (const node of nodeMap.values()) {
    if (node.parentSpanId && nodeMap.has(node.parentSpanId)) {
      const parent = nodeMap.get(node.parentSpanId)!;
      node.depth = parent.depth + 1;
      parent.children.push(node);
    } else {
      roots.push(node);
    }
  }

  function sortChildren(nodes: TreeNode[]) {
    nodes.sort((a, b) => a.sequenceOrder - b.sequenceOrder);
    nodes.forEach((n) => sortChildren(n.children));
  }
  sortChildren(roots);

  return roots;
}

function flattenTree(nodes: TreeNode[], collapsedSet: Set<string>): TreeNode[] {
  const result: TreeNode[] = [];
  function walk(node: TreeNode) {
    result.push(node);
    if (!collapsedSet.has(node.spanId)) {
      node.children.forEach(walk);
    }
  }
  nodes.forEach(walk);
  return result;
}

function fmtDuration(ms: number | null): string {
  if (ms == null) return '-';
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(ms / 60000)}m${Math.round((ms % 60000) / 1000)}s`;
}

export function SpanTreeTimeline({
  spans,
  totalDurationMs,
  traceStartTime,
  selectedSpanId,
  onSelectSpan,
}: Props) {
  const [collapsedSet, setCollapsedSet] = useState<Set<string>>(new Set());

  const tree = useMemo(() => buildTree(spans), [spans]);
  const flatNodes = useMemo(() => flattenTree(tree, collapsedSet), [tree, collapsedSet]);

  const traceStart = new Date(traceStartTime).getTime();
  const totalMs = totalDurationMs || 1;

  const toggleCollapse = (spanId: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setCollapsedSet((prev) => {
      const next = new Set(prev);
      if (next.has(spanId)) {
        next.delete(spanId);
      } else {
        next.add(spanId);
      }
      return next;
    });
  };

  // Generate time scale markers
  const scaleMarkers = useMemo(() => {
    const count = 5;
    return Array.from({ length: count + 1 }, (_, i) => {
      const ms = (totalMs / count) * i;
      return { pct: (i / count) * 100, label: fmtDuration(Math.round(ms)) };
    });
  }, [totalMs]);

  // Span type legend
  const usedTypes = useMemo(() => {
    const types = new Set(spans.map((s) => s.type));
    return Array.from(types);
  }, [spans]);

  return (
    <div
      className="overflow-hidden rounded-lg border border-gray-200 dark:border-gray-700"
      data-testid="span-tree-timeline"
    >
      {/* Header */}
      <div className="flex items-center justify-between border-b border-gray-200 bg-gray-50 px-4 py-2 dark:border-gray-700 dark:bg-gray-800">
        <div className="flex items-center gap-3">
          <span className="text-xs font-medium text-gray-700 dark:text-gray-300">
            Waterfall Timeline
          </span>
          <div className="flex items-center gap-2">
            {usedTypes.map((type) => {
              const cfg = TYPE_CONFIG[type];
              return (
                <span
                  key={type}
                  className="flex items-center gap-1 text-[10px] text-gray-500 dark:text-gray-400"
                >
                  <span className={`h-2 w-2 rounded-sm ${cfg?.barColor || 'bg-gray-300'}`} />
                  {cfg?.label || type}
                </span>
              );
            })}
          </div>
        </div>
        <span className="font-mono text-xs text-gray-500 dark:text-gray-400">
          {fmtDuration(totalMs)}
        </span>
      </div>

      {/* Time scale */}
      <div className="relative h-5 border-b border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-800/50">
        <div className="absolute top-0 right-[70px] left-[280px] h-full">
          {scaleMarkers.map((m, i) => (
            <div
              key={i}
              className="absolute top-0 h-full border-l border-gray-200 dark:border-gray-700"
              style={{ left: `${m.pct}%` }}
            >
              <span className="absolute top-0.5 left-1 font-mono text-[9px] text-gray-400 dark:text-gray-500">
                {m.label}
              </span>
            </div>
          ))}
        </div>
      </div>

      {/* Span rows */}
      <div className="divide-y divide-gray-100 dark:divide-gray-700/50">
        {flatNodes.map((node) => {
          const spanStart = new Date(node.startTime).getTime();
          const offsetPct = Math.max(0, Math.min(((spanStart - traceStart) / totalMs) * 100, 100));
          const widthPct = Math.max(
            0.5,
            Math.min(((node.durationMs || 1) / totalMs) * 100, 100 - offsetPct),
          );
          const isSelected = node.spanId === selectedSpanId;
          const hasChildren = node.children.length > 0;
          const isCollapsed = collapsedSet.has(node.spanId);
          const cfg = TYPE_CONFIG[node.type] || TYPE_CONFIG.SPAN;
          const barColor =
            node.status === 'error'
              ? STATUS_BAR_COLORS.ERROR
              : STATUS_BAR_COLORS[node.status] || cfg.barColor;

          return (
            <div
              key={node.spanId}
              onClick={() => onSelectSpan(node.spanId)}
              className={`flex cursor-pointer items-center px-3 py-1.5 text-xs transition-colors ${
                isSelected
                  ? 'bg-blue-50 ring-1 ring-blue-200 ring-inset dark:bg-blue-900/20 dark:ring-blue-800'
                  : 'hover:bg-gray-50 dark:hover:bg-gray-800/50'
              }`}
              data-testid={`span-row-${node.spanId}`}
            >
              {/* Name column */}
              <div
                className="flex w-[280px] shrink-0 items-center gap-1.5 overflow-hidden"
                style={{ paddingLeft: node.depth * 16 }}
              >
                {hasChildren ? (
                  <button
                    onClick={(e) => toggleCollapse(node.spanId, e)}
                    className="flex h-4 w-4 shrink-0 items-center justify-center text-gray-400 hover:text-gray-600 dark:text-gray-500 dark:hover:text-gray-300"
                  >
                    {isCollapsed ? '\u25B6' : '\u25BC'}
                  </button>
                ) : (
                  <span className="w-4 shrink-0" />
                )}
                <span className="shrink-0">{cfg.icon}</span>
                <span className={`truncate font-mono ${cfg.color}`} title={node.name}>
                  {node.name}
                </span>
              </div>

              {/* Waterfall bar area */}
              <div className="relative mx-2 h-5 flex-1 overflow-hidden rounded bg-gray-100 dark:bg-gray-800">
                {/* Grid lines */}
                {scaleMarkers.slice(1, -1).map((m, i) => (
                  <div
                    key={i}
                    className="absolute top-0 h-full border-l border-gray-200/50 dark:border-gray-700/30"
                    style={{ left: `${m.pct}%` }}
                  />
                ))}
                {/* The bar */}
                <div
                  className={`absolute h-full rounded transition-all ${barColor}`}
                  style={{
                    left: `${offsetPct}%`,
                    width: `${widthPct}%`,
                    minWidth: '2px',
                  }}
                  title={`${node.name}: ${fmtDuration(node.durationMs)}`}
                >
                  {/* Duration label inside bar if wide enough */}
                  {widthPct > 8 && (
                    <span className="absolute inset-0 flex items-center justify-center truncate px-1 font-mono text-[9px] text-white">
                      {fmtDuration(node.durationMs)}
                    </span>
                  )}
                </div>
              </div>

              {/* Duration column */}
              <div className="w-[70px] shrink-0 text-right font-mono text-gray-600 tabular-nums dark:text-gray-400">
                {fmtDuration(node.durationMs)}
              </div>
            </div>
          );
        })}
        {flatNodes.length === 0 && (
          <div className="px-4 py-12 text-center text-sm text-gray-400 dark:text-gray-500">
            No spans recorded
          </div>
        )}
      </div>
    </div>
  );
}
