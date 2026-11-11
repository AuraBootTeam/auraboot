import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const ts = createRequire(path.join(root, 'package.json'))('typescript');
function parse(file) {
  return ts.createSourceFile(file, readFileSync(path.join(root, file), 'utf8'),
    ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
}
function findNodes(node, predicate) {
  const matches = [];
  function visit(child) {
    if (predicate(child)) matches.push(child);
    ts.forEachChild(child, visit);
  }
  visit(node);
  return matches;
}
function execute(source, variables = {}) {
  const javascript = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  return vm.runInContext(javascript, vm.createContext(variables), { timeout: 1000 });
}
function observe(callback, code) {
  return execute(`
    const prior = { existing: { valid: false } };
    const prototype = Object.prototype;
    const names = Object.getOwnPropertyNames(prototype).join();
    const next = (${callback})(prior);
    ({ own: Object.hasOwn(next, code),
       newObject: next !== prior,
       priorUntouched: !Object.hasOwn(prior, code) && Object.getPrototypeOf(prior) === prototype,
       prototypeUntouched: Object.getPrototypeOf(next) === prototype &&
         Object.getOwnPropertyNames(prototype).join() === names,
       preservesExisting: next.existing === prior.existing });
  `, { code, fingerprint: 'security-boundary-fixture' });
}
function assertSafe(result) {
  assert.equal(result.own, true);
  assert.equal(result.newObject, true);
  assert.equal(result.priorUntouched, true);
  assert.equal(result.prototypeUntouched, true);
  assert.equal(result.preservesExisting, true);
}

const studio = parse('web-admin/app/shared/decision/ui/StrategyStudioWorkbench.tsx');
const updates = findNodes(studio, node => ts.isCallExpression(node)
  && node.expression.getText(studio) === 'setReadiness'
  && ts.isArrowFunction(node.arguments[0]));
test('the three reported readiness callbacks remain in the security test denominator', () => {
  assert.equal(updates.length, 3);
});
for (const [index, update] of updates.entries()) {
  test(`readiness callback ${index + 1} preserves state and prototypes for hostile property keys`, () => {
    for (const code of ['__proto__', 'constructor', 'toString', 'normal_decision']) {
      assertSafe(observe(update.arguments[0].getText(studio), code));
    }
  });
}
test('the same safety assertions reject a mutating prototype setter', () => {
  const unsafe = observe('(current) => { current[code] = { valid: true }; return current; }', '__proto__');
  assert.throws(() => assertSafe(unsafe), assert.AssertionError);
});

test('actual contextual query filters admit useful flat keys while rejecting prototype keys', () => {
  const authoring = parse('web-admin/app/framework/meta/authoring/ContextualAuthoringSurface.tsx');
  const collect = findNodes(authoring, node => ts.isFunctionDeclaration(node)
    && node.name?.text === 'collectQueryState');
  const capture = findNodes(authoring, node => ts.isFunctionDeclaration(node)
    && node.name?.text === 'captureInteractionContext');
  assert.equal(collect.length, 1);
  assert.equal(capture.length, 1);
  const declarations = findNodes(capture[0], node => ts.isVariableDeclaration(node)
    && ['filters', 'sort'].includes(node.name.getText(authoring)));
  assert.equal(declarations.length, 2);
  for (const declaration of declarations) {
    const include = declaration.initializer.arguments[1].getText(authoring);
    const result = execute(`${collect[0].getText(authoring)};
      const previous = Object.getOwnPropertyNames(Object.prototype).join();
      const values = collectQueryState(new URLSearchParams(
        '__proto__=bad&constructor=bad&toString=bad&filter.name=Acme&filter[__proto__]=literal&sort=name'), ${include});
      ({ keys: Object.keys(values),
         flatValue: values['filter[__proto__]'],
         clean: Object.getPrototypeOf(values) === Object.prototype &&
           Object.getOwnPropertyNames(Object.prototype).join() === previous });`, { URLSearchParams });
    assert.equal(result.clean, true);
    assert.equal(result.keys.some(key => ['__proto__', 'constructor', 'toString'].includes(key)), false);
    if (declaration.name.getText(authoring) === 'filters') {
      assert.equal(result.keys.includes('filter.name'), true);
      assert.deepEqual(Array.from(result.flatValue), ['literal']);
    } else {
      assert.equal(result.keys.includes('sort'), true);
    }
  }
});
