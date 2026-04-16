/**
 * Page Mode Service
 *
 * Manages page mode switching and component migration.
 *
 * @since 3.2.0
 */

import type {
  PageMode,
  PageModeConfig,
  ModeSwitchEvent,
  MigrationResult,
  DragItem,
  DropTarget,
  FormLayoutConfig,
} from './types';
import { PAGE_MODES, getModeConfig } from './modes';
import type { Component, Position } from '~/plugins/core-designer/components/studio/workbench/canvas/types';

/**
 * Mode change listener
 */
type ModeChangeListener = (event: ModeSwitchEvent) => void;

/**
 * Page Mode Service Class
 */
export class PageModeService {
  private static instance: PageModeService;

  private currentMode: PageMode = 'form';
  private formLayout: FormLayoutConfig = {
    columns: 2,
    gutter: 16,
    labelPosition: 'top',
  };
  private listeners: Set<ModeChangeListener> = new Set();

  private constructor() {}

  /**
   * Get singleton instance
   */
  public static getInstance(): PageModeService {
    if (!PageModeService.instance) {
      PageModeService.instance = new PageModeService();
    }
    return PageModeService.instance;
  }

  /**
   * Get current mode
   */
  public getMode(): PageMode {
    return this.currentMode;
  }

  /**
   * Get current mode config
   */
  public getModeConfig(): PageModeConfig {
    return getModeConfig(this.currentMode);
  }

  /**
   * Get form layout config
   */
  public getFormLayout(): FormLayoutConfig {
    return { ...this.formLayout };
  }

  /**
   * Set form layout config
   */
  public setFormLayout(layout: Partial<FormLayoutConfig>): void {
    this.formLayout = { ...this.formLayout, ...layout };
  }

  /**
   * Switch page mode
   */
  public switchMode(newMode: PageMode, components: Component[] = []): MigrationResult {
    const previousMode = this.currentMode;

    if (previousMode === newMode) {
      return {
        success: true,
        migratedCount: 0,
        failedComponents: [],
        warnings: [],
      };
    }

    // Migrate components
    const result = this.migrateComponents(components, previousMode, newMode);

    // Update current mode
    this.currentMode = newMode;

    // Emit event
    const event: ModeSwitchEvent = {
      fromMode: previousMode,
      toMode: newMode,
      timestamp: Date.now(),
      componentsMigrated: result.migratedCount > 0,
    };
    this.emit(event);

    return result;
  }

  /**
   * Migrate components between modes
   */
  private migrateComponents(
    components: Component[],
    fromMode: PageMode,
    toMode: PageMode,
  ): MigrationResult {
    const migratedComponents: Component[] = [];
    const failedComponents: string[] = [];
    const warnings: string[] = [];

    components.forEach((component) => {
      try {
        const migrated = this.migrateComponent(component, fromMode, toMode);
        if (migrated) {
          migratedComponents.push(migrated);
        } else {
          failedComponents.push(component.id);
          warnings.push(`Component ${component.id} (${component.type}) could not be migrated`);
        }
      } catch (error) {
        failedComponents.push(component.id);
        warnings.push(`Error migrating component ${component.id}: ${error}`);
      }
    });

    return {
      success: failedComponents.length === 0,
      migratedCount: migratedComponents.length,
      failedComponents,
      warnings,
    };
  }

  /**
   * Migrate a single component
   */
  private migrateComponent(
    component: Component,
    fromMode: PageMode,
    toMode: PageMode,
  ): Component | null {
    // Clone component
    const migrated = { ...component };

    // Adjust position based on mode
    if (toMode === 'grid' && fromMode !== 'grid') {
      // Convert to grid position
      migrated.position = this.convertToGridPosition(component, fromMode);
    } else if (fromMode === 'grid' && toMode !== 'grid') {
      // Convert from grid to sequential position
      migrated.position = this.convertFromGridPosition(component, toMode);
    }

    return migrated;
  }

  /**
   * Convert position to grid coordinates
   */
  private convertToGridPosition(component: Component, fromMode: PageMode): Position {
    const config = getModeConfig(fromMode);
    const gridColumns = 12;

    // Calculate grid position based on original position
    const row = component.position?.row || 0;
    const col = component.position?.column || 0;
    const span = component.span || Math.floor(gridColumns / config.defaultLayout.columns);

    return {
      row,
      column: Math.min(col * span, gridColumns - 1),
    };
  }

  /**
   * Convert from grid position to sequential
   */
  private convertFromGridPosition(component: Component, toMode: PageMode): Position {
    const config = getModeConfig(toMode);
    const columns = config.defaultLayout.columns;

    const row = component.position?.row || 0;
    const col = component.position?.column || 0;

    return {
      row: row,
      column: Math.min(col, columns - 1),
    };
  }

  /**
   * Handle drop based on current mode
   */
  public handleDrop(item: DragItem, target: DropTarget): Component | null {
    const mode = this.currentMode;

    switch (mode) {
      case 'floor':
        return this.handleFloorDrop(item, target);
      case 'form':
        return this.handleFormDrop(item, target);
      case 'grid':
        return this.handleGridDrop(item, target);
      default:
        return null;
    }
  }

  /**
   * Handle drop in floor mode
   */
  private handleFloorDrop(item: DragItem, target: DropTarget): Component | null {
    // In floor mode, components go into blocks
    if (target.type !== 'block' && target.type !== 'canvas') {
      console.warn('[PageModeService] Floor mode requires block target');
      return null;
    }

    const component = this.createComponentFromDragItem(item);
    if (!component) return null;

    // Set position based on insert index
    component.position = {
      row: target.insertIndex || 0,
      column: 0,
    };

    return component;
  }

  /**
   * Handle drop in form mode
   */
  private handleFormDrop(item: DragItem, target: DropTarget): Component | null {
    const component = this.createComponentFromDragItem(item);
    if (!component) return null;

    // Calculate position based on form layout
    const { columns } = this.formLayout;
    const insertIndex = target.insertIndex || 0;

    component.position = {
      row: Math.floor(insertIndex / columns),
      column: insertIndex % columns,
    };

    // Set span based on form columns
    component.span = 1;

    return component;
  }

  /**
   * Handle drop in grid mode
   */
  private handleGridDrop(item: DragItem, target: DropTarget): Component | null {
    const component = this.createComponentFromDragItem(item);
    if (!component) return null;

    // Use grid position or calculate from drop position
    if (target.gridPosition) {
      component.position = {
        row: target.gridPosition.row,
        column: target.gridPosition.column,
      };
    } else if (target.position) {
      // Calculate grid position from pixel position
      // This would need canvas dimensions to calculate properly
      component.position = {
        row: Math.floor(target.position.y / 50), // Approximate row height
        column: Math.floor(target.position.x / 100), // Approximate column width
      };
    }

    // Default span for grid mode
    component.span = 3;

    return component;
  }

  /**
   * Create component from drag item
   */
  private createComponentFromDragItem(item: DragItem): Component | null {
    const id = `comp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;

    if (item.type === 'field' && item.fieldPath) {
      // Create input component for field
      return {
        id,
        type: 'SmartInput',
        props: {
          field: item.fieldPath,
          label: item.fieldPath.split('.').pop(),
        },
        position: { row: 0, column: 0 },
      };
    }

    if (item.type === 'component' && item.componentType) {
      // Create component of specified type
      return {
        id,
        type: item.componentType,
        props: { ...item.data },
        position: { row: 0, column: 0 },
      };
    }

    return null;
  }

  /**
   * Subscribe to mode changes
   */
  public subscribe(listener: ModeChangeListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /**
   * Emit mode change event
   */
  private emit(event: ModeSwitchEvent): void {
    this.listeners.forEach((listener) => {
      try {
        listener(event);
      } catch (error) {
        console.error('[PageModeService] Listener error:', error);
      }
    });
  }

  /**
   * Check if a capability is supported in current mode
   */
  public supportsCapability(capability: keyof PageModeConfig['capabilities']): boolean {
    const config = getModeConfig(this.currentMode);
    return !!config.capabilities[capability];
  }
}

// Export singleton instance
export const pageModeService = PageModeService.getInstance();

export default pageModeService;
