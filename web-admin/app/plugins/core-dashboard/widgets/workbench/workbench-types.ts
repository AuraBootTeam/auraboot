/**
 * Shared types for workbench widgets.
 */

export interface StatItem {
  value: number | string;
  label: string;
  format?: 'number' | 'currency' | 'percent';
  trend?: {
    direction: 'up' | 'down' | 'flat';
    value: number | string;
    period: 'week' | 'month';
    unit?: 'percent' | 'absolute';
  };
}

export interface WorkbenchStats {
  [key: string]: StatItem;
}

export interface StatsConfig {
  key: string;
  title: string;
  icon?: string;
  gradient: string;
  linkTo?: string;
}
