const fs = require('fs');
const path = require('path');
const ts = require('typescript');

const projectRoot = process.cwd();
const appRoot = path.join(projectRoot, 'app');

const allowedExtensions = new Set([
  '.ts',
  '.tsx',
  '.js',
  '.jsx',
  '.cjs',
  '.mjs',
  '.cts',
  '.mts',
]);

const fileCount = { scanned: 0, updated: 0 };

function walkDir(dirPath, files = []) {
  const entries = fs.readdirSync(dirPath, { withFileTypes: true });
  for (const entry of entries) {
    if (entry.name === 'node_modules') continue;
    const fullPath = path.join(dirPath, entry.name);
    if (entry.isDirectory()) {
      walkDir(fullPath, files);
    } else if (allowedExtensions.has(path.extname(entry.name))) {
      files.push(fullPath);
    }
  }
  return files;
}

function getScriptKind(filePath) {
  const ext = path.extname(filePath);
  switch (ext) {
    case '.ts':
    case '.cts':
      return ts.ScriptKind.TS;
    case '.tsx':
      return ts.ScriptKind.TSX;
    case '.js':
    case '.cjs':
      return ts.ScriptKind.JS;
    case '.jsx':
      return ts.ScriptKind.JSX;
    case '.mjs':
    case '.mts':
      return ts.ScriptKind.JS;
    default:
      return ts.ScriptKind.Unknown;
  }
}

function toAliasPath(filePath, specifier) {
  if (!specifier.startsWith('.')) return null;
  const resolved = path.resolve(path.dirname(filePath), specifier);
  if (!resolved.startsWith(appRoot)) return null;
  let relative = path.relative(appRoot, resolved);
  if (!relative) {
    return '~/';
  }
  const normalized = relative.split(path.sep).join('/');
  return `~/${normalized}`;
}

function collectReplacements(sourceFile, filePath, sourceText) {
  const replacements = [];

  function addReplacement(node, newSpecifier) {
    if (!newSpecifier) return;
    const start = node.getStart(sourceFile);
    const end = node.getEnd();
    const current = sourceText.slice(start, end);
    const quote = current[0];
    const replacement = `${quote}${newSpecifier}${quote}`;
    replacements.push({ start, end, replacement });
  }

  function visit(node) {
    if ((ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) && node.moduleSpecifier && ts.isStringLiteral(node.moduleSpecifier)) {
      const newSpecifier = toAliasPath(filePath, node.moduleSpecifier.text);
      if (newSpecifier) {
        addReplacement(node.moduleSpecifier, newSpecifier);
      }
    } else if (ts.isCallExpression(node)) {
      const [arg] = node.arguments;
      const isRequire = ts.isIdentifier(node.expression) && node.expression.text === 'require';
      const isDynamicImport = node.expression.kind === ts.SyntaxKind.ImportKeyword;
      if ((isRequire || isDynamicImport) && arg && ts.isStringLiteral(arg)) {
        const newSpecifier = toAliasPath(filePath, arg.text);
        if (newSpecifier) {
          addReplacement(arg, newSpecifier);
        }
      }
    } else if (ts.isImportEqualsDeclaration(node)) {
      if (
        node.moduleReference &&
        ts.isExternalModuleReference(node.moduleReference) &&
        node.moduleReference.expression &&
        ts.isStringLiteral(node.moduleReference.expression)
      ) {
        const newSpecifier = toAliasPath(filePath, node.moduleReference.expression.text);
        if (newSpecifier) {
          addReplacement(node.moduleReference.expression, newSpecifier);
        }
      }
    }

    ts.forEachChild(node, visit);
  }

  visit(sourceFile);
  return replacements;
}

function applyReplacements(sourceText, replacements) {
  if (!replacements.length) return sourceText;
  let result = sourceText;
  const sorted = replacements.sort((a, b) => b.start - a.start);
  for (const { start, end, replacement } of sorted) {
    result = result.slice(0, start) + replacement + result.slice(end);
  }
  return result;
}

function processFile(filePath) {
  fileCount.scanned += 1;
  const sourceText = fs.readFileSync(filePath, 'utf8');
  const sourceFile = ts.createSourceFile(filePath, sourceText, ts.ScriptTarget.Latest, true, getScriptKind(filePath));
  const replacements = collectReplacements(sourceFile, filePath, sourceText);
  if (!replacements.length) {
    return;
  }
  const updated = applyReplacements(sourceText, replacements);
  if (updated !== sourceText) {
    fs.writeFileSync(filePath, updated);
    fileCount.updated += 1;
  }
}

function main() {
  if (!fs.existsSync(appRoot)) {
    console.error('Failed to locate app directory at', appRoot);
    process.exit(1);
  }

  const files = walkDir(appRoot);
  for (const file of files) {
    processFile(file);
  }

  console.log(`Processed ${fileCount.scanned} files, updated ${fileCount.updated}.`);
}

main();
