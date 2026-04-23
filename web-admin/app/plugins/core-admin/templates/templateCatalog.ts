/**
 * Template Catalog metadata and merge helpers.
 *
 * Static entries provide curated UX copy for OSS templates.
 * Runtime-discovered entries from `/api/templates` are merged on top so
 * enterprise-only templates can appear without hardcoding them here.
 */

export type TemplateSource = 'oss' | 'enterprise';

export interface TemplateRegistryEntry {
  id: string;
  name: string;
  relativePath: string;
  namespace: string;
}

export type TemplateCategory = 'All' | 'crm' | 'Operations' | 'HR' | 'Assets' | 'Inventory';

export interface AppTemplate {
  id: string;
  name: string;
  description: string;
  icon: string;
  category: TemplateCategory;
  modelCount: number;
  commandCount: number;
  features: string[];
  pluginPath: string;
  color: string;
  namespace: string;
  source: TemplateSource;
  previewImage?: string;
  tags?: string[];
}

export interface TemplateCategoryDef {
  id: string;
  label: string;
  icon: string;
}

type TemplateOverride = Omit<AppTemplate, 'pluginPath' | 'namespace' | 'source'> & {
  pluginPath?: string;
  namespace?: string;
};

export const TEMPLATE_CATEGORY_TREE: TemplateCategoryDef[] = [
  { id: 'all', label: 'All Templates', icon: 'sparkles' },
  { id: 'crm', label: 'CRM & Sales', icon: 'users' },
  { id: 'Operations', label: 'Operations', icon: 'cog-6-tooth' },
  { id: 'HR', label: 'HR & People', icon: 'user-group' },
  { id: 'Assets', label: 'Asset Management', icon: 'cube' },
  { id: 'Inventory', label: 'Inventory', icon: 'archive-box' },
];

export const TEMPLATE_CATEGORIES: TemplateCategory[] = [
  'All',
  'crm',
  'Operations',
  'HR',
  'Assets',
  'Inventory',
];

const STATIC_TEMPLATE_OVERRIDES: Record<string, TemplateOverride> = {
  'crm-quick-start': {
    id: 'crm-quick-start',
    name: 'CRM Quick Start',
    description:
      'Lightweight CRM for small teams. Includes leads, accounts, contacts, and opportunities with full lifecycle management.',
    icon: '\uD83E\uDD1D',
    category: 'crm',
    modelCount: 4,
    commandCount: 5,
    features: ['Lead Management', 'Sales Pipeline', 'Contact Tracking', 'Opportunity Lifecycle'],
    pluginPath: 'plugins/crm-quick-start',
    color: 'blue',
    namespace: 'tcrm',
    tags: ['crm', 'sales', 'leads', 'pipeline', 'contacts', 'deals'],
  },
  'project-management': {
    id: 'project-management',
    name: 'Project Management',
    description:
      'Simple project and task tracking for teams. Includes projects, tasks, and milestones with full lifecycle management.',
    icon: '\uD83D\uDCCB',
    category: 'Operations',
    modelCount: 3,
    commandCount: 0,
    features: ['Project Tracking', 'Task Management', 'Milestones', 'Team Collaboration'],
    pluginPath: 'plugins/project-management',
    color: 'indigo',
    namespace: 'tpm',
    tags: ['project', 'task', 'milestone', 'team', 'kanban', 'agile'],
  },
  'asset-management': {
    id: 'asset-management',
    name: 'Asset Management',
    description:
      'IT and office asset tracking with maintenance records. Track assets from purchase through retirement.',
    icon: '\uD83D\uDCE6',
    category: 'Assets',
    modelCount: 3,
    commandCount: 0,
    features: ['Asset Registry', 'Category Management', 'Maintenance Tracking', 'Lifecycle Status'],
    pluginPath: 'plugins/asset-management',
    color: 'amber',
    namespace: 'tasset',
    tags: ['asset', 'equipment', 'maintenance', 'IT', 'lifecycle'],
  },
  'simple-inventory': {
    id: 'simple-inventory',
    name: 'Simple Inventory',
    description:
      'Simple buy/sell/stock management for small business. Track products, warehouses, and stock movements.',
    icon: '\uD83D\uDCE6',
    category: 'Inventory',
    modelCount: 4,
    commandCount: 0,
    features: ['Product Catalog', 'Warehouse Management', 'Stock In/Out', 'Movement Tracking'],
    pluginPath: 'plugins/simple-inventory',
    color: 'emerald',
    namespace: 'tinv',
    tags: ['inventory', 'warehouse', 'stock', 'product', 'supply chain'],
  },
  'hr-essentials': {
    id: 'hr-essentials',
    name: 'HR Essentials',
    description:
      'HR management covering employee records, attendance tracking, and leave request workflows with state machines.',
    icon: '\uD83D\uDC65',
    category: 'HR',
    modelCount: 3,
    commandCount: 12,
    features: ['Employee Records', 'Attendance Tracking', 'Leave Requests', 'Approval Workflow'],
    pluginPath: 'plugins/hr-essentials',
    color: 'violet',
    namespace: 'thr',
    tags: ['hr', 'employee', 'attendance', 'leave', 'people', 'approval'],
  },
};

const CURATED_TEMPLATE_ORDER = Object.keys(STATIC_TEMPLATE_OVERRIDES);

export const APP_TEMPLATES: AppTemplate[] = Object.values(STATIC_TEMPLATE_OVERRIDES).map((template) =>
  toAppTemplate({
    id: template.id,
    name: template.name,
    relativePath: template.pluginPath ?? `plugins/${template.id}`,
    namespace: template.namespace ?? '',
  }),
);

export function mergeTemplateCatalog(discovered: TemplateRegistryEntry[]): AppTemplate[] {
  const merged = new Map<string, AppTemplate>();

  for (const discoveredTemplate of discovered) {
    merged.set(discoveredTemplate.id, toAppTemplate(discoveredTemplate));
  }

  for (const template of APP_TEMPLATES) {
    if (!merged.has(template.id)) {
      merged.set(template.id, template);
    }
  }

  return Array.from(merged.values()).sort((left, right) => {
    const leftIndex = CURATED_TEMPLATE_ORDER.indexOf(left.id);
    const rightIndex = CURATED_TEMPLATE_ORDER.indexOf(right.id);
    const leftPinned = leftIndex === -1 ? 1 : 0;
    const rightPinned = rightIndex === -1 ? 1 : 0;
    if (leftPinned !== rightPinned) {
      return leftPinned - rightPinned;
    }
    if (leftPinned === 0 && leftIndex !== rightIndex) {
      return leftIndex - rightIndex;
    }
    return left.name.localeCompare(right.name);
  });
}

export function inferTemplateSource(relativePath: string): TemplateSource {
  return relativePath.startsWith('plugins/templates/') ? 'enterprise' : 'oss';
}

function toAppTemplate(entry: TemplateRegistryEntry): AppTemplate {
  const source = inferTemplateSource(entry.relativePath);
  const override = STATIC_TEMPLATE_OVERRIDES[entry.id];

  return {
    id: entry.id,
    name: override?.name ?? entry.name,
    description: override?.description ?? defaultDescription(entry, source),
    icon: override?.icon ?? defaultIcon(entry, source),
    category: override?.category ?? defaultCategory(entry),
    modelCount: override?.modelCount ?? 0,
    commandCount: override?.commandCount ?? 0,
    features: override?.features ?? defaultFeatures(entry, source),
    pluginPath: override?.pluginPath ?? entry.relativePath,
    color: override?.color ?? defaultColor(entry, source),
    namespace: override?.namespace ?? entry.namespace,
    previewImage: override?.previewImage,
    tags: override?.tags ?? defaultTags(entry, source),
    source,
  };
}

function defaultCategory(entry: TemplateRegistryEntry): TemplateCategory {
  const value = `${entry.id} ${entry.name}`.toLowerCase();
  if (value.includes('crm')) return 'crm';
  if (value.includes('hr')) return 'HR';
  if (value.includes('asset')) return 'Assets';
  if (value.includes('inventory')) return 'Inventory';
  return 'Operations';
}

function defaultColor(entry: TemplateRegistryEntry, source: TemplateSource): string {
  switch (defaultCategory(entry)) {
    case 'crm':
      return 'blue';
    case 'HR':
      return 'violet';
    case 'Assets':
      return source === 'enterprise' ? 'indigo' : 'amber';
    case 'Inventory':
      return 'emerald';
    default:
      return source === 'enterprise' ? 'slate' : 'indigo';
  }
}

function defaultDescription(entry: TemplateRegistryEntry, source: TemplateSource): string {
  const sourceCopy =
    source === 'enterprise'
      ? 'Enterprise-ready template discoverable from the current workspace.'
      : 'Prebuilt business template ready to install into your workspace.';
  return `${entry.name}. ${sourceCopy}`;
}

function defaultFeatures(entry: TemplateRegistryEntry, source: TemplateSource): string[] {
  const category = defaultCategory(entry);
  const features = [`${category} Workflow`, 'Config-driven Setup'];
  if (source === 'enterprise') {
    features.push('Enterprise Edition');
  }
  return features;
}

function defaultTags(entry: TemplateRegistryEntry, source: TemplateSource): string[] {
  return [entry.id, entry.namespace, source, defaultCategory(entry).toLowerCase()];
}

function defaultIcon(entry: TemplateRegistryEntry, source: TemplateSource): string {
  switch (defaultCategory(entry)) {
    case 'crm':
      return '\uD83E\uDD1D';
    case 'HR':
      return '\uD83D\uDC65';
    case 'Assets':
      return '\uD83D\uDCE6';
    case 'Inventory':
      return '\uD83D\uDCE6';
    default:
      return source === 'enterprise' ? '\uD83C\uDFE2' : '\uD83D\uDCCB';
  }
}
