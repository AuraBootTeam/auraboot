import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { canonicalJson, operations, requireCondition, sha256, validateDocument } from './contract.mjs';
const ts = createRequire(new URL('./toolchain/package.json', import.meta.url))('typescript');
const NETWORK_MODULE = /(?:^|\/)(?:http-client|HttpClient|ApiService|internal-api)(?:\/|$)|^axios$/;
const HTTP_NAMES = new Set(['get', 'post', 'put', 'patch', 'del', 'delete', 'head', 'options', 'fetchResult', 'fetchData', 'request', 'internalRequest']);
const PRODUCTION = p => !/(^|\/)(__tests__|tests|fixtures|__fixtures__|generated)(\/|$)|\.(test|spec)\./.test(p);
const httpMethod = name => name === 'del' ? 'delete' : ['get', 'post', 'put', 'patch', 'delete', 'head', 'options'].includes(name) ? name : undefined;

export function matchOperation(route, method, document) {
  const pathname = route.split('?')[0].replace(/\/$/, '') || '/';
  const rows = operations(document).filter(row => row.method === method).flatMap(row => {
    const expected = row.path.replace(/\/$/, '') || '/';
    const a = pathname.split('/'), b = expected.split('/');
    if (a.length !== b.length) return [];
    let score = 0;
    for (let i = 0; i < a.length; i++) {
      const callerVariable = /^\{[^}]+\}$/.test(a[i]);
      const serverVariable = /^\{[^}]+\}$/.test(b[i]);
      // A client variable cannot prove that a fixed server path is selected.
      if (callerVariable && !serverVariable) return [];
      if (!serverVariable && a[i] !== b[i]) return [];
      if (!serverVariable) score++;
    }
    return [{ method: row.method, path: row.path, operationId: row.operationId, score }];
  });
  const best = Math.max(-1, ...rows.map(r => r.score));
  return rows.filter(r => r.score === best).map(({ score, ...row }) => row);
}

/** Static source reconciliation. Unresolved routes remain visible and block completion. */
export function discoverCalls(sources, document) {
  validateDocument(document);
  const virtual = new Map(sources.map(s => [path.resolve('/source', s.file), s.text]));
  const options = { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.Preserve, noResolve: true, noLib: true };
  const host = ts.createCompilerHost(options);
  host.getSourceFile = (file, version) => virtual.has(file)
    ? ts.createSourceFile(file, virtual.get(file), version, true) : undefined;
  host.fileExists = file => virtual.has(file);
  host.readFile = file => virtual.get(file);
  const program = ts.createProgram([...virtual.keys()], options, host);
  const checker = program.getTypeChecker();
  const calls = [], declarations = [];

  for (const source of sources) {
    const sf = program.getSourceFile(path.resolve('/source', source.file));
    const imports = new Map();
    for (const n of sf.statements) {
      if (!ts.isImportDeclaration(n) || !NETWORK_MODULE.test(n.moduleSpecifier.text)) continue;
      const binding = n.importClause?.namedBindings;
      if (binding && ts.isNamedImports(binding)) for (const e of binding.elements) {
        const original = (e.propertyName ?? e.name).text;
        if (HTTP_NAMES.has(original)) imports.set(e.name.text, original);
      }
    }
    function evaluate(n, visited = new Set()) {
      if (!n) return undefined;
      if (ts.isStringLiteralLike(n)) return n.text;
      if (ts.isParenthesizedExpression(n) || ts.isAsExpression(n) || ts.isNonNullExpression(n)) return evaluate(n.expression, visited);
      if (ts.isTemplateExpression(n)) {
        let value = n.head.text;
        for (const span of n.templateSpans) value += (evaluate(span.expression, visited) ?? `{${placeholder(span.expression)}}`) + span.literal.text;
        return value;
      }
      if (ts.isBinaryExpression(n) && n.operatorToken.kind === ts.SyntaxKind.PlusToken) {
        const a = evaluate(n.left, visited), b = evaluate(n.right, visited);
        return a !== undefined && b !== undefined ? a + b : undefined;
      }
      if (ts.isCallExpression(n) && /^(encodeURIComponent|String)$/.test(n.expression.getText(sf))) return evaluate(n.arguments[0], visited);
      const symbol = checker.getSymbolAtLocation(ts.isPropertyAccessExpression(n) ? n.name : n);
      if (symbol && !visited.has(symbol)) {
        const next = new Set(visited); next.add(symbol);
        const declaration = symbol.valueDeclaration;
        if (declaration?.initializer) return evaluate(declaration.initializer, next);
      }
      if (ts.isIdentifier(n) || ts.isPropertyAccessExpression(n) || ts.isElementAccessExpression(n)) return `{${placeholder(n)}}`;
      return undefined;
    }
    function placeholder(n) {
      if (ts.isCallExpression(n) && n.arguments[0]) return placeholder(n.arguments[0]);
      return n.getText(sf).replace(/[^a-zA-Z0-9_]/g, '_').slice(0, 100) || 'value';
    }
    function visit(n) {
      const line = () => sf.getLineAndCharacterOfPosition(n.getStart(sf)).line + 1;
      if (ts.isInterfaceDeclaration(n) || ts.isTypeAliasDeclaration(n)) declarations.push({
        file: source.file, line: line(), symbol: n.name.text,
        kind: ts.isInterfaceDeclaration(n) ? 'interface' : 'type', status: 'candidate-unclassified',
      });
      if (ts.isCallExpression(n)) {
        const callee = n.expression.getText(sf);
        const property = ts.isPropertyAccessExpression(n.expression) ? n.expression.name.text : undefined;
        const imported = imports.get(callee);
        const isFetch = callee === 'fetch' || callee === 'globalThis.fetch';
        const isClient = property && (HTTP_NAMES.has(property) || property === 'fetch') && /api|http|client|axios|service/i.test(callee);
        if (imported || isFetch || isClient) {
          const generated = imported === 'internalRequest';
          const targetNode = n.arguments[generated ? 1 : 0];
          let method = generated ? evaluate(n.arguments[0]) : httpMethod(imported ?? property) ?? 'get';
          const config = n.arguments[generated ? 2 : 1];
          if (config && ts.isObjectLiteralExpression(config)) {
            const methodOption = config.properties.find(p => p.name?.getText(sf).replace(/['"]/g, '') === 'method');
            if (!generated && methodOption && ts.isPropertyAssignment(methodOption)) method = evaluate(methodOption.initializer)?.toLowerCase();
            else if (!generated && !httpMethod(imported ?? property) && config.properties.some(p => ts.isSpreadAssignment(p))) method = 'unresolved';
          } else if (!generated && !httpMethod(imported ?? property) && config) method = 'unresolved';
          if (!method || !httpMethod(method)) method = 'unresolved';
          const route = evaluate(targetNode);
          const target = targetNode?.getText(sf) ?? '';
          const plumbing = /\/http-client\/|\/ApiService\.ts$|\/server\/services\/BffProxyService\.ts$/.test(source.file);
          let classification = plumbing ? 'transport-plumbing' : !route ? 'unresolved-route'
            : /^https?:\/\//.test(route) ? 'absolute-origin-needs-review'
            : !route.startsWith('/api/') ? 'non-api-or-unresolved-base' : 'internal';
          const matches = classification === 'internal' ? matchOperation(route, method, document) : [];
          if (classification === 'internal') classification = matches.length === 1 ? 'source-linked'
            : matches.length > 1 ? 'ambiguous-operation' : 'unmatched-operation';
          calls.push({ id: sha256(`${source.file}:${n.getStart(sf)}:${callee}:${target}`).slice(0, 20),
            file: source.file, line: line(), callee, method, target, route: route ?? null,
            typeArguments: (n.typeArguments ?? []).map(t => t.getText(sf)), classification, matches,
            migration: generated ? 'generated-operation' : 'not-migrated', evidence: [] });
        }
      }
      ts.forEachChild(n, visit);
    }
    visit(sf);
  }
  const summary = Object.fromEntries([...new Set(calls.map(c => c.classification))].sort()
    .map(key => [key, calls.filter(c => c.classification === key).length]));
  const selected = new Map();
  for (const call of calls) for (const match of call.matches) selected.set(`${match.method} ${match.path}`, match);
  return { summary: { files: sources.length, calls: calls.length, declarations: declarations.length,
    sourceLinkedOperations: selected.size, classifications: summary },
    calls, declarations, operations: [...selected.values()].sort((a, b) => `${a.path} ${a.method}`.localeCompare(`${b.path} ${b.method}`, 'en')) };
}

export function inventory(root, document) {
  const git = (...args) => execFileSync('git', ['-C', root, ...args], { encoding: 'utf8' }).trim();
  const commit = git('rev-parse', 'HEAD');
  const files = git('ls-files', '--cached', '--others', '--exclude-standard').split('\n');
  const sources = files.filter(p => /^web-admin\/app\//.test(p) && /\.tsx?$/.test(p) && PRODUCTION(p))
    .map(file => ({ file, text: fs.readFileSync(path.join(root, file), 'utf8') }));
  const result = discoverCalls(sources, document);
  requireCondition(git('rev-parse', 'HEAD') === commit, 'Source commit changed during inventory');
  return { sourceCommit: commit, sourceDigest: sha256(sources.map(s => s.file + '\n' + sha256(s.text)).join('\n')),
    catalogDigest: sha256(canonicalJson(document)), ...result };
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    const [root, input, output] = process.argv.slice(2);
    requireCondition(root && input && output, 'Usage: node discover.mjs <repo> <catalog.json> <manifest.json>');
    const result = inventory(root, JSON.parse(fs.readFileSync(input, 'utf8')));
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(output, canonicalJson(result));
    console.log(JSON.stringify(result.summary));
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
