import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

const appCssPath = resolve(process.cwd(), 'app/app.css');
const designerStylePaths = [
  'app/plugins/core-designer/components/studio/workbench/styles/command.css',
  'app/plugins/core-designer/components/studio/workbench/styles/drag.css',
  'app/plugins/core-designer/components/studio/workbench/styles/drag-preview.css',
  'app/plugins/core-designer/components/studio/workbench/styles/responsive.css',
  'app/plugins/core-designer/components/studio/workbench/styles/smart-slots.css',
].map((path) => resolve(process.cwd(), path));

describe('manual dark-mode CSS policy', () => {
  it('binds Tailwind dark utilities to the html.dark theme class', () => {
    const appCss = readFileSync(appCssPath, 'utf8');

    expect(appCss).toContain('@custom-variant dark (&:where(.dark, .dark *));');
    expect(appCss).not.toMatch(/@config\s+["'][^"']*tailwind\.config\.[cm]?js["']/);
  });

  it('keeps designer dark styles under the app theme class, not system media', () => {
    const systemMediaOffenders = designerStylePaths
      .map((path) => ({
        path,
        css: readFileSync(path, 'utf8'),
      }))
      .filter(({ css }) => /@media\s*\(\s*prefers-color-scheme\s*:\s*dark\s*\)/.test(css))
      .map(({ path }) => path.replace(`${process.cwd()}/`, ''));

    const localVariantOffenders = designerStylePaths
      .map((path) => ({
        path,
        css: readFileSync(path, 'utf8'),
      }))
      .filter(({ css }) => /@variant\s+dark\b/.test(css))
      .map(({ path }) => path.replace(`${process.cwd()}/`, ''));

    expect(systemMediaOffenders).toEqual([]);
    expect(localVariantOffenders).toEqual([]);
  });
});
