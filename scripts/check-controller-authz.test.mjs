import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const scanner = fileURLToPath(new URL('./check-controller-authz.mjs', import.meta.url));

function inspect(source) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'controller-authz-'));
  try {
    fs.mkdirSync(path.join(root, 'scripts'));
    fs.mkdirSync(path.join(root, 'platform/src/main/java'), { recursive: true });
    fs.writeFileSync(path.join(root, 'scripts/controller-authz-baseline.json'), '[]');
    fs.writeFileSync(path.join(root, 'platform/src/main/java/FixtureController.java'), source);
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
