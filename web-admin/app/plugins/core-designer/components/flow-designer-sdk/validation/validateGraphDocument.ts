// web-admin/app/plugins/core-designer/components/flow-designer-sdk/validation/validateGraphDocument.ts
/**
 * Structural validator for the unified GraphDocument grammar
 * (`docs/backlog/2026-05-23-unified-graph-grammar-spec.md`).
 *
 * Two-stage check:
 *   1. JSON Schema (graphDocumentSchema) — shape / required / enums.
 *   2. Cross-field semantic rules from spec §6:
 *      - node.id / edge.id document-wide uniqueness
 *      - every edge.source/target points at an existing node.id
 *      - exactly one start-class node (kind=automation: a `trigger-*` node;
 *        kind=bpmn: a `startEvent` node)
 *      - exclusive/inclusive gateway out-edges: each carries a condition
 *        OR exactly one isDefault per gateway
 *
 * This validator is i18n-agnostic, side-effect free, and intended for both
 * the SDK runtime (designer save gate) and the CLI lint tool.
 */
import Ajv, { type ErrorObject, type ValidateFunction } from 'ajv';
import { graphDocumentSchema } from './graphDocumentSchema';

export interface GraphDocumentValidationError {
  /** Stable error code so consumers (CLI, UI) can switch on it. */
  code: string;
  /** Human-readable description (English; UI may map to i18n). */
  message: string;
  /** JSON Pointer (RFC6901) to the offending value, when available. */
  path?: string;
  /** Convenience IDs when the offender is a node/edge. */
  nodeId?: string;
  edgeId?: string;
}

export interface GraphDocumentValidationResult {
  valid: boolean;
  errors: GraphDocumentValidationError[];
}

const ajv = new Ajv({ allErrors: true });
let compiled: ValidateFunction | null = null;
function getValidator(): ValidateFunction {
  if (!compiled) compiled = ajv.compile(graphDocumentSchema);
  return compiled;
}

function ajvPath(err: ErrorObject): string {
  const compat = err as ErrorObject & { instancePath?: string; dataPath?: string };
  return compat.instancePath || compat.dataPath || '/';
}

function ajvErrorToCode(err: ErrorObject): string {
  // Map a few well-known ajv keywords to stable codes; everything else falls
  // through to GRAPH-SCHEMA.<keyword> so we never lose information.
  switch (err.keyword) {
    case 'required':
      return 'GRAPH-SCHEMA.MISSING_REQUIRED';
    case 'additionalProperties':
      return 'GRAPH-SCHEMA.UNKNOWN_PROPERTY';
    case 'enum':
      return 'GRAPH-SCHEMA.ENUM_MISMATCH';
    case 'const':
      return 'GRAPH-SCHEMA.CONST_MISMATCH';
    case 'type':
      return 'GRAPH-SCHEMA.TYPE_MISMATCH';
    case 'not':
      return 'GRAPH-SCHEMA.DEPRECATED_FIELD';
    case 'oneOf':
      return 'GRAPH-SCHEMA.ONEOF_FAIL';
    default:
      return `GRAPH-SCHEMA.${err.keyword.toUpperCase()}`;
  }
}

function ajvErrorToMessage(err: ErrorObject): string {
  if (err.keyword === 'not' && ajvPath(err).endsWith('/data')) {
    return 'data.type sub-discriminator is retired by grammar spec §3.2 (use top-level node.type)';
  }
  if (err.keyword === 'required') {
    const missing = (err.params as { missingProperty?: string })?.missingProperty;
    return `Missing required property: ${missing}`;
  }
  if (err.keyword === 'additionalProperties') {
    const extra = (err.params as { additionalProperty?: string })?.additionalProperty;
    return `Unknown property: ${extra}`;
  }
  return err.message ?? 'Schema validation failed';
}

interface NodeShape {
  id: string;
  type: string;
}
interface EdgeShape {
  id: string;
  source: string;
  target: string;
  data?: { condition?: unknown; isDefault?: boolean };
}

function isGatewayType(kind: string, nodeType: string): boolean {
  if (kind === 'bpmn') {
    return nodeType === 'exclusiveGateway' || nodeType === 'inclusiveGateway';
  }
  // automation: control-condition compiles to exclusiveGateway per spec §4.2.
  return nodeType === 'control-condition';
}

function isStartType(kind: string, nodeType: string): boolean {
  if (kind === 'bpmn') return nodeType === 'startEvent';
  return nodeType.startsWith('trigger-');
}

function runSemanticChecks(
  doc: Record<string, unknown>,
  errors: GraphDocumentValidationError[],
): void {
  const kind = String(doc.kind);
  const nodes = (doc.nodes as NodeShape[]) ?? [];
  const edges = (doc.edges as EdgeShape[]) ?? [];

  // 1. ID uniqueness
  const seenNodeIds = new Set<string>();
  for (const n of nodes) {
    if (seenNodeIds.has(n.id)) {
      errors.push({
        code: 'GRAPH-SEMANTIC.DUPLICATE_NODE_ID',
        message: `Duplicate node id: ${n.id}`,
        nodeId: n.id,
      });
    }
    seenNodeIds.add(n.id);
  }
  const seenEdgeIds = new Set<string>();
  for (const e of edges) {
    if (seenEdgeIds.has(e.id)) {
      errors.push({
        code: 'GRAPH-SEMANTIC.DUPLICATE_EDGE_ID',
        message: `Duplicate edge id: ${e.id}`,
        edgeId: e.id,
      });
    }
    seenEdgeIds.add(e.id);
  }

  // 2. Edge endpoint integrity
  for (const e of edges) {
    if (!seenNodeIds.has(e.source)) {
      errors.push({
        code: 'GRAPH-SEMANTIC.EDGE_SOURCE_NOT_FOUND',
        message: `Edge ${e.id}.source references missing node: ${e.source}`,
        edgeId: e.id,
      });
    }
    if (!seenNodeIds.has(e.target)) {
      errors.push({
        code: 'GRAPH-SEMANTIC.EDGE_TARGET_NOT_FOUND',
        message: `Edge ${e.id}.target references missing node: ${e.target}`,
        edgeId: e.id,
      });
    }
  }

  // 3. Exactly one start node
  const startNodes = nodes.filter((n) => isStartType(kind, n.type));
  if (startNodes.length === 0) {
    errors.push({
      code: 'GRAPH-SEMANTIC.NO_START_NODE',
      message:
        kind === 'bpmn'
          ? 'No startEvent node found (spec §6.3)'
          : 'No trigger-* node found (spec §6.3)',
    });
  } else if (startNodes.length > 1) {
    errors.push({
      code: 'GRAPH-SEMANTIC.MULTIPLE_START_NODES',
      message: `Expected exactly 1 start node, found ${startNodes.length} (spec §6.3)`,
    });
  }

  // 4. Gateway out-edges must carry a condition OR isDefault; ≤ 1 default per gateway.
  const gateways = nodes.filter((n) => isGatewayType(kind, n.type));
  for (const g of gateways) {
    const outs = edges.filter((e) => e.source === g.id);
    if (outs.length === 0) continue;
    let defaultCount = 0;
    for (const out of outs) {
      const hasCondition = !!(out.data && out.data.condition);
      const isDefault = !!(out.data && out.data.isDefault);
      if (isDefault) defaultCount += 1;
      if (!hasCondition && !isDefault) {
        errors.push({
          code: 'GRAPH-SEMANTIC.GATEWAY_EDGE_MISSING_CONDITION',
          message: `Gateway ${g.id} out-edge ${out.id} is missing both condition and isDefault (spec §6.4)`,
          nodeId: g.id,
          edgeId: out.id,
        });
      }
    }
    if (defaultCount > 1) {
      errors.push({
        code: 'GRAPH-SEMANTIC.GATEWAY_MULTIPLE_DEFAULTS',
        message: `Gateway ${g.id} has ${defaultCount} default out-edges; at most 1 allowed (spec §6.4)`,
        nodeId: g.id,
      });
    }
  }
}

/**
 * Validate a candidate GraphDocument against the spec. Always returns a
 * result (never throws) — `valid` is `true` only if both schema and semantic
 * checks pass.
 */
export function validateGraphDocument(doc: unknown): GraphDocumentValidationResult {
  const errors: GraphDocumentValidationError[] = [];

  if (doc === null || typeof doc !== 'object' || Array.isArray(doc)) {
    return {
      valid: false,
      errors: [
        {
          code: 'GRAPH-SCHEMA.NOT_AN_OBJECT',
          message: 'GraphDocument must be a JSON object',
        },
      ],
    };
  }

  const validate = getValidator();
  const ok = validate(doc);
  if (!ok && validate.errors) {
    for (const err of validate.errors) {
      errors.push({
        code: ajvErrorToCode(err),
        message: ajvErrorToMessage(err),
        path: ajvPath(err),
      });
    }
  }

  // Only run semantic checks when basic shape is roughly right (has nodes/edges
  // arrays + a recognisable kind). Otherwise schema errors are already the
  // signal and semantic noise would just confuse the user.
  const d = doc as Record<string, unknown>;
  if (Array.isArray(d.nodes) && Array.isArray(d.edges) && typeof d.kind === 'string') {
    runSemanticChecks(d, errors);
  }

  return { valid: errors.length === 0, errors };
}

export default validateGraphDocument;
