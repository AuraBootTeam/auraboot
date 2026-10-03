import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

// Hermetic public-control double; real selection is tested separately with Git.
export function registerFixtureWorkspace(root, repo) {
  if (!fs.existsSync(path.join(root, '.git'))) execFileSync('git', ['init', '-q', root]);
  fs.writeFileSync(path.join(root, 'runtime.yaml'), 'repos: {}\n');
  const lib = path.join(repo, 'scripts/lib');
  fs.mkdirSync(lib, { recursive: true });
  fs.copyFileSync(new URL('../../lib/workspace-control.sh', import.meta.url), path.join(lib, 'workspace-control.sh'));
  const aura = path.join(root, 'aura'), implementation = path.join(root, 'aura-fixture-impl');
  fs.copyFileSync(aura, implementation);
  fs.writeFileSync(aura, '#!/usr/bin/env node\n' +
    "const fs=require('node:fs'),path=require('node:path');const args=process.argv.slice(2);\n" +
    "if(args.join(' ')==='control require 1')process.exit(0);\n" +
    "if(args.slice(0,3).join(' ')==='runtime evidence begin'){console.log(path.join(process.env.AURA_WORKSPACE_STATE_DIR||path.join(process.env.AURA_WORKSPACE_ROOT,'.workspace'),'evidence',args[3],'round-fixture'));process.exit(0);}\n" +
    "const r=require('node:child_process').spawnSync(" + JSON.stringify(implementation) +
    ",args,{stdio:'inherit',env:process.env});process.exit(r.status??2);\n", { mode: 0o755 });
}
