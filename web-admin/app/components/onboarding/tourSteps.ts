/**
 * Tour step definitions for the Product Tour.
 *
 * Each step targets a CSS selector in the layout and provides
 * i18n-friendly English text. The i18n system handles translations.
 */

export type TourPlacement = 'top' | 'bottom' | 'left' | 'right';

export interface TourStep {
  id: string;
  target: string;
  title: string;
  content: string;
  placement: TourPlacement;
}

export const TOUR_STEPS: TourStep[] = [
  {
    id: 'sidebar',
    target: '[data-testid="left-sidebar"]',
    title: 'Navigation Sidebar',
    content:
      'Access all your applications, pages, and settings from the sidebar. Menus are organized by module and expand on hover.',
    placement: 'right',
  },
  {
    id: 'dashboards',
    target: '[data-testid="menu-dashboards"], [href="/dashboards"]',
    title: 'Dashboards',
    content:
      'View analytics dashboards with real-time data. Drag tabs to reorder, and use the dashboard designer to create custom views.',
    placement: 'right',
  },
  {
    id: 'aurabot',
    target: '[data-testid="aurabot-toggle"], [data-testid="header-ai-btn"]',
    title: 'AuraBot AI Assistant',
    content:
      'Ask AuraBot to help you find information, create records, or automate tasks. It understands your data models and business logic.',
    placement: 'bottom',
  },
  {
    id: 'templates',
    target: '[data-testid="menu-templates"], [href="/admin/templates"]',
    title: 'Application Templates',
    content:
      'Install pre-built application templates like CRM, Project Management, or HR with one click. Each template includes models, pages, and workflows.',
    placement: 'right',
  },
  {
    id: 'settings',
    target: '[data-testid="menu-settings"], [data-testid="header-settings"]',
    title: 'Settings & Configuration',
    content:
      'Manage your tenant settings, user accounts, roles, permissions, and system configuration from the admin panel.',
    placement: 'bottom',
  },
];
