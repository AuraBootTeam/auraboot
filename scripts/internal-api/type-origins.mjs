import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { canonicalJson, requireCondition, sha256 } from './contract.mjs';
const ts = createRequire(new URL('./toolchain/package.json', import.meta.url))('typescript');

/** Declaration provenance only. It does not prove wire compatibility or data-flow coverage. */
export function traceCallTypes(root, inventory) {
  root = path.resolve(root);
  requireCondition(inventory.calls?.length > 0 && inventory.sourceInputs, 'Type origins require a nonempty bound inventory');
  const inputs = new Map();
  const label = file => path.relative(root, path.resolve(file)).replaceAll(path.sep, '/');
  const readFile = file => {
    const text = ts.sys.readFile(file);
    if (text !== undefined) {
      const key = label(file), digest = sha256(text);
      requireCondition(!inputs.has(key) || inputs.get(key) === digest, `Compiler input changed: ${key}`);
      inputs.set(key, digest);
    }
    return text;
  };
  for (const [file, expected] of Object.entries(inventory.sourceInputs))
    requireCondition(sha256(readFile(path.resolve(root,file)) ?? '') === expected, `Inventory input mismatch: ${file}`);
  const config = ts.readConfigFile(path.join(root,'web-admin/tsconfig.json'), readFile);
  requireCondition(!config.error, 'Cannot read type-origin compiler config');
  const parsed = ts.parseJsonConfigFileContent(config.config, { ...ts.sys, readFile }, path.join(root,'web-admin'));
  requireCondition(parsed.errors.length === 0, 'Cannot parse type-origin compiler config');
  const sourceFiles = Object.keys(inventory.sourceInputs).filter(file => /\.[cm]?[jt]sx?$/.test(file));
  const host = ts.createCompilerHost(parsed.options);
  host.readFile = readFile;
  // createCompilerHost.getSourceFile closes over its original reader on some TS versions.
  host.getSourceFile = (file, version) => {
    const text = readFile(file);
    return text === undefined ? undefined : ts.createSourceFile(file,text,version,true);
  };
  const program = ts.createProgram(sourceFiles.map(file => path.resolve(root,file)), parsed.options, host);
  const checker = program.getTypeChecker(), declarations = new Map();
  const location = node => {
    const source = node.getSourceFile();
    return { file: label(source.fileName), line: source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1,
      start: node.getStart(source), end: node.end };
  };
  const category = file => /(?:^|\/)node_modules\//.test(file) ? 'dependency-declaration'
    : file.startsWith('web-admin/app/generated/internal-api/') ? 'generated-contract'
    : file.startsWith('../') ? 'external-source' : 'source-declaration';
  function resolve(node) {
    let symbol = checker.getSymbolAtLocation(node);
    const seen = new Set();
    while (symbol?.flags & ts.SymbolFlags.Alias) {
      if (seen.has(symbol)) return undefined;
      seen.add(symbol); symbol = checker.getAliasedSymbol(symbol);
    }
    return symbol;
  }
  function symbolOrigins(symbol) {
    return [...new Set((symbol?.declarations ?? []).map(register))].sort();
  }
  function register(node) {
    const loc = location(node), id = sha256(`${loc.file}:${loc.start}:${loc.end}:${ts.SyntaxKind[node.kind]}`).slice(0,20);
    if (declarations.has(id)) return id;
    const row = { id, ...loc, kind: ts.SyntaxKind[node.kind], name: node.name?.getText() ?? null,
      category: category(loc.file), sourceSha256: sha256(node.getSourceFile().text), references: [] };
    declarations.set(id,row); // Cycles must point at this row, not recurse forever.
    if (row.category !== 'source-declaration' && row.category !== 'external-source') return id;
    // Follow declared type syntax, never function bodies, initializer calls or an entire module.
    const roots = ts.isInterfaceDeclaration(node) || ts.isTypeAliasDeclaration(node) ? [node]
      : [node.type, ...(node.typeParameters ?? []).flatMap(p => [p.constraint,p.default]),
        ...(node.heritageClauses ?? []), ...(node.parameters ?? []).map(p => p.type),
        ...(ts.isClassDeclaration(node) ? node.members.flatMap(m => [m.type,...(m.parameters ?? []).map(p=>p.type)]) : [])].filter(Boolean);
    const reference = (target, syntax) => {
      const symbol = resolve(target);
      const origins = symbolOrigins(symbol);
      row.references.push({ syntax, ...location(target), origins,
        status: origins.length ? 'resolved-declaration' : 'unresolved-symbol' });
    };
    const visit = n => {
      if (ts.isTypeReferenceNode(n)) reference(n.typeName,n.getText());
      else if (ts.isExpressionWithTypeArguments(n)) reference(n.expression,n.getText());
      else if (ts.isTypeQueryNode(n)) reference(n.exprName,n.getText());
      else if (ts.isImportTypeNode(n) && n.qualifier) reference(n.qualifier,n.getText());
      ts.forEachChild(n,visit);
    };
    roots.forEach(visit);
    return id;
  }
  function describe(type) {
    const seen = new Set(), origins = new Set(), boundaries = new Set();
    const visit = t => {
      if (!t || seen.has(t)) return;
      seen.add(t);
      if (seen.size > 128) { boundaries.add('type-expansion-limit'); return; }
      if (t.flags & ts.TypeFlags.Any) boundaries.add('any');
      if (t.flags & ts.TypeFlags.Unknown) boundaries.add('unknown');
      if (t.flags & ts.TypeFlags.TypeParameter) boundaries.add('generic-parameter');
      for (const symbol of [t.aliasSymbol,t.getSymbol()])
        symbolOrigins(symbol).forEach(id => origins.add(id));
      for (const child of t.isUnionOrIntersection() ? t.types : []) visit(child);
      for (const child of t.aliasTypeArguments ?? []) visit(child);
      if (t.flags & ts.TypeFlags.Object && t.objectFlags & ts.ObjectFlags.Reference)
        for (const child of checker.getTypeArguments(t)) visit(child);
      // Property expansion is intentionally excluded; declared type references are a separate graph.
    };
    visit(type);
    return { display: checker.typeToString(type,undefined,ts.TypeFormatFlags.NoTruncation),
      origins: [...origins].sort(), boundaries: [...boundaries].sort() };
  }
  function syntaxReferences(node) {
    const references = [];
    const visit = n => {
      const target = ts.isTypeReferenceNode(n) ? n.typeName : ts.isTypeQueryNode(n) ? n.exprName : undefined;
      if (target) {
        const origins = symbolOrigins(resolve(target));
        references.push({ syntax: n.getText(), origins,
          status: origins.length ? 'resolved-declaration' : 'unresolved-symbol' });
      }
      ts.forEachChild(n,visit);
    };
    visit(node);
    return references;
  }
  const calls = inventory.calls.map(call => {
    const source = program.getSourceFile(path.resolve(root,call.file));
    if (!source && /\.[cm]?jsx?$/.test(call.file) && !parsed.options.allowJs)
      return { id: call.id, file: call.file, line: call.line, migration: call.migration,
        operationCandidates: call.matches, status: 'compiler-excluded-source', reason: 'allowJs-disabled',
        explicitTypeArguments: [], arguments: [], result: null, signature: null, assertions: [] };
    requireCondition(source, `Compiler source missing: ${call.file}`);
    let node;
    const find = n => {
      if (n.getStart(source) === call.sourceStart && n.end === call.sourceEnd && ts.isCallExpression(n)) node = n;
      if (!node && n.pos <= call.sourceStart && n.end >= call.sourceEnd) ts.forEachChild(n,find);
    };
    find(source);
    requireCondition(node && sha256(node.getText(source)) === call.callSha256, `Call binding mismatch: ${call.id}`);
    const signature = checker.getResolvedSignature(node);
    const assertions = [];
    const findAssertions = n => {
      if (ts.isAsExpression(n) || ts.isTypeAssertionExpression(n) || ts.isSatisfiesExpression(n))
        assertions.push({ ...location(n), kind: ts.SyntaxKind[n.kind],
          input: describe(checker.getTypeAtLocation(n.expression)), asserted: describe(checker.getTypeFromTypeNode(n.type)) });
      if (!ts.isFunctionLike(n)) ts.forEachChild(n,findAssertions);
    };
    node.arguments.forEach(findAssertions);
    return { id: call.id, file: call.file, line: call.line, migration: call.migration,
      operationCandidates: call.matches,
      explicitTypeArguments: (node.typeArguments ?? []).map(n => ({ syntax: n.getText(source),
        references: syntaxReferences(n), ...describe(checker.getTypeFromTypeNode(n)) })),
      arguments: node.arguments.map((n,index) => ({ index,...describe(checker.getTypeAtLocation(n)) })),
      result: describe(checker.getTypeAtLocation(node)),
      signature: signature ? { declaration: signature.declaration ? location(signature.declaration) : null,
        result: describe(checker.getReturnTypeOfSignature(signature)) } : null,
      assertions, status: signature ? 'compiler-associated' : 'unresolved-signature' };
  });
  const compilerInputs = Object.fromEntries([...inputs].sort(([a],[b]) => a.localeCompare(b,'en')));
  for (const [file,expected] of Object.entries(compilerInputs))
    requireCondition(sha256(ts.sys.readFile(path.resolve(root,file)) ?? '') === expected, `Compiler input changed: ${file}`);
  const nodes = [...declarations.values()].sort((a,b) => a.id.localeCompare(b.id,'en'));
  return { schemaVersion: 1, claim: 'compiler-associated-declaration-provenance-only',
    limitations: ['No proof of HTTP serialization or operation compatibility',
      'No downstream result.json or property data-flow tracking',
      'Generated and dependency declarations are terminal provenance nodes',
      'JavaScript calls excluded by actual compiler options remain explicit boundaries',
      'No complete application semantic diagnostic or runtime reachability claim'],
    typescriptVersion: ts.version, sourceCommit: inventory.sourceCommit,
    inventorySha256: sha256(canonicalJson(inventory)), compilerInputs,
    compilerInputsSha256: sha256(canonicalJson(compilerInputs)),
    summary: { calls: calls.length, declarations: nodes.length, compilerInputs: inputs.size,
      assertions: calls.reduce((n,c) => n+c.assertions.length,0),
      unresolvedSignatures: calls.filter(c => c.status === 'unresolved-signature').length,
      compilerExcludedCalls: calls.filter(c => c.status === 'compiler-excluded-source').length,
      unresolvedReferences: nodes.reduce((n,d) => n+d.references.filter(r => r.status === 'unresolved-symbol').length,0)
        + calls.reduce((n,c) => n+c.explicitTypeArguments.flatMap(t=>t.references).filter(r=>r.status==='unresolved-symbol').length,0) },
    calls, declarations: nodes };
}

export function typeInventory(root, inventory) {
  const git = (...args) => execFileSync('git',['-C',root,...args],{encoding:'utf8'}).trim();
  const commit = git('rev-parse','HEAD');
  requireCondition(commit === inventory.sourceCommit && git('status','--porcelain') === '', 'Type inventory requires the matching clean source checkout');
  const tools = ['type-origins.mjs','contract.mjs','toolchain/package-lock.json'];
  const hashes = () => Object.fromEntries(tools.map(f => [f,sha256(fs.readFileSync(new URL(f,import.meta.url)))]));
  const before = hashes(), result = traceCallTypes(root,inventory);
  requireCondition(git('rev-parse','HEAD') === commit && git('status','--porcelain') === '', 'Source changed during type inventory');
  requireCondition(canonicalJson(before) === canonicalJson(hashes()), 'Type inventory tool inputs changed');
  return { ...result, sourceClaim: 'clean-git-snapshot', toolInputHashes: before, nodeVersion: process.version };
}

if (process.argv[1] && import.meta.url === pathToFileURL(fs.realpathSync(process.argv[1])).href) {
  try {
    const [root,input,output] = process.argv.slice(2);
    requireCondition(process.argv.length === 5 && root && input && output, 'Usage: node type-origins.mjs <repo> <inventory.json> <fresh-output.json>');
    requireCondition(!fs.existsSync(output), 'Type inventory output exists; use a fresh attempt path');
    const result = typeInventory(path.resolve(root),JSON.parse(fs.readFileSync(input,'utf8')));
    fs.mkdirSync(path.dirname(output),{recursive:true});
    fs.writeFileSync(output,canonicalJson(result),{flag:'wx'});
    console.log(JSON.stringify(result.summary));
  } catch (error) { console.error(error.message); process.exitCode=1; }
}
