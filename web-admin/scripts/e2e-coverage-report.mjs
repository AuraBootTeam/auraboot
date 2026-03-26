import fs from 'fs';
import path from 'path';
import { execSync } from 'child_process';

const rootDir = process.cwd();
const rawDir = path.join(rootDir, 'test-results/coverage/raw');
const outDir = path.join(rootDir, 'test-results/coverage');
const mergedFile = path.join(outDir, 'coverage-final.json');

if (!fs.existsSync(rawDir)) {
  console.error(`Coverage raw directory not found: ${rawDir}`);
  process.exit(1);
}

const rawFiles = fs.readdirSync(rawDir).filter((name) => name.endsWith('.json'));
if (rawFiles.length === 0) {
  console.error(`No raw coverage files found in: ${rawDir}`);
  process.exit(1);
}

fs.mkdirSync(outDir, { recursive: true });

if (fs.existsSync(mergedFile)) {
  fs.rmSync(mergedFile, { force: true });
}

execSync(`npx nyc merge "${rawDir}" "${mergedFile}"`, { stdio: 'inherit' });
execSync(
  `npx nyc report --temp-dir "${outDir}" --report-dir "${outDir}" --reporter=text-summary --reporter=html --reporter=lcov`,
  { stdio: 'inherit' },
);

console.log(`Coverage report generated: ${outDir}`);
