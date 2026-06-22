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

test('inventories legacy targetRecordId config without targetRecordPid', () => {
  const root = makeRepo();
  writeJson(root, 'plugins/demo/config/pages/order_list.json', {
    blocks: [
      {
        type: 'record-table',
        action: {
          command: 'demo:update_order',
          targetRecordId: '${recordId}',
        },
      },
    ],
  });

  const result = auditRepo(root, { ossOnly: true, baselinePath: null });

  assert.ok(codes(result).includes('S-PUBLIC-RECORD-TARGET-ID-LEGACY'));
  assert.ok(codes(result).includes('S-PUBLIC-RECORD-PLACEHOLDER-LEGACY'));
  assert.equal(result.summary.total, 2);
  assert.equal(result.summary.new, 2);
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

test('inventories named query internal-field bypass risk without blanket matching business ids', () => {
  const root = makeRepo();
  writeJson(root, 'plugins/demo/config/named-queries/orders.json', {
    safeQuery: 'select external_order_id, material_id from mt_order',
    riskyQuery: 'select id, tenant_id, created_by, name from mt_order',
  });

  const result = auditRepo(root, { ossOnly: true, baselinePath: null });
  const sqlFindings = result.findings.filter((finding) => finding.code === 'S-PUBLIC-RECORD-SQL-INTERNAL-FIELD-RISK');

  assert.equal(sqlFindings.length, 1);
  assert.equal(sqlFindings[0].jsonPath, '$.riskyQuery');
});
