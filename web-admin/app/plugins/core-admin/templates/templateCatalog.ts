/**
 * Template Catalog — Static metadata for application templates.
 *
 * Each entry maps to a plugin directory under `plugins/templates/`.
 * The install flow POSTs to `/api/plugins/import/import-directory-sync`.
 */

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
  previewImage?: string;
  tags?: string[];
}

export interface TemplateCategoryDef {
  id: string;
  label: string;
  icon: string;
}

export const TEMPLATE_CATEGORY_TREE: TemplateCategoryDef[] = [
  { id: 'all', label: 'All Templates', icon: 'sparkles' },
  { id: 'crm', label: 'CRM & Sales', icon: 'users' },
  { id: 'Operations', label: 'Operations', icon: 'cog-6-tooth' },
  { id: 'HR', label: 'HR & People', icon: 'user-group' },
  { id: 'Assets', label: 'Asset Management', icon: 'cube' },
  { id: 'Inventory', label: 'Inventory', icon: 'archive-box' },
];

export type TemplateCategory = 'All' | 'crm' | 'Operations' | 'HR' | 'Assets' | 'Inventory';

export const TEMPLATE_CATEGORIES: TemplateCategory[] = [
  'All',
  'crm',
  'Operations',
  'HR',
  'Assets',
  'Inventory',
];

export const APP_TEMPLATES: AppTemplate[] = [
  {
    id: 'crm-quick-start',
    name: 'CRM Quick Start',
    description:
      'Lightweight CRM for small teams. Includes leads, accounts, contacts, and opportunities with full lifecycle management.',
    icon: '\uD83E\uDD1D',
    category: 'crm',
    modelCount: 4,
    commandCount: 5,
    features: ['Lead Management', 'Sales Pipeline', 'Contact Tracking', 'Opportunity Lifecycle'],
    pluginPath: 'plugins/templates/crm-quick-start',
    color: 'blue',
    namespace: 'tcrm',
    tags: ['crm', 'sales', 'leads', 'pipeline', 'contacts', 'deals'],
  },
  {
    id: 'project-management',
    name: 'Project Management',
    description:
      'Simple project and task tracking for teams. Includes projects, tasks, and milestones with full lifecycle management.',
    icon: '\uD83D\uDCCB',
    category: 'Operations',
    modelCount: 3,
    commandCount: 0,
    features: ['Project Tracking', 'Task Management', 'Milestones', 'Team Collaboration'],
    pluginPath: 'plugins/templates/project-management',
    color: 'indigo',
    namespace: 'tpm',
    tags: ['project', 'task', 'milestone', 'team', 'kanban', 'agile'],
  },
  {
    id: 'asset-management',
    name: 'Asset Management',
    description:
      'IT and office asset tracking with maintenance records. Track assets from purchase through retirement.',
    icon: '\uD83D\uDCE6',
    category: 'Assets',
    modelCount: 3,
    commandCount: 0,
    features: ['Asset Registry', 'Category Management', 'Maintenance Tracking', 'Lifecycle Status'],
    pluginPath: 'plugins/templates/asset-management',
    color: 'amber',
    namespace: 'tasset',
    tags: ['asset', 'equipment', 'maintenance', 'IT', 'lifecycle'],
  },
  {
    id: 'simple-inventory',
    name: 'Simple Inventory',
    description:
      'Simple buy/sell/stock management for small business. Track products, warehouses, and stock movements.',
    icon: '\uD83D\uDCE6',
    category: 'Inventory',
    modelCount: 4,
    commandCount: 0,
    features: ['Product Catalog', 'Warehouse Management', 'Stock In/Out', 'Movement Tracking'],
    pluginPath: 'plugins/templates/simple-inventory',
    color: 'emerald',
    namespace: 'tinv',
    tags: ['inventory', 'warehouse', 'stock', 'product', 'supply chain'],
  },
  {
    id: 'hr-essentials',
    name: 'HR Essentials',
    description:
      'HR management covering employee records, attendance tracking, and leave request workflows with state machines.',
    icon: '\uD83D\uDC65',
    category: 'HR',
    modelCount: 3,
    commandCount: 12,
    features: ['Employee Records', 'Attendance Tracking', 'Leave Requests', 'Approval Workflow'],
    pluginPath: 'plugins/templates/hr-essentials',
    color: 'violet',
    namespace: 'thr',
    tags: ['hr', 'employee', 'attendance', 'leave', 'people', 'approval'],
  },
];
