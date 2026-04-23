/**
 * Binding Sync Manager
 *
 * Manages synchronization between fields and components.
 *
 * @since 3.2.0
 */

import { fieldBindingService } from './FieldBindingService';
import type { FieldBinding, FieldChanges, ViewModelFieldInfo } from './types';

/**
 * Sync options
 */
interface SyncOptions {
  /** Debounce time in ms */
  debounceMs?: number;
  /** Enable batch sync */
  batchSync?: boolean;
}

/**
 * Pending sync item
 */
interface PendingSyncItem {
  binding: FieldBinding;
  changes: FieldChanges;
  timestamp: number;
}

/**
 * Binding Sync Manager Class
 */
export class BindingSyncManager {
  private static instance: BindingSyncManager;

  private options: Required<SyncOptions>;
  private pendingSync: Map<string, PendingSyncItem> = new Map();
  private syncTimer: ReturnType<typeof setTimeout> | null = null;

  private componentUpdater:
    | ((componentId: string, updates: Record<string, unknown>) => void)
    | null = null;

  private constructor(options: SyncOptions = {}) {
    this.options = {
      debounceMs: 100,
      batchSync: true,
      ...options,
    };
  }

  /**
   * Get singleton instance
   */
  public static getInstance(options?: SyncOptions): BindingSyncManager {
    if (!BindingSyncManager.instance) {
      BindingSyncManager.instance = new BindingSyncManager(options);
    }
    return BindingSyncManager.instance;
  }

  /**
   * Set the component updater function (from designer store)
   */
  public setComponentUpdater(
    updater: (componentId: string, updates: Record<string, unknown>) => void,
  ): void {
    this.componentUpdater = updater;
  }

  /**
   * Handle field change and queue sync
   */
  public onFieldChanged(fieldPath: string, changes: FieldChanges): void {
    const bindings = fieldBindingService.getBindingsForField(fieldPath);
    if (bindings.length === 0) return;

    bindings.forEach((binding) => {
      if (!binding.syncEnabled) return;

      const key = `${binding.id}`;
      this.pendingSync.set(key, {
        binding,
        changes,
        timestamp: Date.now(),
      });
    });

    this.scheduleSync();
  }

  /**
   * Handle component change and reverse sync to field
   */
  public onComponentChanged(componentId: string, propertyPath: string, _value: unknown): void {
    const bindings = fieldBindingService.getBindingsForComponent(componentId);
    if (bindings.length === 0) return;

    const matchingBinding = bindings.find(
      (b) => b.propertyPath === propertyPath && b.mode === 'two-way',
    );

    if (matchingBinding) {
      // Emit event for form value update
    }
  }

  /**
   * Schedule sync with debounce
   */
  private scheduleSync(): void {
    if (this.syncTimer) {
      clearTimeout(this.syncTimer);
    }

    this.syncTimer = setTimeout(() => {
      this.executeSync();
    }, this.options.debounceMs);
  }

  /**
   * Execute pending syncs
   */
  private executeSync(): void {
    if (this.pendingSync.size === 0) return;
    if (!this.componentUpdater) {
      console.warn('[BindingSyncManager] No component updater set');
      return;
    }

    if (this.options.batchSync) {
      // Group by component ID for batch updates
      const byComponent = new Map<string, Record<string, unknown>>();

      this.pendingSync.forEach((item) => {
        const { binding, changes } = item;
        let updates = byComponent.get(binding.componentId);
        if (!updates) {
          updates = {};
          byComponent.set(binding.componentId, updates);
        }

        // Map field changes to component props
        const propUpdates = this.mapChangesToProps(changes, binding.propertyPath);
        Object.assign(updates, propUpdates);
      });

      // Apply batch updates
      byComponent.forEach((updates, componentId) => {
        this.componentUpdater?.(componentId, updates);
      });
    } else {
      // Individual updates
      this.pendingSync.forEach((item) => {
        const { binding, changes } = item;
        const propUpdates = this.mapChangesToProps(changes, binding.propertyPath);
        this.componentUpdater?.(binding.componentId, propUpdates);
      });
    }

    this.pendingSync.clear();
    this.syncTimer = null;
  }

  /**
   * Map field changes to component prop updates
   */
  private mapChangesToProps(
    changes: FieldChanges,
    _propertyPath: string,
  ): Record<string, unknown> {
    const props: Record<string, unknown> = {};

    if (changes.label !== undefined) {
      props.label = changes.label;
    }

    if (changes.required !== undefined) {
      props.required = changes.required;
    }

    if (changes.readonly !== undefined) {
      props.readOnly = changes.readonly;
    }

    if (changes.disabled !== undefined) {
      props.disabled = changes.disabled;
    }

    if (changes.visible !== undefined) {
      props.visible = changes.visible;
    }

    if (changes.options !== undefined) {
      props.options = changes.options;
    }

    if (changes.validation !== undefined) {
      props.rules = this.mapValidationRules(changes.validation);
    }

    return props;
  }

  /**
   * Map validation rules to component format
   */
  private mapValidationRules(
    rules: Array<{ type: string; value?: unknown; message?: string }>,
  ): Record<string, unknown>[] {
    return rules.map((rule) => ({
      validator: rule.type,
      value: rule.value,
      message: rule.message,
    }));
  }

  /**
   * Force immediate sync
   */
  public flush(): void {
    if (this.syncTimer) {
      clearTimeout(this.syncTimer);
      this.syncTimer = null;
    }
    this.executeSync();
  }

  /**
   * Cancel pending syncs
   */
  public cancel(): void {
    if (this.syncTimer) {
      clearTimeout(this.syncTimer);
      this.syncTimer = null;
    }
    this.pendingSync.clear();
  }

  /**
   * Get pending sync count
   */
  public getPendingCount(): number {
    return this.pendingSync.size;
  }

  /**
   * Auto-bind field to matching component
   */
  public autoBind(
    field: ViewModelFieldInfo,
    componentId: string,
    propertyPath = 'value',
  ): FieldBinding {
    return fieldBindingService.bind(field.path, componentId, propertyPath);
  }

  /**
   * Auto-bind multiple fields based on name matching
   */
  public autoBindByName(
    fields: ViewModelFieldInfo[],
    components: Array<{ id: string; type: string; props: Record<string, unknown> }>,
  ): FieldBinding[] {
    const bindings: FieldBinding[] = [];

    fields.forEach((field) => {
      // Find component with matching field prop
      const matchingComponent = components.find((comp) => {
        const fieldProp = comp.props.field as string;
        return fieldProp === field.path || fieldProp === field.path.split('.').pop();
      });

      if (matchingComponent) {
        const binding = fieldBindingService.bind(field.path, matchingComponent.id);
        bindings.push(binding);
      }
    });

    return bindings;
  }
}

// Export singleton instance
export const bindingSyncManager = BindingSyncManager.getInstance();

export default bindingSyncManager;
