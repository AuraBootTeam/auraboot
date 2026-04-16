/**
 * Clipboard Manager
 *
 * Manages component copy, cut, and paste operations.
 *
 * @since 3.2.0
 */

import type { Component, Position } from '~/plugins/core-designer/components/studio/workbench/canvas/types';
import type { ClipboardData, SerializedComponent, ClipboardResult, PasteOptions } from './types';

/**
 * Generate unique ID
 */
function generateId(): string {
  return `comp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Clipboard Manager Class
 */
export class ClipboardManager {
  private static instance: ClipboardManager;
  private clipboard: ClipboardData | null = null;
  private cutComponentIds: Set<string> = new Set();

  private constructor() {}

  /**
   * Get singleton instance
   */
  public static getInstance(): ClipboardManager {
    if (!ClipboardManager.instance) {
      ClipboardManager.instance = new ClipboardManager();
    }
    return ClipboardManager.instance;
  }

  /**
   * Copy components to clipboard
   */
  public copy(components: Component[], pageId?: string): void {
    if (components.length === 0) return;

    this.clipboard = {
      type: components.length === 1 ? 'component' : 'components',
      data: components.map((c) => this.serialize(c)),
      sourcePageId: pageId,
      timestamp: Date.now(),
    };

    // Clear cut state when copying
    this.cutComponentIds.clear();
  }

  /**
   * Cut components to clipboard
   */
  public cut(components: Component[], pageId?: string): void {
    if (components.length === 0) return;

    this.copy(components, pageId);

    // Mark components as cut
    this.cutComponentIds = new Set(components.map((c) => c.id));
  }

  /**
   * Paste components from clipboard
   */
  public paste(options: PasteOptions = {}): ClipboardResult {
    if (!this.clipboard) {
      return { success: false, error: 'Clipboard is empty' };
    }

    const { offset = { row: 1, column: 0 }, generateNewIds = true } = options;

    try {
      const components = this.clipboard.data.map((serialized, index) => {
        const position = this.calculatePosition(serialized, options.targetPosition, index, offset);
        return this.deserialize(serialized, position, generateNewIds);
      });

      return { success: true, components };
    } catch (error) {
      console.error('[ClipboardManager] Paste error:', error);
      return {
        success: false,
        error: error instanceof Error ? error.message : 'Failed to paste',
      };
    }
  }

  /**
   * Duplicate components (copy + paste in place)
   */
  public duplicate(components: Component[], pageId?: string): ClipboardResult {
    this.copy(components, pageId);
    return this.paste({ offset: { row: 1, column: 0 } });
  }

  /**
   * Check if clipboard has content
   */
  public hasContent(): boolean {
    return this.clipboard !== null;
  }

  /**
   * Get clipboard content count
   */
  public getContentCount(): number {
    return this.clipboard?.data.length || 0;
  }

  /**
   * Check if components were cut (for visual indication)
   */
  public isCut(componentId: string): boolean {
    return this.cutComponentIds.has(componentId);
  }

  /**
   * Get cut component IDs
   */
  public getCutComponentIds(): string[] {
    return Array.from(this.cutComponentIds);
  }

  /**
   * Clear cut state
   */
  public clearCutState(): void {
    this.cutComponentIds.clear();
  }

  /**
   * Clear clipboard
   */
  public clear(): void {
    this.clipboard = null;
    this.cutComponentIds.clear();
  }

  /**
   * Serialize component for clipboard
   */
  private serialize(component: Component): SerializedComponent {
    return {
      type: component.type,
      name: component.name,
      props: { ...component.props },
      position: component.position ? { ...component.position } : undefined,
      size: component.size ? { ...component.size } : undefined,
      span: component.span,
      children: component.children?.map((c) => this.serialize(c)),
    };
  }

  /**
   * Deserialize component from clipboard
   */
  private deserialize(
    serialized: SerializedComponent,
    position: Position,
    generateNewId: boolean,
  ): Component {
    return {
      id: generateNewId ? generateId() : serialized.props?.id || generateId(),
      type: serialized.type,
      name: serialized.name,
      props: { ...serialized.props },
      position,
      size: serialized.size ? { ...serialized.size } : undefined,
      span: serialized.span,
      children: serialized.children?.map((c) =>
        this.deserialize(c, c.position ?? { row: 0, column: 0 }, generateNewId),
      ),
    };
  }

  /**
   * Calculate position for pasted component
   */
  private calculatePosition(
    serialized: SerializedComponent,
    targetPosition?: Position,
    index: number = 0,
    offset: { row: number; column: number } = { row: 1, column: 0 },
  ): Position {
    if (targetPosition) {
      return {
        row: targetPosition.row + index,
        column: targetPosition.column,
      };
    }

    const originalRow = serialized.position?.row ?? 0;
    const originalColumn = serialized.position?.column ?? 0;

    return {
      row: originalRow + offset.row,
      column: Math.max(0, Math.min(11, originalColumn + offset.column)),
    };
  }
}

// Export singleton instance
export const clipboardManager = ClipboardManager.getInstance();

export default clipboardManager;
