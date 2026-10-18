import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { canonicalJson, operations, requireCondition, sha256, validateDocument } from './contract.mjs';
import { traceSourceGraph } from './source-graph.mjs';
const ts = createRequire(new URL('./toolchain/package.json', import.meta.url))('typescript');
const NETWORK_MODULE = /(?:^|\/)(?:http-client|HttpClient|ApiService|internal-api)(?:\/|$)|^axios$/;
const HTTP_NAMES = new Set(['get', 'post', 'put', 'patch', 'del', 'delete', 'head', 'options', 'fetchResult', 'fetchData', 'request', 'internalRequest', 'internalFormRequest']);
const PRODUCTION = p => !/(^|\/)(__tests__|tests)(\/|$)|\.(test|spec)\.|\.d\.[cm]?ts$/.test(p);
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

function withoutTypeWrappers(node) {
  while (node && (ts.isParenthesizedExpression(node) || ts.isAsExpression(node)
    || ts.isNonNullExpression(node) || ts.isSatisfiesExpression(node))) node = node.expression;
  return node;
}

// Token/value identity, not an AST equivalence test. Preserve every scalar token;
// omit comments, layout and erased type arguments of URL-builder calls.
function syntaxIdentity(node, source) {
  node = withoutTypeWrappers(node);
  if (!node) return ['missing'];
  if (typeof node.text === 'string') return [node.kind, node.text];
  if (ts.isCallExpression(node) || ts.isNewExpression(node))
    return [node.kind, syntaxIdentity(node.expression, source), (node.arguments ?? []).map(n => syntaxIdentity(n, source))];
  const children = node.getChildren(source).filter(n => !(n.kind >= ts.SyntaxKind.FirstJSDocNode && n.kind <= ts.SyntaxKind.LastJSDocNode));
  return [node.kind, children.length ? children.map(n => syntaxIdentity(n, source)) : node.getText(source)];
}
function identityName(node, source) {
  return node && typeof node.text === 'string' ? node.text : canonicalJson(syntaxIdentity(node, source));
}
function lexicalScope(node, source) {
  const scopes = [];
  for (let cursor = node.parent; cursor && cursor !== source; cursor = cursor.parent) {
    if (ts.isClassDeclaration(cursor) || ts.isClassExpression(cursor) || ts.isModuleDeclaration(cursor))
      scopes.push(`container:${cursor.name ? identityName(cursor.name, source) : '<anonymous>'}`);
    if (ts.isVariableDeclaration(cursor) && ts.isObjectLiteralExpression(withoutTypeWrappers(cursor.initializer) ?? source))
      scopes.push(`object:${identityName(cursor.name, source)}`);
    if (ts.isFunctionLike(cursor)) {
      let parent = cursor.parent;
      while (parent && (ts.isParenthesizedExpression(parent) || ts.isAsExpression(parent) || ts.isSatisfiesExpression(parent))) parent = parent.parent;
      if (ts.isConstructorDeclaration(cursor)) scopes.push('callable:constructor');
      else if (cursor.name) scopes.push(`callable:${identityName(cursor.name, source)}`);
      else if (parent && (ts.isVariableDeclaration(parent) || ts.isPropertyAssignment(parent) || ts.isPropertyDeclaration(parent)))
        scopes.push(`callable:${identityName(parent.name, source)}`);
      else if (parent && ts.isCallExpression(parent)) {
        const callee = parent.expression;
        const name = ts.isPropertyAccessExpression(callee) ? identityName(callee.name, source) : identityName(callee, source);
        const index = parent.arguments.findIndex(argument => withoutTypeWrappers(argument) === cursor);
        scopes.push(`callback:${name}:${index}`);
      } else scopes.push('callable:<anonymous>');
    }
  }
  return scopes.reverse();
}

/** Static source reconciliation. Unresolved routes remain visible and block completion. */
export function discoverCalls(sources, document) {
  validateDocument(document);
  const virtual = new Map(sources.map(s => [path.resolve('/source', s.file), s.text]));
  const options = { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.Preserve, allowJs: true, noResolve: true, noLib: true };
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
        if (HTTP_NAMES.has(original)) imports.set(checker.getSymbolAtLocation(e.name), original);
      }
    }
    function transport(n, visited = new Set()) {
      if (!n) return undefined;
      if (ts.isParenthesizedExpression(n) || ts.isAsExpression(n) || ts.isNonNullExpression(n))
        return transport(n.expression, visited);
      const symbol = checker.getSymbolAtLocation(ts.isPropertyAccessExpression(n) ? n.name : n);
      if (imports.has(symbol)) return imports.get(symbol);
      if (ts.isIdentifier(n) && n.text === 'fetch' && !symbol?.declarations?.length) return 'fetch';
      if (ts.isPropertyAccessExpression(n) && n.name.text === 'fetch'
        && ts.isIdentifier(n.expression) && ['globalThis', 'window'].includes(n.expression.text)
        && !checker.getSymbolAtLocation(n.expression)?.declarations?.length) return 'fetch';
      if (symbol && !visited.has(symbol)) {
        const next = new Set(visited); next.add(symbol);
        const declaration = symbol.valueDeclaration;
        // A typed injected fetch remains a network candidate even without a default.
        if (declaration?.type && ts.isTypeQueryNode(declaration.type)
          && declaration.type.exprName.getText(sf) === 'fetch') return 'fetch';
        if (declaration?.initializer) {
          const kind = transport(declaration.initializer, next);
          const immutable = ts.isVariableDeclaration(declaration)
            && ts.isVariableDeclarationList(declaration.parent)
            && Boolean(declaration.parent.flags & ts.NodeFlags.Const);
          return kind && !immutable ? 'candidate' : kind;
        }
      }
      if (ts.isCallExpression(n) && ts.isPropertyAccessExpression(n.expression)
        && n.expression.name.text === 'bind') {
        const kind = transport(n.expression.expression, visited);
        return kind && n.arguments.length <= 1 ? kind : kind ? 'candidate' : undefined;
      }
      if (ts.isConditionalExpression(n) || ts.isBinaryExpression(n)
        && [ts.SyntaxKind.QuestionQuestionToken, ts.SyntaxKind.BarBarToken].includes(n.operatorToken.kind)) {
        const left = transport(ts.isConditionalExpression(n) ? n.whenTrue : n.left, visited);
        const right = transport(ts.isConditionalExpression(n) ? n.whenFalse : n.right, visited);
        return left && left === right ? left : left || right ? 'candidate' : undefined;
      }
      return undefined;
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
    // Enumerate syntactic finite alternatives without selecting a runtime branch.
    // A cap preserves pathological expressions as unresolved rather than truncating.
    const routeOverflow = Symbol('route-alternative-overflow');
    function routeAlternatives(n, visited = new Set()) {
      if (!n) return [undefined];
      const unique = values => [...new Set(values)];
      const product = (left, right) => {
        if (left.includes(routeOverflow) || right.includes(routeOverflow) || left.length * right.length > 32) return [routeOverflow];
        return unique(left.flatMap(a => right.map(b => a === undefined || b === undefined ? undefined : a + b)));
      };
      if (ts.isParenthesizedExpression(n) || ts.isAsExpression(n) || ts.isNonNullExpression(n))
        return routeAlternatives(n.expression, visited);
      if (ts.isConditionalExpression(n)) {
        const values = unique([...routeAlternatives(n.whenTrue, visited), ...routeAlternatives(n.whenFalse, visited)]);
        return values.length > 32 ? [routeOverflow] : values;
      }
      if (ts.isTemplateExpression(n)) {
        let values = [n.head.text];
        for (const span of n.templateSpans) {
          values = product(values, routeAlternatives(span.expression, visited)
            .map(value => value === undefined ? `{${placeholder(span.expression)}}` : value));
          values = product(values, [span.literal.text]);
        }
        return values;
      }
      if (ts.isBinaryExpression(n) && n.operatorToken.kind === ts.SyntaxKind.PlusToken)
        return product(routeAlternatives(n.left, visited), routeAlternatives(n.right, visited));
      if (ts.isCallExpression(n) && /^(encodeURIComponent|String)$/.test(n.expression.getText(sf)))
        return routeAlternatives(n.arguments[0], visited);
      const symbol = checker.getSymbolAtLocation(ts.isPropertyAccessExpression(n) ? n.name : n);
      if (symbol && !visited.has(symbol) && symbol.valueDeclaration?.initializer) {
        const next = new Set(visited); next.add(symbol);
        return routeAlternatives(symbol.valueDeclaration.initializer, next);
      }
      return [evaluate(n, visited)];
    }
    function routeInputs(n, visited = new Set(), inputs = new Map()) {
      if (!n) return [...inputs.values()];
      const symbol = checker.getSymbolAtLocation(ts.isPropertyAccessExpression(n) ? n.name : n);
      if (symbol && !visited.has(symbol)) {
        visited.add(symbol);
        const declaration = symbol.valueDeclaration;
        if (declaration?.initializer) {
          const configurable = ts.isParameter(declaration);
          const mutableVariable = ts.isVariableDeclaration(declaration)
            && ts.isVariableDeclarationList(declaration.parent)
            && !(declaration.parent.flags & ts.NodeFlags.Const);
          // Even readonly instance fields can be replaced by constructor input.
          const instanceProperty = ts.isPropertyDeclaration(declaration);
          if (configurable || mutableVariable || instanceProperty) {
            const line = sf.getLineAndCharacterOfPosition(declaration.getStart(sf)).line + 1;
            inputs.set(`${line}:${symbol.name}`, { kind: configurable ? 'replaceable-parameter-default' : instanceProperty ? 'instance-route-property' : 'mutable-route-value',
              binding: symbol.name, declarationLine: line });
          }
          routeInputs(declaration.initializer, visited, inputs);
        }
      }
      ts.forEachChild(n, child => { routeInputs(child, visited, inputs); });
      return [...inputs.values()];
    }
    function placeholder(n) {
      if (ts.isCallExpression(n) && n.arguments[0]) return placeholder(n.arguments[0]);
      return n.getText(sf).replace(/[^a-zA-Z0-9_]/g, '_').slice(0, 100) || 'value';
    }
    function localCollection(receiver, visited = new Set()) {
      if (!receiver) return undefined;
      if (ts.isParenthesizedExpression(receiver) || ts.isAsExpression(receiver) || ts.isNonNullExpression(receiver))
        return localCollection(receiver.expression, visited);
      const symbol = checker.getSymbolAtLocation(ts.isPropertyAccessExpression(receiver) ? receiver.name : receiver);
      if (!symbol || visited.has(symbol)) return undefined;
      const declaration = symbol.valueDeclaration;
      const initializer = declaration?.initializer;
      if (!initializer) return undefined;
      if (ts.isNewExpression(initializer) && ts.isIdentifier(initializer.expression)
        && ['Map', 'Set', 'WeakMap', 'WeakSet'].includes(initializer.expression.text)
        // A locally imported or declared constructor may be a network client.
        && !checker.getSymbolAtLocation(initializer.expression)?.declarations?.length) {
        return { kind: 'local-collection', binding: symbol.name, constructor: initializer.expression.text,
          file: source.file, declarationLine: sf.getLineAndCharacterOfPosition(declaration.getStart(sf)).line + 1 };
      }
      return localCollection(initializer, new Set([...visited, symbol]));
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
        const resolvedTransport = transport(n.expression);
        const imported = resolvedTransport && HTTP_NAMES.has(resolvedTransport) ? resolvedTransport : undefined;
        const isFetch = resolvedTransport === 'fetch' || resolvedTransport === 'candidate' || callee === 'fetch' || callee === 'globalThis.fetch';
        const isClient = property && (HTTP_NAMES.has(property) || property === 'fetch') && /api|http|client|axios|service/i.test(callee);
        if (imported || isFetch || isClient) {
          const generated = imported === 'internalRequest' || imported === 'internalFormRequest';
          const targetNode = n.arguments[generated ? 1 : 0];
          let method = generated ? evaluate(n.arguments[0]) : httpMethod(imported ?? property) ?? 'get';
          const config = n.arguments[generated ? 2 : 1];
          if (config && ts.isObjectLiteralExpression(config)) {
            const methodOption = config.properties.find(p => p.name?.getText(sf).replace(/['"]/g, '') === 'method');
            if (!generated && methodOption && ts.isPropertyAssignment(methodOption)) method = evaluate(methodOption.initializer)?.toLowerCase();
            else if (!generated && !httpMethod(imported ?? property) && config.properties.some(p => ts.isSpreadAssignment(p))) method = 'unresolved';
          } else if (!generated && !httpMethod(imported ?? property) && config) method = 'unresolved';
          if (!method || !httpMethod(method)) method = 'unresolved';
          const routes = routeAlternatives(targetNode).map(value => value === routeOverflow ? undefined : value);
          const route = routes.length === 1 ? routes[0] : undefined;
          const target = targetNode?.getText(sf) ?? '';
          const routeInputEvidence = routeInputs(targetNode);
          const plumbing = /\/http-client\/|\/ApiService\.ts$|\/server\/services\/BffProxyService\.ts$/.test(source.file);
          const exclusion = ts.isPropertyAccessExpression(n.expression) ? localCollection(n.expression.expression) : undefined;
          const branches = routes.map(route => {
            let classification = exclusion ? 'excluded-local-collection' : plumbing ? 'transport-plumbing' : !route ? 'unresolved-route'
              : /^https?:\/\//.test(route) ? 'absolute-origin-needs-review'
              : !route.startsWith('/api/') ? 'non-api-or-unresolved-base' : 'internal';
            const matches = classification === 'internal' ? matchOperation(route, method, document) : [];
            if (classification === 'internal') classification = matches.length === 1 ? 'source-linked'
              : matches.length > 1 ? 'ambiguous-operation' : 'unmatched-operation';
            return { route: route ?? null, classification, matches };
          });
          const classifications = new Set(branches.map(branch => branch.classification));
          let classification = classifications.size === 1 ? branches[0].classification : 'unresolved-route-branches';
          if (classification === 'source-linked' && routeInputEvidence.length) classification = 'route-input-needs-review';
          const matches = [...new Map(branches.flatMap(branch => branch.matches)
            .map(match => [`${match.method} ${match.path}`, match])).values()];
          const identityTargets = [...new Set(branches.flatMap(branch => branch.matches.length
            ? branch.matches.map(match => `operation:${match.method} ${match.path}`)
            : [`route:${branch.route ?? canonicalJson(syntaxIdentity(targetNode, sf))}`]))].sort();
          calls.push({ legacyId: sha256(`${source.file}:${n.getStart(sf)}:${callee}:${target}`).slice(0, 20),
            identityVersion: 2, identityAnchor: { file: source.file.replaceAll('\\', '/'), scope: lexicalScope(n, sf), method, targets: identityTargets },
            file: source.file, line: line(), callee, method, target, route: route ?? null,
            transportResolution: resolvedTransport ?? 'name-heuristic-candidate', routeInputEvidence,
            typeArguments: (n.typeArguments ?? []).map(t => t.getText(sf)), classification, matches, branches,
            migration: exclusion ? 'not-applicable' : generated ? 'generated-operation' : 'not-migrated',
            evidence: exclusion ? [exclusion] : [] });
        }
      }
      ts.forEachChild(n, visit);
    }
    visit(sf);
  }
  const identityGroups = new Map();
  for (const call of calls) {
    const anchor = canonicalJson(call.identityAnchor);
    if (!identityGroups.has(anchor)) identityGroups.set(anchor, []);
    identityGroups.get(anchor).push(call);
  }
  for (const [anchor, group] of identityGroups) group.forEach((call, index) => {
    call.id = sha256(index === 0 ? anchor : `${anchor}occurrence:${index + 1}`).slice(0, 20);
    call.identityStability = group.length === 1 ? 'unique-anchor' : 'duplicate-order-dependent';
    call.identityOccurrence = index + 1;
    call.identityCount = group.length;
  });
  requireCondition(new Set(calls.map(call => call.id)).size === calls.length, 'Network call identity collision');
  const summary = Object.fromEntries([...new Set(calls.map(c => c.classification))].sort()
    .map(key => [key, calls.filter(c => c.classification === key).length]));
  const selected = new Map(), linked = new Set();
  for (const call of calls) for (const match of call.matches) {
    const key = `${match.method} ${match.path}`;
    selected.set(key, match);
    if (call.classification === 'source-linked') linked.add(key);
  }
  return { callIdentityVersion: 2, summary: { files: sources.length, calls: calls.length, declarations: declarations.length,
    sourceLinkedOperations: linked.size, candidateOperations: selected.size, classifications: summary },
    calls, declarations, operations: [...selected.values()].sort((a, b) => `${a.path} ${a.method}`.localeCompare(`${b.path} ${b.method}`, 'en')) };
}

export function inventory(root, document) {
  const git = (...args) => execFileSync('git', ['-C', root, ...args], { encoding: 'utf8' }).trim();
  const commit = git('rev-parse', 'HEAD');
  requireCondition(git('status', '--porcelain') === '', 'Inventory requires a clean source checkout');
  const files = git('ls-files', '--cached', '--others', '--exclude-standard').split('\n');
  const sourceFiles = files.filter(p => /^(?:web-admin\/(?:app|packages)\/|packages\/)/.test(p) && /\.[cm]?[jt]sx?$/.test(p) && PRODUCTION(p));
  const packageFiles = files.filter(p => /^(?:packages|web-admin\/packages)\/[^/]+\/package\.json$/.test(p));
  const configFile = 'web-admin/tsconfig.json', inputFiles = [...new Set([...sourceFiles, ...packageFiles, configFile])].sort();
  const contents = new Map(inputFiles.map(file => [file, fs.readFileSync(path.join(root, file), 'utf8')]));
  const sourceInputs = Object.fromEntries([...contents].map(([file, text]) => [file, sha256(text)]));
  const scanInputsSha256 = sha256(canonicalJson(sourceInputs));
  const toolFiles = ['discover.mjs','source-graph.mjs','contract.mjs','toolchain/package-lock.json'];
  const toolInputs = () => Object.fromEntries(toolFiles.map(file => [file, sha256(fs.readFileSync(new URL(file, import.meta.url)))]));
  const toolInputHashes = toolInputs();
  const sources = sourceFiles.map(file => ({ file, text: contents.get(file) }));
  const packages = packageFiles.map(file => ({ root: path.posix.dirname(file), manifest: JSON.parse(contents.get(file)) }));
  const configText = contents.get(configFile);
  const config = ts.parseConfigFileTextToJson(configFile, configText);
  requireCondition(!config.error && config.config?.compilerOptions, 'Cannot read Web Admin source graph compiler options');
  requireCondition(!config.config.extends, 'Inherited source graph aliases require explicit resolution');
  const base = path.posix.join(path.posix.dirname(configFile), config.config.compilerOptions.baseUrl ?? '.');
  const aliases = Object.fromEntries(Object.entries(config.config.compilerOptions.paths ?? {})
    .map(([pattern, targets]) => [pattern, targets.map(target => path.posix.join(base, target))]));
  const entries = ['root.tsx', 'routes.ts', 'entry.client.tsx', 'entry.server.tsx', 'server/bff.server.ts']
    .map(file => `web-admin/app/${file}`);
  const sourceGraph = traceSourceGraph({ sources, entries, packages, aliases });
  const reachability = new Map(sourceGraph.files.map(row => [row.file, row]));
  const result = discoverCalls(sources, document);
  for (const row of [...result.calls, ...result.declarations]) {
    const file = reachability.get(row.file);
    row.entryReachability = file.reachable ? 'static-entry-linked' : 'not-proven-entry-linked';
    row.reachedBy = file.reachedBy;
  }
  requireCondition(git('rev-parse', 'HEAD') === commit && git('status', '--porcelain') === '', 'Source changed during inventory');
  requireCondition(sha256(canonicalJson(Object.fromEntries(inputFiles.map(file => [file, sha256(fs.readFileSync(path.join(root, file)))])))) === scanInputsSha256,
    'Source inputs changed during inventory');
  requireCondition(canonicalJson(toolInputs()) === canonicalJson(toolInputHashes), 'Inventory tool inputs changed during inventory');
  return { sourceCommit: commit, sourceClaim: 'clean-git-snapshot', scanInputsSha256, sourceInputs, toolInputHashes,
    toolVersions: { node: process.version, typescript: ts.version }, sourceDigest: sha256(sources.map(s => s.file + '\n' + sha256(s.text)).join('\n')),
    sourceGraphInputsSha256: sha256(canonicalJson({ packages, entries, aliases, configSha256: sha256(configText) })),
    catalogDigest: sha256(canonicalJson(document)), ...result, sourceGraph };
}

if (process.argv[1] && import.meta.url === pathToFileURL(fs.realpathSync(process.argv[1])).href) {
  try {
    const [root, input, output] = process.argv.slice(2);
    requireCondition(process.argv.slice(2).length === 3 && root && input && output, 'Usage: node discover.mjs <repo> <catalog.json> <manifest.json>');
    requireCondition(!fs.existsSync(output), 'Inventory output exists; use a fresh attempt path');
    const result = inventory(root, JSON.parse(fs.readFileSync(input, 'utf8')));
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(output, canonicalJson(result), { flag: 'wx' });
    console.log(JSON.stringify(result.summary));
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
