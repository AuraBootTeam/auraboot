// web-admin/app/plugins/core-designer/components/flow-designer-sdk/validation/graphDocumentSchema.ts
/**
 * JSON Schema for the Unified Graph Document grammar shared by the Automation
 * and BPMN designers. Source of truth:
 *   docs/backlog/2026-05-23-unified-graph-grammar-spec.md (§3, §6)
 *
 * Scope:
 * - Structural validation only (shape, required fields, enums, ID uniqueness
 *   via custom check, edge endpoint integrity via custom check).
 * - Domain config payloads (data.config) are intentionally permissive at the
 *   schema layer; per-node-type config schemas live in each domain's
 *   NodeRegistry and are validated by validateFlow() at save time.
 *
 * Stability: the schema's `$id` is versioned via schemaVersion=1.0 and any
 * breaking grammar change must bump it.
 */

export const GRAPH_DOCUMENT_SCHEMA_VERSION = '1.0' as const;

/**
 * Plain JSON Schema (draft-07 compatible, ajv default). Kept as a literal
 * object (not `as const`) so ajv compileAsync/compile accepts it without
 * generic gymnastics.
 */
export const graphDocumentSchema = {
  $id: 'https://auraboot.dev/schemas/graph-document-1.0.json',
  $schema: 'http://json-schema.org/draft-07/schema#',
  title: 'GraphDocument',
  description:
    'Unified flow/BPMN graph document. Consumed by JsonToBpmnConverter (backend) and shared by both designers.',
  type: 'object',
  required: ['schemaVersion', 'kind', 'meta', 'nodes', 'edges'],
  additionalProperties: false,
  properties: {
    schemaVersion: {
      type: 'string',
      const: GRAPH_DOCUMENT_SCHEMA_VERSION,
    },
    kind: {
      type: 'string',
      enum: ['automation', 'bpmn'],
    },
    meta: { $ref: '#/definitions/GraphMeta' },
    nodes: {
      type: 'array',
      items: { $ref: '#/definitions/Node' },
    },
    edges: {
      type: 'array',
      items: { $ref: '#/definitions/Edge' },
    },
  },
  definitions: {
    LocalizedText: {
      oneOf: [
        { type: 'string', minLength: 1 },
        {
          type: 'object',
          additionalProperties: { type: 'string' },
          minProperties: 1,
        },
      ],
    },
    Position: {
      type: 'object',
      required: ['x', 'y'],
      additionalProperties: false,
      properties: {
        x: { type: 'number' },
        y: { type: 'number' },
      },
    },
    GraphMeta: {
      type: 'object',
      required: ['key', 'name'],
      additionalProperties: true,
      properties: {
        key: { type: 'string', minLength: 1 },
        name: { $ref: '#/definitions/LocalizedText' },
        description: { type: 'string' },
        category: { type: 'string' },
        version: { type: 'integer', minimum: 0 },
        versionName: { type: 'string' },
        variables: { type: 'object' },
        aura: { type: 'object' },
        automation: {
          type: 'object',
          required: ['trigger'],
          additionalProperties: false,
          properties: {
            trigger: {
              type: 'object',
              required: ['type'],
              additionalProperties: true,
              properties: {
                type: { type: 'string', minLength: 1 },
                modelCode: { type: 'string' },
                config: { type: 'object' },
              },
            },
          },
        },
      },
    },
    Node: {
      type: 'object',
      required: ['id', 'type', 'position', 'data'],
      additionalProperties: true,
      properties: {
        id: { type: 'string', minLength: 1 },
        type: { type: 'string', minLength: 1 },
        position: { $ref: '#/definitions/Position' },
        parentId: { type: 'string' },
        data: {
          type: 'object',
          required: ['label', 'config'],
          additionalProperties: true,
          properties: {
            label: { $ref: '#/definitions/LocalizedText' },
            config: { type: 'object' },
          },
          // Spec §3.2 explicitly retires data.type after the grammar
          // unification — node discrimination uses the top-level node.type.
          not: { required: ['type'] },
        },
      },
    },
    Edge: {
      type: 'object',
      required: ['id', 'source', 'target'],
      additionalProperties: true,
      properties: {
        id: { type: 'string', minLength: 1 },
        source: { type: 'string', minLength: 1 },
        target: { type: 'string', minLength: 1 },
        sourceHandle: { type: 'string' },
        targetHandle: { type: 'string' },
        data: {
          type: 'object',
          additionalProperties: true,
          properties: {
            label: { $ref: '#/definitions/LocalizedText' },
            condition: {
              oneOf: [
                { type: 'null' },
                { $ref: '#/definitions/ConditionExpression' },
              ],
            },
            isDefault: { type: 'boolean' },
          },
        },
      },
    },
    ConditionExpression: {
      type: 'object',
      required: ['type', 'content'],
      additionalProperties: false,
      properties: {
        type: { type: 'string', enum: ['expression', 'script'] },
        content: { type: 'string', minLength: 1 },
        language: { type: 'string', enum: ['mvel', 'juel', 'spel'] },
        ruleCode: { type: 'string' },
      },
    },
  },
} as const;

export default graphDocumentSchema;
