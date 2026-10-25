import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const scanner = fileURLToPath(new URL('./check-controller-authz.mjs', import.meta.url));

function inspect(source, module = '') {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'controller-authz-'));
  try {
    fs.mkdirSync(path.join(root, 'scripts'));
    const sourceRoot = path.join(root, 'platform', module, 'src/main/java');
    fs.mkdirSync(sourceRoot, { recursive: true });
    if (module) fs.writeFileSync(path.join(root, 'platform', module, 'build.gradle'), "plugins { id 'java' }\n");
    fs.writeFileSync(path.join(root, 'scripts/controller-authz-baseline.json'), '[]');
    fs.writeFileSync(path.join(sourceRoot, 'FixtureController.java'), source);
    return spawnSync(process.execPath, [scanner], { cwd: root, encoding: 'utf8' });
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

const responses = '@ApiResponses({@ApiResponse(content = @Content(schema = @Schema(implementation = Object.class))), @ApiResponse(description = "semicolon; brace }")})';

test('class authorization survives nested response annotation arrays', () => {
  const source = `@RestController
@AuthenticatedAccess("token-scoped public read")
${responses}
public class FixtureController {
  @GetMapping("/shared/{token}")
  public Object read() { return null; }
}`;
  assert.equal(inspect(source).status, 0);
  // The same annotation metadata must not conceal removal of the actual guard.
  assert.equal(inspect(source.replace('@AuthenticatedAccess("token-scoped public read")', '')).status, 1);
});

test('method authorization survives nested annotations without deciding adjacent handlers', () => {
  const source = `@RestController
public class FixtureController {
  @RequirePermission("write")
  ${responses}
  @PostMapping("/items")
  public Object write() { return null; }
  @GetMapping("/items")
  @AuthenticatedAccess("authenticated read")
  ${responses}
  public Object read() { return null; }
}`;
  assert.equal(inspect(source).status, 0);
  assert.equal(inspect(source.replace('@AuthenticatedAccess("authenticated read")', '')).status, 1);
  assert.equal(inspect(source.replace('@RequirePermission("write")', '')).status, 1);
});

test('comments and literal text cannot impersonate a guard', () => {
  const source = `@RestController
${responses}
public class FixtureController {
  // @AuthenticatedAccess("not an annotation")
  @GetMapping("/items")
  @Operation(description = "@RequirePermission; /* not code */")
  public Object read() { return null; }
}`;
  assert.equal(inspect(source).status, 1);
});

test('optional agent and application controllers retain authorization enforcement', () => {
  const source = `@RestController
public class FixtureController {
  @GetMapping("/items")
  public Object read() { return null; }
}`;
  for (const module of ['platform-agent', 'platform-app']) {
    assert.equal(inspect(source, module).status, 1, module);
    assert.equal(inspect(source.replace('@RestController', '@RestController\n@AuthenticatedAccess("tenant scope")'), module).status, 0);
  }
});
