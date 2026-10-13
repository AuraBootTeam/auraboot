import type { APIRequestContext } from '@playwright/test';
import { lstatSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { isIP } from 'node:net';

export type FixtureRow = { model: string; pid: string };
type Entry = FixtureRow & { status: 'pending' | 'removed' | 'failed'; error?: string };

function validateRow(row: FixtureRow): void {
  if (!/^[A-Za-z0-9_]+$/.test(row.model) || !/^[A-Za-z0-9_-]+$/.test(row.pid)) {
    throw new Error('Invalid fixture identity');
  }
}

export class FixtureCleanupJournal {
  private entries: Entry[] = [];
  private snapshotPending = false;
  readonly origin: string;

  constructor(private readonly file: string, origin: string) {
    const target = new URL(origin);
    const host = target.hostname.replace(/^\[|\]$/g, '');
    const privateV4 = isIP(host) === 4 && /^(127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(host);
    if (target.protocol !== 'http:' || (!privateV4 && !['localhost', '::1'].includes(host))
        || target.username || target.password) {
      throw new Error('Fixture cleanup requires an explicit nonproduction HTTP target');
    }
    if (!path.isAbsolute(file)) throw new Error('Fixture journal requires an absolute evidence path');
    this.origin = target.origin;
  }

  track(row: FixtureRow): void {
    validateRow(row);
    if (!this.entries.some(entry => entry.model === row.model && entry.pid === row.pid)) {
      this.entries.push({ ...row, status: 'pending' });
      this.persist();
    }
  }

  static resume(file: string, expectedOrigin: string): FixtureCleanupJournal {
    const journal = new FixtureCleanupJournal(file, expectedOrigin);
    if (!lstatSync(file).isFile() || lstatSync(file).isSymbolicLink()) {
      throw new Error('Fixture journal must be a regular evidence file');
    }
    const data = JSON.parse(readFileSync(file, 'utf8'));
    if (![1, 2].includes(data.schemaVersion) || data.origin !== journal.origin || !Array.isArray(data.entries)) {
      throw new Error('Fixture journal target/schema mismatch');
    }
    const identities = new Set<string>();
    journal.entries = data.entries.map((row: Entry) => {
      validateRow(row);
      const key = `${row.model}:${row.pid}`;
      if (identities.has(key) || !['pending', 'removed', 'failed'].includes(row.status)) {
        throw new Error('Invalid fixture journal entry');
      }
      identities.add(key);
      return { model: row.model, pid: row.pid, status: row.status };
    });
    if (data.pending !== journal.entries.filter(row => row.status !== 'removed').length) {
      throw new Error('Fixture journal pending count mismatch');
    }
    if (data.schemaVersion === 2 && typeof data.snapshotPending !== 'boolean') {
      throw new Error('Fixture journal snapshot state missing');
    }
    journal.snapshotPending = data.schemaVersion === 2 ? data.snapshotPending
      : journal.entries.some(row => row.model === 'bom_material_master' && row.status === 'removed');
    return journal;
  }

  private persist(): void {
    mkdirSync(path.dirname(this.file), { recursive: true });
    const temporary = `${this.file}.${process.pid}.tmp`;
    writeFileSync(temporary, JSON.stringify({ schemaVersion: 2, origin: this.origin,
      snapshotPending: this.snapshotPending,
      pending: this.entries.filter(entry => entry.status !== 'removed').length,
      entries: this.entries }, null, 2) + '\n', { mode: 0o600 });
    renameSync(temporary, this.file);
  }

  async cleanup(request: APIRequestContext, rows?: FixtureRow[]): Promise<void> {
    const selected = rows ? new Set(rows.map(row => `${row.model}:${row.pid}`)) : null;
    if (selected && rows?.some(row => !this.entries.some(entry => entry.model === row.model && entry.pid === row.pid))) {
      throw new Error('Cleanup refused an unregistered fixture');
    }
    const failures: string[] = [];
    for (const entry of [...this.entries].reverse()) {
      if (entry.status === 'removed' || (selected && !selected.has(`${entry.model}:${entry.pid}`))) continue;
      if (entry.model === 'bom_material_master') {
        this.snapshotPending = true;
        this.persist();
      }
      try {
        const prefix = `${this.origin}/api/dynamic/${entry.model}`;
        const deletion = await request.delete(`${prefix}/${entry.pid}`, {
          timeout: 15_000, headers: { Origin: this.origin },
        });
        const deleted = await deletion.json();
        if (!deletion.ok() || String(deleted.code) !== '0') {
          throw new Error(`Delete rejected: HTTP ${deletion.status()}, code=${deleted.code}`);
        }
        const filters = encodeURIComponent(JSON.stringify([{ fieldName: 'pid', operator: 'EQ', value: entry.pid }]));
        const check = await request.get(`${prefix}/list?pageNum=1&pageSize=2&filters=${filters}`, { timeout: 15_000 });
        const body = await check.json();
        const records = body?.data?.records;
        if (!check.ok() || String(body.code) !== '0' || !Array.isArray(records)) {
          throw new Error('Fixture absence query failed');
        }
        if (records.length !== 0) throw new Error('Fixture remains visible after deletion');
        entry.status = 'removed';
        delete entry.error;
      } catch (error) {
        entry.status = 'failed';
        // Keep only the failure class; request errors may include sensitive URLs.
        entry.error = error instanceof Error && error.message.startsWith('Fixture') ? error.message : 'Fixture deletion failed';
        failures.push(`${entry.model}:${entry.pid}`);
      }
      this.persist();
    }
    if (this.snapshotPending) {
      try {
        const response = await request.post(`${this.origin}/api/meta/commands/execute/bom:refresh_material_snapshot`, {
          data: { payload: {}, operationType: 'update' }, headers: { Origin: this.origin }, timeout: 60_000,
        });
        const body = await response.json();
        const result = body?.data?.data ?? body?.data;
        if (!response.ok() || String(body.code) !== '0' || result?.success !== true) {
          throw new Error('Material snapshot refresh rejected');
        }
        this.snapshotPending = false;
      } catch {
        failures.push('material-snapshot');
      }
      this.persist();
    }
    if (failures.length) throw new Error(`Fixture cleanup failed for ${failures.join(', ')}`);
  }
}
