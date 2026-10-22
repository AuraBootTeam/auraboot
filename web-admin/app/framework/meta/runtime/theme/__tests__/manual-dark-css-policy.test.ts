import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

const appCssPath = resolve(process.cwd(), 'app/app.css');
const designerStylePaths = [
  'app/plugins/core-designer/components/studio/workbench/styles/command.css',
  'app/plugins/core-designer/components/studio/workbench/styles/drag.css',
  'app/plugins/core-designer/components/studio/workbench/styles/drag-preview.css',
  'app/plugins/core-designer/components/studio/workbench/styles/responsive.css',
  'app/plugins/core-designer/components/studio/workbench/styles/smart-slots.css',
].map((path) => resolve(process.cwd(), path));

function readStyleGraph(
  path: string,
  seen = new Set<string>(),
): Array<{ path: string; css: string }> {
  if (seen.has(path)) return [];
  seen.add(path);
  const css = readFileSync(path, 'utf8');
  const imports = [...css.matchAll(/@import\s+['"](\.[^'"]+\.css)['"]\s*;/g)];
  return [
    { path, css },
    ...imports.flatMap((match) => readStyleGraph(resolve(dirname(path), match[1]), seen)),
  ];
}

describe('manual dark-mode CSS policy', () => {
  it('binds Tailwind dark utilities to the html.dark theme class', () => {
    const appCss = readFileSync(appCssPath, 'utf8');

    expect(appCss).toContain('@custom-variant dark (&:where(.dark, .dark *));');
    expect(appCss).not.toMatch(/@config\s+["'][^"']*tailwind\.config\.[cm]?js["']/);
  });

  it('keeps designer dark styles under the app theme class, not system media', () => {
    const systemMediaOffenders = designerStylePaths
      .flatMap((path) => readStyleGraph(path))
      .filter(({ css }) => /@media\s*\(\s*prefers-color-scheme\s*:\s*dark\s*\)/.test(css))
      .map(({ path }) => path.replace(`${process.cwd()}/`, ''));

    const localVariantOffenders = designerStylePaths
      .flatMap((path) => readStyleGraph(path))
      .filter(({ css }) => /@variant\s+dark\b/.test(css))
      .map(({ path }) => path.replace(`${process.cwd()}/`, ''));

    expect(systemMediaOffenders).toEqual([]);
    expect(localVariantOffenders).toEqual([]);
  });
});
