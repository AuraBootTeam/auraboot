// web-admin/app/plugins/core-designer/components/flow-designer-sdk/validation/diffGraphDocuments.ts
/**
 * Audit a pair of JSON documents (typically one Automation FlowData and one
 * BPMN BPMNProcessDefinition) for the 4 known grammar divergences listed in
 * docs/backlog/2026-05-23-unified-graph-grammar-spec.md §3 / §7.
 *
 * Used by `bin/validate-flow.mjs --diff` to make the spec's divergences
 * machine-checkable instead of prose-only.
 *
 *   D1  envelope          : missing schemaVersion / kind / meta wrapper
 *   D2  node-discriminator: data.type sub-discriminator still in use
 *   D3  edge-condition    : edge.data.condition is a bare string vs structured
 *   D4  meta-location     : process-level fields (key/name/...) scattered at
 *                           document root vs collected under `meta`
 */

export interface GrammarDivergence {
  /** D1 / D2 / D3 / D4 from the spec. */
  code: 'D1' | 'D2' | 'D3' | 'D4';
  /** automation | bpmn — which side exhibits the divergence. */
  side: 'a' | 'b';
  message: string;
  /** Best-effort JSON pointer (or human path). */
  path?: string;
  /** Up to ~120 char excerpt of the offending JSON. */
  evidence?: string;
}

export interface DiffReport {
  /** Detected document kind hints, purely for the human report header. */
  aKind: string;
  bKind: string;
  divergences: GrammarDivergence[];
}

function shortJson(v: unknown): string {
  try {
    const s = JSON.stringify(v);
    return s.length > 120 ? s.slice(0, 117) + '...' : s;
  } catch {
    return String(v);
  }
}

function detectKind(doc: Record<string, unknown>): string {
  if (typeof doc.kind === 'string') return doc.kind;
  // Heuristic: BPMNProcessDefinition has `key` + nodes with BPMN-ish types.
  const nodes = Array.isArray(doc.nodes) ? (doc.nodes as Array<Record<string, unknown>>) : [];
  const sample = nodes[0];
  const data = (sample?.data ?? {}) as Record<string, unknown>;
  if (typeof data.type === 'string') {
    const t = data.type as string;
    if (
      t === 'startEvent' ||
      t === 'endEvent' ||
      t === 'userTask' ||
      t === 'serviceTask' ||
      t.endsWith('Gateway')
    ) {
      return 'bpmn?';
    }
  }
  const topType = typeof sample?.type === 'string' ? (sample.type as string) : '';
  if (topType.startsWith('trigger-') || topType.startsWith('action-') || topType.startsWith('control-')) {
    return 'automation?';
  }
  return 'unknown';
}

function auditOne(
  doc: Record<string, unknown>,
  side: 'a' | 'b',
  divergences: GrammarDivergence[],
): void {
  // D1: envelope
  if (typeof doc.schemaVersion !== 'string') {
    divergences.push({
      code: 'D1',
      side,
      message: 'Missing GraphDocument envelope: schemaVersion is absent',
      path: '/schemaVersion',
    });
  }
  if (typeof doc.kind !== 'string') {
    divergences.push({
      code: 'D1',
      side,
      message: 'Missing GraphDocument envelope: kind is absent',
      path: '/kind',
    });
  }
  if (!doc.meta || typeof doc.meta !== 'object') {
    divergences.push({
      code: 'D1',
      side,
      message: 'Missing GraphDocument envelope: meta wrapper is absent',
      path: '/meta',
    });
  }

  // D4: meta-location (root-level process metadata that should live under meta)
  const ROOT_META_FIELDS = ['key', 'name', 'description', 'category', 'version', 'versionName'];
  for (const field of ROOT_META_FIELDS) {
    if (Object.prototype.hasOwnProperty.call(doc, field)) {
      divergences.push({
        code: 'D4',
        side,
        message: `Process-level field "${field}" at document root; spec §3.4 requires it inside meta.${field}`,
        path: `/${field}`,
        evidence: shortJson((doc as Record<string, unknown>)[field]),
      });
    }
  }

  const nodes = Array.isArray(doc.nodes) ? (doc.nodes as Array<Record<string, unknown>>) : [];
  for (let i = 0; i < nodes.length; i++) {
    const n = nodes[i];
    const data = (n.data ?? {}) as Record<string, unknown>;
    // D2: data.type sub-discriminator
    if (typeof data.type === 'string') {
      divergences.push({
        code: 'D2',
        side,
        message: `nodes[${i}] uses retired data.type sub-discriminator ("${data.type}"); spec §3.2 mandates the top-level node.type`,
        path: `/nodes/${i}/data/type`,
        evidence: shortJson(data.type),
      });
    }
  }

  const edges = Array.isArray(doc.edges) ? (doc.edges as Array<Record<string, unknown>>) : [];
  for (let i = 0; i < edges.length; i++) {
    const e = edges[i];
    const eData = (e.data ?? {}) as Record<string, unknown>;
    const condition = eData.condition;
    // D3: bare-string condition
    if (typeof condition === 'string') {
      divergences.push({
        code: 'D3',
        side,
        message: `edges[${i}].data.condition is a bare string; spec §3.5 mandates a structured ConditionExpression`,
        path: `/edges/${i}/data/condition`,
        evidence: shortJson(condition),
      });
    }
    // also detect the older shape where `condition` sat directly on the edge
    if (typeof (e as Record<string, unknown>).condition === 'string') {
      divergences.push({
        code: 'D3',
        side,
        message: `edges[${i}].condition (top-level) is a bare string; spec §3.5 mandates a structured ConditionExpression under edge.data.condition`,
        path: `/edges/${i}/condition`,
        evidence: shortJson((e as Record<string, unknown>).condition),
      });
    }
  }
}

export function diffGraphDocuments(a: unknown, b: unknown): DiffReport {
  const divergences: GrammarDivergence[] = [];
  const aObj = (a && typeof a === 'object' ? a : {}) as Record<string, unknown>;
  const bObj = (b && typeof b === 'object' ? b : {}) as Record<string, unknown>;
  auditOne(aObj, 'a', divergences);
  auditOne(bObj, 'b', divergences);
  return {
    aKind: detectKind(aObj),
    bKind: detectKind(bObj),
    divergences,
  };
}

export default diffGraphDocuments;
