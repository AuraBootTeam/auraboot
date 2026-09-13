import { createContext } from 'react';

/** Binds chart reads to an accessible saved widget and one viewer visit. */
export interface DashboardQueryBinding {
  dashboardPid: string;
  widgetId: string;
  usageId: string;
}

export const DashboardQueryContext = createContext<DashboardQueryBinding | null>(null);
