import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, symlinkSync, writeFileSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { spawnSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import * as ts from 'typescript';
import { writeAuraMcpConfig } from '../../src/mcp/mcp-config.js';

let root: string;
beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), 'aura-mcpcfg-'));
});
afterEach(() => {
  rmSync(root, { recursive: true, force: true });
});

describe('writeAuraMcpConfig', () => {
  it.each(['creation-symlink', 'creation-regular', 'after-open-symlink'])(
    'cannot redirect config bytes through a real filesystem race: %s', (mode) => {
      const file = join(root, '.mcp.json');
      const foreign = join(root, 'foreign.json');
      const original = '{"mcpServers":{"preserved":{"command":"preserved"}}}';
      writeFileSync(foreign, original, { mode: 0o600 });
      if (mode === 'after-open-symlink') writeFileSync(file, original, { mode: 0o600 });
      const source = readFileSync(fileURLToPath(new URL('../../src/mcp/mcp-config.ts', import.meta.url)), 'utf8');
      const compiled = join(root, 'actual-config.mjs');
      writeFileSync(compiled, ts.transpileModule(source, { compilerOptions: {
        module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022,
      } }).outputText);
      const probe = join(root, 'probe.mjs');
      writeFileSync(probe, `
        import fs from 'node:fs';
        import { syncBuiltinESMExports } from 'node:module';
        const file=${JSON.stringify(file)}, foreign=${JSON.stringify(foreign)}, mode=${JSON.stringify(mode)};
        const open=fs.openSync, fstat=fs.fstatSync;
        let selected, injected=false;
        fs.openSync=(target,...args)=>{
          try {const fd=open(target,...args);if(target===file)selected=fd;return fd;}
          catch(error){
            if(target===file && error.code==='ENOENT' && !injected){
              injected=true;
              if(mode==='creation-symlink')fs.symlinkSync(foreign,file);
              else fs.writeFileSync(file,fs.readFileSync(foreign),{mode:0o600});
            }
            throw error;
          }
        };
        fs.fstatSync=(fd,...args)=>{
          const info=fstat(fd,...args);
          if(fd===selected && mode==='after-open-symlink' && !injected){
            injected=true;fs.renameSync(file,file+'.original');fs.symlinkSync(foreign,file);
          }
          return info;
        };
        syncBuiltinESMExports();
        const {writeAuraMcpConfig}=await import(${JSON.stringify(pathToFileURL(compiled).href)});
        let result, failed=false;
        try{result=writeAuraMcpConfig(${JSON.stringify(root)},'claude');}catch{failed=true;}
        console.log(JSON.stringify({injected,failed,result,foreign:fs.readFileSync(foreign,'utf8'),
          selected:fs.readFileSync(mode==='after-open-symlink'?file+'.original':file,'utf8')}));
      `);
      const child = spawnSync(process.execPath, [probe], { encoding: 'utf8', timeout: 10000 });
      expect(child.status, child.stderr).toBe(0);
      const outcome = JSON.parse(child.stdout);
      expect(outcome.injected).toBe(true);
      expect(outcome.foreign).toBe(original);
      expect(outcome.failed).toBe(mode === 'creation-symlink');
      if (!outcome.failed) {
        expect(outcome.result.action).toBe('updated');
        expect(JSON.parse(outcome.selected).mcpServers).toEqual({
          preserved: { command: 'preserved' }, auraboot: { command: 'aura', args: ['mcp', 'serve'] },
        });
      }
    },
  );

  it.each(['invalid JSON', '[]', 'null', '{"mcpServers":[]}'])('preserves invalid existing config: %s', (contents) => {
    const file = join(root, '.mcp.json');
    writeFileSync(file, contents);
    expect(() => writeAuraMcpConfig(root, 'claude')).toThrow();
    expect(readFileSync(file, 'utf8')).toBe(contents);
  });

  it('rejects config symlinks without changing their target', () => {
    const foreign = join(root, 'foreign.json');
    writeFileSync(foreign, '{"private":"preserved"}');
    symlinkSync(foreign, join(root, '.mcp.json'));
    expect(() => writeAuraMcpConfig(root, 'claude')).toThrow();
    expect(readFileSync(foreign, 'utf8')).toBe('{"private":"preserved"}');
  });

  it('creates private files and preserves permissions when merging', () => {
    const file = join(root, '.mcp.json');
    writeAuraMcpConfig(root, 'claude');
    expect(statSync(file).mode & 0o777).toBe(0o600);
    writeFileSync(file, '{"mcpServers":{"other":{"command":"other"}}}');
    chmodSync(file, 0o640);
    writeAuraMcpConfig(root, 'claude');
    expect(statSync(file).mode & 0o777).toBe(0o640);
  });

  it('creates a cursor mcp.json with the auraboot server entry', () => {
    const res = writeAuraMcpConfig(root, 'cursor');
    expect(res.action).toBe('created');
    const cfg = JSON.parse(readFileSync(join(root, '.cursor/mcp.json'), 'utf8'));
    expect(cfg.mcpServers.auraboot).toEqual({ command: 'aura', args: ['mcp', 'serve'] });
  });

  it('merges into an existing config without clobbering other servers', () => {
    mkdirSync(join(root, '.cursor'), { recursive: true });
    writeFileSync(
      join(root, '.cursor/mcp.json'),
      JSON.stringify({ mcpServers: { slack: { command: 'slack-mcp' } } }),
    );
    const res = writeAuraMcpConfig(root, 'cursor');
    expect(res.action).toBe('updated');
    const cfg = JSON.parse(readFileSync(join(root, '.cursor/mcp.json'), 'utf8'));
    expect(cfg.mcpServers.slack).toEqual({ command: 'slack-mcp' });
    expect(cfg.mcpServers.auraboot).toEqual({ command: 'aura', args: ['mcp', 'serve'] });
  });

  it('is idempotent — a second write reports unchanged', () => {
    writeAuraMcpConfig(root, 'cursor');
    expect(writeAuraMcpConfig(root, 'cursor').action).toBe('unchanged');
  });

  it('uses .mcp.json for claude', () => {
    writeAuraMcpConfig(root, 'claude');
    expect(existsSync(join(root, '.mcp.json'))).toBe(true);
  });

  it('returns manual (no file) for codex which uses a non-JSON config', () => {
    const res = writeAuraMcpConfig(root, 'codex');
    expect(res.action).toBe('manual');
    expect(res.path).toBeNull();
    expect(existsSync(join(root, '.agents'))).toBe(false);
  });
});
