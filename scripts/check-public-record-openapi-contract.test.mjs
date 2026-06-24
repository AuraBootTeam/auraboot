import test from 'node:test';
import assert from 'node:assert/strict';
import { auditOpenApi } from './check-public-record-openapi-contract.mjs';

test('flags legacy record identity keys on public record OpenAPI paths', () => {
  const result = auditOpenApi({
    paths: {
      '/api/dynamic/{pageKey}/{recordPid}': {
        get: {
          parameters: [
            { name: 'pageKey', schema: { type: 'string' } },
            { name: 'recordId', schema: { type: 'string' } },
          ],
          responses: {
            200: {
              content: {
                'application/json': {
                  schema: {
                    type: 'object',
                    properties: {
                      data: {
                        type: 'object',
                        properties: {
                          targetRecordId: { type: 'string' },
                        },
                      },
                    },
                  },
                },
              },
            },
          },
        },
      },
    },
  });

  assert.equal(result.scopedPathCount, 1);
  assert.equal(result.findingCount, 2);
  assert.deepEqual(result.findings.map((finding) => finding.field).sort(), [
    'recordId',
    'targetRecordId',
  ]);
});

test('does not flag generic resource ids on scoped non-record resources', () => {
  const result = auditOpenApi({
    paths: {
      '/api/email/messages/{id}/link/{linkId}': {
        delete: {
          parameters: [
            { name: 'id', schema: { type: 'integer' } },
            { name: 'linkId', schema: { type: 'integer' } },
          ],
          responses: {
            200: {
              content: {
                'application/json': {
                  schema: {
                    type: 'object',
                    properties: {
                      data: {
                        type: 'object',
                        properties: {
                          messagePid: { type: 'string' },
                          recordPid: { type: 'string' },
                        },
                      },
                    },
                  },
                },
              },
            },
          },
        },
      },
    },
  });

  assert.equal(result.scopedPathCount, 1);
  assert.equal(result.findingCount, 0);
});

test('resolves component schemas when scanning scoped responses', () => {
  const result = auditOpenApi({
    paths: {
      '/api/records/{modelCode}/{recordPid}/capabilities': {
        get: {
          responses: {
            200: {
              content: {
                'application/json': {
                  schema: { $ref: '#/components/schemas/PublicRecordCapability' },
                },
              },
            },
          },
        },
      },
    },
    components: {
      schemas: {
        PublicRecordCapability: {
          type: 'object',
          properties: {
            recordPid: { type: 'string' },
            tenant_id: { type: 'integer' },
          },
        },
      },
    },
  });

  assert.equal(result.findingCount, 1);
  assert.equal(result.findings[0].field, 'tenant_id');
});

test('flags legacy record identity keys in component schemas even outside scoped paths', () => {
  const result = auditOpenApi({
    paths: {
      '/api/internal/audit': {
        get: {
          responses: {
            200: {
              content: {
                'application/json': {
                  schema: { $ref: '#/components/schemas/InternalAuditLog' },
                },
              },
            },
          },
        },
      },
    },
    components: {
      schemas: {
        InternalAuditLog: {
          type: 'object',
          properties: {
            targetRecordId: { type: 'string' },
          },
        },
      },
    },
  });

  assert.equal(result.scopedPathCount, 0);
  assert.equal(result.findingCount, 1);
  assert.equal(result.findings[0].kind, 'schema-property');
  assert.equal(result.findings[0].path, '#/components/schemas/InternalAuditLog');
  assert.equal(result.findings[0].field, 'targetRecordId');
});
