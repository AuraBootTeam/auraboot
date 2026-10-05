import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const helper = fileURLToPath(new URL('./isolated-release-network.sh', import.meta.url));
for (const scenario of ['available', 'overlap', 'permission', 'exhausted', 'empty-id']) {
  test(`isolated network allocation: ${scenario}`, () => {
    const root = mkdtempSync(join(tmpdir(), 'release-network-contract-'));
    try {
      const receipt = join(root, 'receipt.tsv');
      const calls = join(root, 'calls.log');
      const result = spawnSync('/bin/bash', ['-c', `
source "$1"
docker() {
  printf '%s\\n' "$*" >> "$CALLS"
  case "$SCENARIO:$4" in
    overlap:10.247.0.0/24|overlap:10.247.1.0/24|exhausted:*) echo 'Error response from daemon: Pool overlaps with other one on this address space'; return 1;;
    permission:*) echo 'permission denied connecting to the Docker daemon'; return 1;;
    empty-id:*) return 0;;
    *) printf '%064d\\n' 1;;
  esac
}
create_isolated_release_network owned-release-fixture "$2"
`, 'fixture', helper, receipt], { encoding: 'utf8', env: { ...process.env, SCENARIO: scenario, CALLS: calls } });
      const rows = readFileSync(calls, 'utf8').trim().split('\n');
      for (const row of rows) assert.match(row, /^network create --subnet 10\.247\.\d+\.0\/24 owned-release-fixture$/);
      if (scenario === 'available' || scenario === 'overlap') {
        assert.equal(result.status, 0, result.stderr);
        assert.equal(rows.length, scenario === 'available' ? 1 : 3);
        assert.match(readFileSync(receipt, 'utf8'), new RegExp(`owned-release-fixture\\t10\\.247\\.${scenario === 'available' ? 0 : 2}\\.0/24\\t[0-9a-f]{64}`));
      } else {
        assert.equal(result.status, 2, result.stderr);
        assert.equal(existsSync(receipt), false);
        assert.equal(rows.length, scenario === 'exhausted' ? 256 : 1);
      }
      assert.doesNotMatch(rows.join('\n'), /network rm|system prune/);
    } finally { rmSync(root, { recursive: true, force: true }); }
  });
}
