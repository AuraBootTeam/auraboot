/**
 * Resolves icon name strings (from menu config / plugin JSON) to Lucide React components.
 *
 * Accepts multiple naming conventions and maps them all to lucide-react:
 * - PascalCase (direct lucide names):        "LayoutDashboard"
 * - Tabler-style prefix:                     "IconWebhook" → "Webhook"
 * - Lucide-style prefix:                     "LucidePuzzle" → "Puzzle"
 * - kebab-case / Ant Design:                 "bar-chart"   → "BarChart"
 * - snake_case:                              "bar_chart"   → "BarChart"
 * - lowercase single word:                   "dashboard"   → "Dashboard" (via alias)
 *
 * No explicit whitelist: any lucide icon name works out of the box. Unknown
 * names fall back to a text abbreviation so plugins never crash on typos.
 */
import React from 'react';
import * as LucideIcons from 'lucide-react';
import type { LucideProps } from 'lucide-react';

type IconComponent = React.ComponentType<LucideProps>;

// Aliases for legacy / non-lucide names used throughout existing plugins and bootstrap.
// Only include entries that cannot be derived by the normalization rules below.
const ALIASES: Record<string, string> = {
  appstore: 'LayoutDashboard',
  apartment: 'Building',
  book: 'BookOpen',
  dashboard: 'LayoutDashboard',
  layout: 'LayoutDashboard',
  login: 'User',
  setting: 'Settings',
  table: 'List',
  team: 'Users',
  thunderbolt: 'TrendingUp',
  tool: 'Wrench',
  'carry-out': 'PackageCheck',
  'clock-circle': 'Clock',
  'deployment-unit': 'GitMerge',
  'file-search': 'FileQuestion',
  field: 'Hash',
  rocket: 'Rocket',
  safety: 'Shield',
  project: 'FolderKanban',
  // Tabler-specific names that do not exist 1:1 in lucide
  IconRobot: 'Cpu',
  IconApi: 'Link',
  IconWebhook: 'Link',
  IconPlayerPlay: 'PlayCircle',
};

function toPascalCase(input: string): string {
  return input
    .split(/[-_\s]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join('');
}

function stripPrefix(name: string): string {
  if (name.startsWith('Icon') && name.length > 4 && name[4] === name[4].toUpperCase()) {
    return name.slice(4);
  }
  if (name.startsWith('Lucide') && name.length > 6 && name[6] === name[6].toUpperCase()) {
    return name.slice(6);
  }
  return name;
}

const cache = new Map<string, IconComponent | null>();

function lookup(rawName: string): IconComponent | null {
  if (cache.has(rawName)) return cache.get(rawName) ?? null;

  const icons = LucideIcons as unknown as Record<string, IconComponent>;
  const candidates: string[] = [];

  const aliased = ALIASES[rawName];
  if (aliased) candidates.push(aliased);

  candidates.push(rawName);
  candidates.push(stripPrefix(rawName));
  candidates.push(toPascalCase(rawName));
  candidates.push(toPascalCase(stripPrefix(rawName)));

  for (const candidate of candidates) {
    const component = icons[candidate];
    if (typeof component === 'function' || (component && typeof component === 'object')) {
      cache.set(rawName, component);
      return component;
    }
  }

  cache.set(rawName, null);
  return null;
}

/**
 * Resolve an icon name string to a React element.
 * Returns a Lucide icon if found, otherwise a text abbreviation in a circle.
 */
export function resolveIcon(
  iconName: string | undefined | null,
  menuName: string,
  size: number = 18,
): React.ReactElement {
  if (iconName) {
    const IconComponent = lookup(iconName);
    if (IconComponent) {
      return <IconComponent size={size} />;
    }
  }

  const char = (menuName || '?').charAt(0).toUpperCase();
  return (
    <span
      className="inline-flex items-center justify-center rounded bg-gray-200 text-xs font-medium text-gray-600 dark:bg-gray-600 dark:text-gray-300"
      style={{ width: size, height: size, fontSize: size * 0.6 }}
    >
      {char}
    </span>
  );
}

/**
 * Check if an icon name resolves to a known component.
 */
export function hasIcon(iconName: string | undefined | null): boolean {
  return !!iconName && !!lookup(iconName);
}
