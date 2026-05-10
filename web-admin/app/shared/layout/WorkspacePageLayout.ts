import { cn } from '~/utils/cn';

export type WorkspacePageSection =
  | 'header'
  | 'headerCompact'
  | 'content'
  | 'contentCompact'
  | 'contentPadded'
  | 'contentRelaxed';

export const WORKSPACE_PAGE_CLASSES: Record<WorkspacePageSection, string> = {
  header: 'mx-auto w-full px-6 py-5',
  headerCompact: 'mx-auto w-full px-4 sm:px-6 lg:px-8',
  content: 'mx-auto w-full px-6 py-6',
  contentCompact: 'mx-auto w-full px-4 py-6 sm:px-6 lg:px-8',
  contentPadded: 'mx-auto w-full p-6',
  contentRelaxed: 'mx-auto w-full px-6 py-8',
};

export function workspacePageClassName(section: WorkspacePageSection, className?: string) {
  return cn(WORKSPACE_PAGE_CLASSES[section], className);
}
