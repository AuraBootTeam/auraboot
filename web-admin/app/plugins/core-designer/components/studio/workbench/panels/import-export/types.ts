/**
 * Import/Export Types
 *
 * Types for page import and export functionality.
 *
 * @since 3.2.0
 */

/**
 * Export format
 */
export type ExportFormat = 'json' | 'yaml';

/**
 * Export options
 */
export interface ExportOptions {
  /** Export format */
  format: ExportFormat;
  /** Include metadata */
  includeMetadata: boolean;
  /** Pretty print output */
  prettyPrint: boolean;
  /** Include version history */
  includeVersionHistory: boolean;
}

/**
 * Exported page data structure
 */
export interface ExportedPageData {
  /** Export version */
  exportVersion: string;
  /** Export timestamp */
  exportedAt: string;
  /** Page metadata */
  metadata?: {
    title: string;
    description?: string;
    version: string;
    createdAt: string;
    updatedAt: string;
    tags?: string[];
  };
  /** Page schema */
  schema: Record<string, unknown>;
  /** Version history (optional) */
  versionHistory?: Array<{
    version: string;
    timestamp: string;
    operation: string;
  }>;
}

/**
 * Import validation result
 */
export interface ImportValidationResult {
  /** Is valid */
  isValid: boolean;
  /** Validation errors */
  errors: string[];
  /** Validation warnings */
  warnings: string[];
  /** Parsed data (if valid) */
  data?: ExportedPageData;
}

/**
 * Import options
 */
export interface ImportOptions {
  /** Overwrite existing page */
  overwriteExisting: boolean;
  /** Import as new page */
  importAsNew: boolean;
  /** Custom title (for import as new) */
  customTitle?: string;
}

/**
 * Default export options
 */
export const DEFAULT_EXPORT_OPTIONS: ExportOptions = {
  format: 'json',
  includeMetadata: true,
  prettyPrint: true,
  includeVersionHistory: false,
};

/**
 * Current export version
 */
export const EXPORT_VERSION = '1.0.0';
