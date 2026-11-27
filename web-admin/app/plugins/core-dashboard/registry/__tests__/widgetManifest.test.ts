import { describe, expect, it } from 'vitest';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { normalizeChartType } from '~/framework/smart/charts/SharedChartFactory';
import { widgetRegistry } from '../../widgets/widgetRegistry';
import { DRILL_DOWN_WIDGETS, OSS_WIDGETS, supportsDrillDown } from '../widgetManifest';

/**
 * The drill-down capability is a declaration about the renderers. These tests
 * read each renderer's source, so the declaration and the renderers cannot
 * drift apart unnoticed: a renderer that starts or stops calling `onDrillDown`
 * fails here until the declaration is updated.
 */

const here = path.dirname(fileURLToPath(import.meta.url));
const webAdmin = path.resolve(here, '..', '..', '..', '..', '..');
const app = path.join(webAdmin, 'app');
const pluginDir = path.join(app, 'plugins', 'core-dashboard');
const publishedFile = path.resolve(webAdmin, '..', 'plugins', 'schemas', 'dashboard-widget-capabilities.json');

const resolveModule = (specifier: string, fromDir: string) =>
  specifier.startsWith('~/') ? path.join(app, specifier.slice(2)) : path.resolve(fromDir, specifier);

function sourceOfModule(modulePath: string | undefined): string | null {
  if (!modulePath) return null;
  for (const extension of ['.tsx', '.ts']) {
    const file = `${modulePath}${extension}`;
    if (existsSync(file)) return readFileSync(file, 'utf8');
  }
  return null;
}

/** `import { A, B as C } from 'path'`: imported name → module path. */
function importsByName(source: string, fromDir: string): Map<string, string> {
  const byName = new Map<string, string>();
  for (const statement of source.matchAll(/import\s*\{([^}]+)\}\s*from\s*'([^']+)'/g)) {
    for (const name of statement[1].split(',').map((item) => item.trim()).filter(Boolean)) {
      byName.set(name.split(/\s+as\s+/)[0].trim(), resolveModule(statement[2], fromDir));
    }
  }
  return byName;
}

// Framework chart types: `reg('bar', () => import('…/SmartBarChart'))` and `regSync('leads', LeadsWidget)`.
const factoryDir = path.join(app, 'framework', 'smart', 'charts');
const factorySource = readFileSync(path.join(factoryDir, 'SharedChartFactory.ts'), 'utf8');
const factoryImports = importsByName(factorySource, factoryDir);
const factoryComponentOfKey = new Map<string, string>();
for (const registration of factorySource.matchAll(/\breg\('([a-z-]+)',\s*\(\)\s*=>\s*import\('([^']+)'\)/g)) {
  factoryComponentOfKey.set(registration[1], resolveModule(registration[2], factoryDir));
}
for (const registration of factorySource.matchAll(/\bregSync\('([a-z-]+)',\s*(\w+)\)/g)) {
  factoryComponentOfKey.set(registration[1], factoryImports.get(registration[2]) ?? '');
}

// The dashboard plugin's own widgets, registered after the framework's: `registerChartComponent('calendar', CalendarWidget)`.
const entrySource = readFileSync(path.join(pluginDir, 'index.ts'), 'utf8');
const entryImports = importsByName(entrySource, pluginDir);
const dashboardComponentOfKey = new Map<string, string>();
for (const registration of entrySource.matchAll(/registerChartComponent\('([a-z-]+)',\s*(\w+)\)/g)) {
  dashboardComponentOfKey.set(registration[1], entryImports.get(registration[2]) ?? '');
}

/** The module that renders a dashboard widget type: the dashboard's own widget, else the framework's. */
function rendererOf(type: string): string {
  const key = normalizeChartType(type);
  return dashboardComponentOfKey.get(key) ?? factoryComponentOfKey.get(key) ?? '';
}

function rendererCallsOnDrillDown(type: string): boolean {
  const renderer = rendererOf(type);
  const source = sourceOfModule(renderer);
  expect(source, `${type}: its renderer ${path.relative(webAdmin, renderer) || 'is not registered'} is readable`).toBeTruthy();
  return /\bonDrillDown\b/.test(source ?? '');
}

const types = widgetRegistry.getAll().map((definition) => definition.type);

describe('dashboard drill-down capability', () => {
  it('is declared exactly for the widget types whose renderer calls onDrillDown', () => {
    expect(types.length).toBeGreaterThan(30);
    const declared = types.filter((type) => supportsDrillDown(type));
    expect(declared.length).toBeGreaterThan(10);
    for (const type of types) {
      expect(supportsDrillDown(type), `${type} is rendered by ${path.relative(webAdmin, rendererOf(type))}`).toBe(
        rendererCallsOnDrillDown(type),
      );
    }
  });

  it('names registered types only, in alphabetical order, and resolves every registry key', () => {
    expect([...DRILL_DOWN_WIDGETS].sort()).toEqual([...DRILL_DOWN_WIDGETS]);
    for (const type of DRILL_DOWN_WIDGETS) expect(types, `${type} is registered`).toContain(type);
    for (const type of types) expect(rendererOf(type), `${type} resolves to a renderer`).not.toBe('');
    // A type that declares nothing is not mistaken for one that declares drill-down.
    expect(supportsDrillDown('smart-shortcuts')).toBe(false);
    expect(supportsDrillDown('not-a-widget')).toBe(false);
  });

  it('is not affected by the tier list', () => {
    // OSS and enterprise widgets are on both sides of the drill-down line.
    expect(OSS_WIDGETS.some((type) => supportsDrillDown(type))).toBe(true);
    expect(OSS_WIDGETS.some((type) => !supportsDrillDown(type))).toBe(true);
    expect(types.some((type) => !OSS_WIDGETS.includes(type) && supportsDrillDown(type))).toBe(true);
  });

  it('is published for other repositories, and the published file is up to date', () => {
    const published = {
      schemaVersion: 1,
      description:
        'What each dashboard widget type can do. drillDown: the renderer honours the drillDown configuration; ' +
        'a drill-down configured on another type is saved and never opens anything. ' +
        'A product gate reads this to tell a missing drill-down from a widget that has none.',
      source: 'web-admin/app/plugins/core-dashboard/registry/widgetManifest.ts',
      widgets: Object.fromEntries(
        [...types].sort().map((type) => [type, { drillDown: supportsDrillDown(type) }]),
      ),
    };
    if (process.env.UPDATE_DASHBOARD_WIDGET_CAPABILITIES === '1') {
      writeFileSync(publishedFile, `${JSON.stringify(published, null, 2)}\n`);
    }
    expect(existsSync(publishedFile), path.relative(webAdmin, publishedFile)).toBe(true);
    expect(JSON.parse(readFileSync(publishedFile, 'utf8'))).toEqual(published);
  });
});
