import type { MegaMenuColumn } from './MegaMenu';
import {
  CodeIcon, DesignIcon, PipelineIcon, PluginIcon,
  BotIcon, AgentIcon, ChartIcon,
  ShieldIcon, BuildingIcon, WorkflowIcon,
  UsersIcon, CartIcon, WalletIcon,
  ChipIcon, MountainIcon,
  KanbanIcon, BoxIcon, FileIcon,
  BookIcon, RocketIcon, ApiIcon,
  TerminalIcon, GithubIcon,
} from './MegaMenuIcons';

export interface NavItem {
  label: string;
  href?: string;
  columns?: MegaMenuColumn[];
}

export const NAV_CONFIG: NavItem[] = [
  {
    label: 'Products',
    columns: [
      {
        title: 'Core Platform',
        items: [
          { icon: <CodeIcon />, title: 'DSL Engine', description: 'Model-driven application development', href: '/#features' },
          { icon: <DesignIcon />, title: 'Page Designer', description: 'Visual drag-and-drop page builder', href: '/#features' },
          { icon: <PipelineIcon />, title: 'Command Pipeline', description: '20-stage execution engine', href: '/#features' },
          { icon: <PluginIcon />, title: 'Plugin System', description: '27+ extensible plugins', href: '/plugins' },
        ],
      },
      {
        title: 'AI & Agent',
        items: [
          { icon: <BotIcon />, title: 'AI Copilot', description: 'Context-aware AI assistant', href: '/#features' },
          { icon: <AgentIcon />, title: 'Agent Control Plane', description: 'Autonomous task execution', href: '/#features' },
          { icon: <ChartIcon />, title: 'AI Lead Scoring', description: 'Intelligent lead qualification', href: '/#features' },
        ],
      },
      {
        title: 'Enterprise',
        items: [
          { icon: <ShieldIcon />, title: 'RBAC Permissions', description: 'Role-based access control', href: '/#features' },
          { icon: <BuildingIcon />, title: 'Multi-Tenant', description: 'Deploy once, serve many', href: '/#features' },
          { icon: <WorkflowIcon />, title: 'BPM Workflow', description: 'Process automation and approval', href: '/#features' },
        ],
      },
    ],
  },
  {
    label: 'Solutions',
    columns: [
      {
        title: 'Business',
        items: [
          { icon: <UsersIcon />, title: 'crm', description: 'Customer relationship management', href: '/plugins/crm' },
          { icon: <CartIcon />, title: 'Sales & Procurement', description: 'Order and supply chain', href: '/plugins/sales' },
          { icon: <WalletIcon />, title: 'Finance & Inventory', description: 'Financial and stock management', href: '/plugins/finance' },
        ],
      },
      {
        title: 'Industry',
        items: [
          { icon: <ChipIcon />, title: 'PCBA Manufacturing', description: 'Complete ERP with 126+ models', href: '/plugins/pcba-solution' },
          { icon: <MountainIcon />, title: 'Quarry Management', description: 'Operations, safety, contracts', href: '/plugins/quarry-solution' },
        ],
      },
      {
        title: 'Management',
        items: [
          { icon: <KanbanIcon />, title: 'Project Management', description: 'Tasks, Gantt, Kanban boards', href: '/plugins/project-management' },
          { icon: <BoxIcon />, title: 'Asset Management', description: 'Asset tracking and maintenance', href: '/plugins/asset-management' },
          { icon: <FileIcon />, title: 'Document & Knowledge', description: 'Document management system', href: '/plugins/doc-knowledge' },
        ],
      },
    ],
  },
  {
    label: 'Developers',
    columns: [
      {
        title: 'Learn',
        items: [
          { icon: <BookIcon />, title: 'Documentation', description: 'Guides, tutorials, and references', href: '/docs' },
          { icon: <RocketIcon />, title: 'Quick Start', description: 'Get running in 5 minutes', href: '/docs/getting-started/quick-start' },
          { icon: <ApiIcon />, title: 'API Reference', description: 'Interactive REST API documentation (Swagger)', href: '/settings/api-docs' },
        ],
      },
      {
        title: 'Build',
        items: [
          { icon: <PluginIcon />, title: 'Plugin SDK', description: 'Build your own plugins', href: '/docs/guides/plugin-development' },
          { icon: <TerminalIcon />, title: 'CLI Tools', description: 'Command-line interface', href: '/docs/guides/plugin-development' },
          { icon: <GithubIcon />, title: 'GitHub', description: 'Source code and issues', href: 'https://github.com/AuraBootTeam/AuraBoot', external: true },
        ],
      },
    ],
  },
  { label: 'Pricing', href: '/pricing' },
  { label: 'Blog', href: '/blog' },
];
