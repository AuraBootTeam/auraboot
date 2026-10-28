import { expect } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { execFileSync, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fixture, evidence, record } from './qualification-support';

const execute = promisify(execFile);
async function workspace(args: string[], name: string) {
  const result = await execute(join(fixture.runtime.workspace, 'aura'), args, {
    cwd: fixture.runtime.workspace,
    timeout: 300_000,
    maxBuffer: 8 * 1024 * 1024,
  });
  writeFileSync(join(evidence, name + '.log'), result.stdout + result.stderr, { mode: 0o600 });
  return result.stdout;
}
export async function interruptBackend(name: string) {
  await workspace(
    ['runtime', 'process', 'check', fixture.runtime.name, 'backend'],
    name + '-ownership',
  );
  const row = readFileSync(join(fixture.runtime.processDirectory, 'backend.tsv'), 'utf8')
    .trimEnd()
    .split('\t');
  expect(row).toHaveLength(7);
  expect(row[0]).toBe('backend');
  const pid = Number(row[1]);
  expect(Number.isSafeInteger(pid) && pid > 1).toBe(true);
  const command = execFileSync('ps', ['-ww', '-p', String(pid), '-o', 'command='], {
    encoding: 'utf8',
  }).trim();
  expect(command).toContain(fixture.runtime.bundle + '/backend/app.jar');
  expect(command).toContain(' -jar ');
  const cwd = execFileSync('lsof', ['-a', '-p', String(pid), '-d', 'cwd', '-Fn'], {
    encoding: 'utf8',
  });
  expect(cwd).toContain(fixture.runtime.name + '/work/agent-product/backend');
  record(name + '-before-sigkill', {
    pid,
    command,
    cwd,
    signal: 'SIGKILL',
    bundleSha256: fixture.bundleSha256,
  });
  // This is the owned product JVM, not a provider exception or graceful shutdown.
  process.kill(pid, 'SIGKILL');
  await expect
    .poll(() => {
      try {
        return execFileSync('ps', ['-p', String(pid), '-o', 'stat='], { encoding: 'utf8' })
          .trim()
          .startsWith('Z');
      } catch {
        return true;
      }
    })
    .toBe(true);
  return pid;
}
export async function restore(name: string, previous: number) {
  await workspace(['runtime', 'suspend', fixture.runtime.name], name + '-retaining-suspend');
  await workspace(['runtime', 'resume', fixture.runtime.name], name + '-resume');
  const row = readFileSync(join(fixture.runtime.processDirectory, 'backend.tsv'), 'utf8').split(
    '\t',
  );
  expect(Number(row[1])).not.toBe(previous);
  await workspace(['runtime', 'verify', fixture.runtime.name, '--json'], name + '-verified');
  return Number(row[1]);
}
