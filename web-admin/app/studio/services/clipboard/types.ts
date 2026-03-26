/**
 * Clipboard Types
 *
 * Type definitions for clipboard operations.
 *
 * @since 3.2.0
 */

import type { Component, Position } from '~/studio/domain/schema/types';

/**
 * Serialized component for clipboard
 */
export interface SerializedComponent {
  type: string;
  name?: string;
  props: Record<string, any>;
  position?: Position;
  size?: {
    width: number;
    height: number;
    span: number;
  };
  span?: number;
  children?: SerializedComponent[];
}

/**
 * Clipboard data
 */
export interface ClipboardData {
  type: 'component' | 'components';
  data: SerializedComponent[];
  sourcePageId?: string;
  timestamp: number;
}

/**
 * Clipboard operation result
 */
export interface ClipboardResult {
  success: boolean;
  components?: Component[];
  error?: string;
}

/**
 * Paste options
 */
export interface PasteOptions {
  /** Target position (defaults to next empty cell) */
  targetPosition?: Position;
  /** Offset from original position */
  offset?: { row: number; column: number };
  /** Generate new IDs */
  generateNewIds?: boolean;
}
