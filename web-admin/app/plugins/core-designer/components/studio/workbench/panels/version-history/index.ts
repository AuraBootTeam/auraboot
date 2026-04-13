/**
 * Version History Module
 *
 * Version history panel and related utilities.
 *
 * @since 3.2.0
 */

// Types
export type {
  VersionEntry,
  VersionComparison,
  VersionChange,
  VersionOperation,
  ViewMode,
} from './types';

export { OPERATION_INFO } from './types';

// Components
export { VersionHistoryPanel, default } from './VersionHistoryPanel';
export type { VersionHistoryPanelProps } from './VersionHistoryPanel';
