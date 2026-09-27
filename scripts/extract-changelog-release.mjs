#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';

function usage() {
  console.error('Usage: node scripts/extract-changelog-release.mjs <version> [--changelog CHANGELOG.md] [--out FILE]');
}

const args = process.argv.slice(2);
const version = args[0];

if (!version || version.startsWith('-')) {
  usage();
  process.exit(2);
}

let changelogPath = 'CHANGELOG.md';
let outPath = null;

for (let i = 1; i < args.length; i += 1) {
  const arg = args[i];
  if (arg === '--changelog' && args[i + 1]) {
    changelogPath = args[i + 1];
    i += 1;
  } else if (arg === '--out' && args[i + 1]) {
    outPath = args[i + 1];
    i += 1;
  } else {
    usage();
    process.exit(2);
  }
}

const changelog = fs.readFileSync(changelogPath, 'utf8');
const headingPattern = new RegExp(`^## \\[${escapeRegExp(version)}\\](?: - .*?)?\\s*$`, 'm');
const headingMatch = changelog.match(headingPattern);

if (!headingMatch || headingMatch.index === undefined) {
  console.error(`ERROR: release ${version} was not found in ${changelogPath}`);
  process.exit(1);
}

const sectionStart = headingMatch.index + headingMatch[0].length;
const rest = changelog.slice(sectionStart);
const sectionEnd = firstMatchIndex(rest, [/^---\s*$/m, /^##\s+/m]);
const rawSection = sectionEnd === -1 ? rest : rest.slice(0, sectionEnd);
const body = trimHorizontalRules(rawSection).trim();

if (!body) {
  console.error(`ERROR: release ${version} section is empty in ${changelogPath}`);
  process.exit(1);
}

const title = `AuraBoot ${version}`;
const output = `# ${title}\n\n${body}\n`;

if (outPath) {
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, output);
} else {
  process.stdout.write(output);
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function trimHorizontalRules(value) {
  return value
    .replace(/^\s*---\s*/u, '')
    .replace(/\s*---\s*$/u, '');
}

function firstMatchIndex(value, patterns) {
  const indexes = patterns
    .map((pattern) => {
      const match = value.match(pattern);
      return match && match.index !== undefined ? match.index : -1;
    })
    .filter((index) => index >= 0);

  return indexes.length > 0 ? Math.min(...indexes) : -1;
}
