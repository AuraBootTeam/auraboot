import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { isDeepStrictEqual } from 'node:util';
import { requireCondition, sha256 } from './contract.mjs';

const ts = createRequire(new URL('./toolchain/package.json', import.meta.url))('typescript');
const literal = node => node && ts.isStringLiteralLike(node) ? node.text : undefined;
const equal = (a, b, message) => requireCondition(isDeepStrictEqual(a, b), message);
const key = row => `${row.file} :: ${row.title}`;

function jsonAttachments(body) {
  const names = [];
  const visit = node => {
    if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression)
        && node.expression.name.text === 'attach' && literal(node.arguments[0])
        && node.arguments[1] && ts.isObjectLiteralExpression(node.arguments[1])) {
      const media = node.arguments[1].properties.find(property => ts.isPropertyAssignment(property)
        && (property.name.getText() === 'contentType' || literal(property.name) === 'contentType'));
      if (media && literal(media.initializer) === 'application/json') names.push(literal(node.arguments[0]));
    }
    ts.forEachChild(node, visit);
  };
  visit(body);
  requireCondition(new Set(names).size === names.length, 'Duplicate JSON evidence declarations');
  return names.sort();
}

/** Reconcile the catalog against independently enumerated spec sources before collecting or executing. */
export function auditBrowserCatalog(sources, catalog) {
  requireCondition(Array.isArray(sources) && sources.length > 0, 'Missing browser spec sources');
  requireCondition(new Set(sources.map(row => row.file)).size === sources.length, 'Duplicate browser spec source');
  const tests = [], captures = [];
  for (const { file, text } of sources) {
    requireCondition(typeof text === 'string' && text.trim(), `Empty browser spec: ${file}`);
    const source = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
    requireCondition(source.parseDiagnostics.length === 0, `Invalid browser spec: ${file}`);
    const imports = source.statements.filter(node => ts.isImportDeclaration(node)
      && literal(node.moduleSpecifier) === '@playwright/test')
      .flatMap(node => node.importClause?.namedBindings && ts.isNamedImports(node.importClause.namedBindings)
        ? node.importClause.namedBindings.elements.filter(item => (item.propertyName?.text ?? item.name.text) === 'test').map(item => item.name.text) : []);
    requireCondition(imports.length === 1, `Expected one Playwright test import: ${file}`);
    const binding = imports[0], localTests = [], hooks = [];
    const visit = node => {
      if ((ts.isVariableDeclaration(node) || ts.isParameter(node) || ts.isFunctionDeclaration(node)
          || ts.isClassDeclaration(node)) && node.name && ts.isIdentifier(node.name) && node.name.text === binding)
        throw new Error(`Shadowed Playwright test binding: ${file}`);
      if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression)) {
        let base = node.expression.expression;
        while (ts.isPropertyAccessExpression(base)) base = base.expression;
        const method = node.expression.name.text;
        if (ts.isIdentifier(base) && base.text === binding)
          requireCondition(!['skip', 'only', 'fixme', 'fail'].includes(method), `Non-normal test declaration: ${file}`);
        if (!ts.isIdentifier(node.expression.expression) || node.expression.expression.text !== binding) {
          ts.forEachChild(node, visit); return;
        }
        if (method === 'afterEach') {
          const callback = node.arguments.at(-1);
          requireCondition(callback && (ts.isArrowFunction(callback) || ts.isFunctionExpression(callback)), `Unsupported hook: ${file}`);
          hooks.push(...jsonAttachments(callback.body));
        }
      }
      if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === binding) {
        const title = literal(node.arguments[0]), callback = node.arguments.at(-1);
        requireCondition(title && callback && (ts.isArrowFunction(callback) || ts.isFunctionExpression(callback))
          && ts.isBlock(callback.body), `Unsupported dynamic browser test: ${file}`);
        const scenes = [];
        const capture = child => {
          if (ts.isCallExpression(child) && ts.isIdentifier(child.expression) && child.expression.text === 'capture') {
            const id = literal(child.arguments[0]);
            requireCondition(id, `Dynamic scene declaration: ${file} :: ${title}`);
            scenes.push(id);
          }
          ts.forEachChild(child, capture);
        };
        capture(callback.body);
        const evidence = jsonAttachments(callback.body);
        requireCondition(scenes.length + evidence.length > 0, `No declared evidence: ${file} :: ${title}`);
        localTests.push({ file, title, project: file.startsWith('web-admin/tests/api/internal-api/') ? 'internal-api-wire' : 'contract', scenes, evidence });
      }
      ts.forEachChild(node, visit);
    };
    visit(source);
    requireCondition(localTests.length > 0, `No browser tests in enumerated spec: ${file}`);
    for (const row of localTests) {
      tests.push({ ...row, requiredEvidence: [...new Set([...row.evidence, ...hooks])].sort().map(name => ({ name, fields: {} })) });
      captures.push(...row.scenes.map(id => ({ id, test: row.title, file })));
    }
  }
  requireCondition(new Set(tests.map(key)).size === tests.length, 'Duplicate browser test declaration');
  const expected = Object.values(catalog.profiles).filter(profile => profile.spec)
    .flatMap(profile => profile.tests.map(title => ({ file: profile.spec, title })));
  requireCondition(expected.length > 0 && new Set(expected.map(key)).size === expected.length, 'Empty or duplicate catalog tests');
  equal(tests.map(key).sort(), expected.map(key).sort(), 'Catalog and actual spec test denominator differ');
  requireCondition(Array.isArray(catalog.scenarios) && catalog.scenarios.length > 0, 'Missing catalog scenes');
  requireCondition(new Set(catalog.scenarios.map(row => row.id)).size === catalog.scenarios.length, 'Duplicate catalog scene ID');
  requireCondition(new Set(catalog.scenarios.map(row => row.screenshot)).size === catalog.scenarios.length, 'Duplicate catalog image filename');
  requireCondition(new Set(captures.map(row => row.id)).size === captures.length, 'Duplicate scene capture declaration');
  const declared = catalog.scenarios.map(row => {
    const owner = expected.filter(test => test.title === row.test);
    requireCondition(owner.length === 1 && /^[A-Z]+-\d+$/.test(row.id)
      && typeof row.screenshot === 'string' && path.posix.basename(row.screenshot) === row.screenshot
      && row.screenshot.endsWith('.png'), `Invalid or ambiguous catalog scene: ${row.id}`);
    return { id: row.id, test: row.test, file: owner[0].file };
  });
  equal(captures.sort((a, b) => a.id.localeCompare(b.id)), declared.sort((a, b) => a.id.localeCompare(b.id)), 'Catalog and actual scene denominator differ');
  return { tests: tests.map(row => ({ ...row, id: `internal-api-${sha256(key(row)).slice(0, 16)}` })), scenarios: declared };
}

export function listBrowserSpecFiles(root) {
  const directory = path.join(root, 'web-admin/tests');
  return fs.readdirSync(directory, { recursive: true })
    .map(file => file.replaceAll(path.sep, '/'))
    .filter(file => /^e2e\/.+\/internal-api-[^/]+\.spec\.ts$/.test(file) || /^api\/internal-api\/[^/]+\.spec\.ts$/.test(file))
    .map(file => `web-admin/tests/${file}`).sort();
}

export function loadBrowserCatalog(root, catalog) {
  return auditBrowserCatalog(listBrowserSpecFiles(root).map(file => ({ file, text: fs.readFileSync(path.join(root, file), 'utf8') })), catalog);
}
