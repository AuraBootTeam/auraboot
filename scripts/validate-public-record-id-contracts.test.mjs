import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  auditRepo,
  createBaseline,
} from './validate-public-record-id-contracts.mjs';

function makeRepo() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'public-record-id-'));
}

function write(root, relPath, content) {
  const abs = path.join(root, relPath);
  fs.mkdirSync(path.dirname(abs), { recursive: true });
  fs.writeFileSync(abs, content);
  return abs;
}

function writeJson(root, relPath, value) {
  return write(root, relPath, JSON.stringify(value, null, 2));
}

function codes(result) {
  return result.findings.map((finding) => finding.code);
}

test('inventories legacy public record identity config keys', () => {
  const root = makeRepo();
  writeJson(root, 'plugins/demo/config/pages/order_list.json', {
    blocks: [
      {
        type: 'record-table',
        action: {
          command: 'demo:update_order',
          targetRecordId: '${recordId}',
          targetRecordPid: '${recordPid}',
        },
        task: {
          recordIdVar: 'recordId',
          recordIdField: 'source_id',
        },
      },
    ],
  });

  const result = auditRepo(root, { ossOnly: true, baselinePath: null });

  assert.ok(codes(result).includes('S-PUBLIC-RECORD-TARGET-ID-LEGACY'));
  assert.ok(codes(result).includes('S-PUBLIC-RECORD-CONFIG-LEGACY-KEY'));
  assert.ok(codes(result).includes('S-PUBLIC-RECORD-PLACEHOLDER-LEGACY'));
  assert.equal(result.summary.total, 4);
  assert.equal(result.summary.new, 4);
});

test('baseline accepts current findings while still failing newly introduced leaks', () => {
  const root = makeRepo();
  writeJson(root, 'plugins/demo/config/pages/current.json', {
    action: { targetRecordId: '${recordId}' },
  });
  const initial = auditRepo(root, { ossOnly: true, baselinePath: null });
  const baselinePath = path.join(root, 'scripts/public-record-id-baseline.json');
  write(root, 'scripts/.keep', '');
  fs.writeFileSync(baselinePath, JSON.stringify(createBaseline(initial.findings), null, 2));

  writeJson(root, 'plugins/demo/config/pages/new.json', {
    action: { targetRecordId: '$record.id' },
  });
  const result = auditRepo(root, { ossOnly: true, baselinePath });

  assert.equal(result.summary.accepted, initial.findings.length);
  assert.equal(result.summary.new, 2);
  assert.equal(result.exitCode, 1);
  assert.ok(result.newFindings.every((finding) => finding.file.endsWith('new.json')));
});

test('inventories backend public record-id and dynamic map response risks', () => {
  const root = makeRepo();
  write(root, 'platform/src/main/java/com/acme/DynamicController.java', `
    @RestController
    class DynamicController {
      @GetMapping("/{pageKey}/{recordId}")
      ResponseEntity<Map<String, Object>> get(@PathVariable Long recordId) {
        return null;
      }
    }
  `);

  const result = auditRepo(root, { ossOnly: true, baselinePath: null });

  assert.ok(codes(result).includes('S-PUBLIC-RECORD-JAVA-CONTROLLER-LEGACY'));
  assert.ok(codes(result).includes('S-PUBLIC-RECORD-DYNAMIC-MAP-RISK'));
});

test('does not inventory sanitized dynamic controller record maps', () => {
  const root = makeRepo();
  write(root, 'platform/src/main/java/com/acme/DynamicController.java', `
    @RestController
    class DynamicController {
      @GetMapping("/{pageKey}/{recordPid}")
      ApiResponse<Map<String, Object>> get(@PathVariable String recordPid) {
        Map<String, Object> result = dynamicDataService.getById("order", recordPid);
        return ApiResponse.success(PublicRecordSanitizer.sanitizeRecord(result));
      }

      @GetMapping("/{pageKey}/list")
      ApiResponse<PaginationResult<Map<String, Object>>> list(String cursor) {
        PaginationResult<Map<String, Object>> result = dynamicDataService.list("order", request);
        return ApiResponse.success(PublicRecordSanitizer.sanitizePage(result));
      }
    }
  `);

  const result = auditRepo(root, { ossOnly: true, baselinePath: null });

  assert.ok(!codes(result).includes('S-PUBLIC-RECORD-DYNAMIC-MAP-RISK'));
});

test('inventories named query internal-field bypass risk without blanket matching business ids', () => {
  const root = makeRepo();
  writeJson(root, 'plugins/demo/config/named-queries/orders.json', {
    safeQuery: 'select external_order_id, material_id from mt_order',
    safeTenantFilteredQuery: 'select pid, order_name from mt_order where tenant_id = #{params.tenantId} order by created_at desc',
    riskyQuery: 'select id, tenant_id, created_by, name from mt_order',
  });

  const result = auditRepo(root, { ossOnly: true, baselinePath: null });
  const sqlFindings = result.findings.filter((finding) => finding.code === 'S-PUBLIC-RECORD-SQL-INTERNAL-FIELD-RISK');

  assert.equal(sqlFindings.length, 1);
  assert.equal(sqlFindings[0].jsonPath, '$.riskyQuery');
});

test('inventories public response fixtures that expose legacy record identity keys', () => {
  const root = makeRepo();
  writeJson(root, 'docs/api-fixtures/public-record/dynamic-list.json', {
    code: 0,
    data: {
      records: [
        {
          pid: 'rec-pid-1',
          recordId: 1001,
        },
      ],
    },
  });

  const result = auditRepo(root, { ossOnly: true, baselinePath: null });
  const responseFindings = result.findings.filter((finding) =>
    finding.code === 'S-PUBLIC-RECORD-RESPONSE-LEGACY-KEY');

  assert.equal(responseFindings.length, 1);
  assert.equal(responseFindings[0].jsonPath, '$.data.records[0].recordId');
});

test('inventories frontend public config defaults that expose recordIdVar', () => {
  const root = makeRepo();
  write(root, 'web-admin/app/plugins/core-designer/components/bpmn-designer/constants/index.ts', `
    export const defaults = {
      recordUpdateTask: {
        modelCode: '',
        recordIdVar: 'businessKey',
      },
    };
  `);

  const result = auditRepo(root, { ossOnly: true, baselinePath: null });
  const findings = result.findings.filter((finding) =>
    finding.code === 'S-PUBLIC-RECORD-FRONTEND-LEGACY');

  assert.equal(findings.length, 1);
  assert.equal(findings[0].field, 'recordIdVar');
});
