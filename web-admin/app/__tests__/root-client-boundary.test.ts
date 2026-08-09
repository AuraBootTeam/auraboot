import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

function collectProductionSources(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      return entry.name === '__tests__' ? [] : collectProductionSources(path);
    }
    return /\.tsx?$/.test(entry.name) && entry.name !== 'root.tsx' ? [path] : [];
  });
}

describe('root client boundary', () => {
  it('keeps client modules from importing the server-backed root route', () => {
    const offenders = collectProductionSources('app').filter((path) => {
      const source = readFileSync(path, 'utf8');
      return /(?:from\s*|import\s*\(\s*)['"]~\/root['"]/.test(source);
    });

    expect(offenders).toEqual([]);
  });
});
