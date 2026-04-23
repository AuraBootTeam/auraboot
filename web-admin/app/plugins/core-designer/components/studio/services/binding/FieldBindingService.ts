/**
 * Field Binding Service
 *
 * Manages field-component bindings with sync and validation.
 *
 * @since 3.2.0
 */

import type {
  FieldBinding,
  FieldChanges,
  BindingStatus,
  BindingValidationResult,
  FieldBindingGroup,
  ComponentBindingGroup,
  BindingChangeEvent,
  BindingServiceOptions,
  ViewModelFieldInfo,
  BindingMode,
} from './types';

/**
 * Generate unique ID
 */
function generateId(): string {
  return `binding_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Field Binding Service Class
 */
export class FieldBindingService {
  private static instance: FieldBindingService;

  private bindings: Map<string, FieldBinding> = new Map();
  private fieldIndex: Map<string, Set<string>> = new Map(); // fieldPath -> binding IDs
  private componentIndex: Map<string, Set<string>> = new Map(); // componentId -> binding IDs

  private listeners: Set<(event: BindingChangeEvent) => void> = new Set();
  private options: BindingServiceOptions;

  private viewModelFields: Map<string, ViewModelFieldInfo> = new Map();
  private componentRegistry: Map<string, { type: string; label?: string }> = new Map();

  private constructor(options: BindingServiceOptions = {}) {
    this.options = {
      autoSync: true,
      validateOnChange: true,
      enableHistory: false,
      ...options,
    };
  }

  /**
   * Get singleton instance
   */
  public static getInstance(options?: BindingServiceOptions): FieldBindingService {
    if (!FieldBindingService.instance) {
      FieldBindingService.instance = new FieldBindingService(options);
    }
    return FieldBindingService.instance;
  }

  /**
   * Create a binding between field and component
   */
  public bind(
    fieldPath: string,
    componentId: string,
    propertyPath = 'value',
    mode: BindingMode = 'two-way',
  ): FieldBinding {
    const binding: FieldBinding = {
      id: generateId(),
      fieldPath,
      componentId,
      propertyPath,
      syncEnabled: true,
      mode,
      createdAt: Date.now(),
    };

    this.bindings.set(binding.id, binding);
    this.addToIndex(this.fieldIndex, fieldPath, binding.id);
    this.addToIndex(this.componentIndex, componentId, binding.id);

    this.emit({ type: 'created', binding, timestamp: Date.now() });

    return binding;
  }

  /**
   * Remove a binding
   */
  public unbind(bindingId: string): boolean {
    const binding = this.bindings.get(bindingId);
    if (!binding) return false;

    this.bindings.delete(bindingId);
    this.removeFromIndex(this.fieldIndex, binding.fieldPath, bindingId);
    this.removeFromIndex(this.componentIndex, binding.componentId, bindingId);

    this.emit({ type: 'deleted', binding, timestamp: Date.now() });

    return true;
  }

  /**
   * Unbind all bindings for a field
   */
  public unbindField(fieldPath: string): number {
    const bindingIds = this.fieldIndex.get(fieldPath);
    if (!bindingIds) return 0;

    let count = 0;
    bindingIds.forEach((id) => {
      if (this.unbind(id)) count++;
    });

    return count;
  }

  /**
   * Unbind all bindings for a component
   */
  public unbindComponent(componentId: string): number {
    const bindingIds = this.componentIndex.get(componentId);
    if (!bindingIds) return 0;

    let count = 0;
    bindingIds.forEach((id) => {
      if (this.unbind(id)) count++;
    });

    return count;
  }

  /**
   * Get bindings for a field
   */
  public getBindingsForField(fieldPath: string): FieldBinding[] {
    const bindingIds = this.fieldIndex.get(fieldPath);
    if (!bindingIds) return [];

    return Array.from(bindingIds)
      .map((id) => this.bindings.get(id))
      .filter(Boolean) as FieldBinding[];
  }

  /**
   * Get bindings for a component
   */
  public getBindingsForComponent(componentId: string): FieldBinding[] {
    const bindingIds = this.componentIndex.get(componentId);
    if (!bindingIds) return [];

    return Array.from(bindingIds)
      .map((id) => this.bindings.get(id))
      .filter(Boolean) as FieldBinding[];
  }

  /**
   * Get all bindings
   */
  public getAllBindings(): FieldBinding[] {
    return Array.from(this.bindings.values());
  }

  /**
   * Get binding by ID
   */
  public getBinding(bindingId: string): FieldBinding | undefined {
    return this.bindings.get(bindingId);
  }

  /**
   * Update binding properties
   */
  public updateBinding(
    bindingId: string,
    updates: Partial<Pick<FieldBinding, 'syncEnabled' | 'mode' | 'propertyPath'>>,
  ): boolean {
    const binding = this.bindings.get(bindingId);
    if (!binding) return false;

    Object.assign(binding, updates);
    this.emit({ type: 'updated', binding, timestamp: Date.now() });

    return true;
  }

  /**
   * Handle field changes and sync to bound components
   */
  public onFieldChanged(fieldPath: string, changes: FieldChanges): void {
    if (!this.options.autoSync) return;

    const bindings = this.getBindingsForField(fieldPath);
    if (bindings.length === 0) return;

    bindings.forEach((binding) => {
      if (binding.syncEnabled) {
        this.syncToComponent(binding, changes);
        this.emit({ type: 'synced', binding, timestamp: Date.now() });
      }
    });
  }

  /**
   * Sync changes to component
   */
  private syncToComponent(_binding: FieldBinding, _changes: FieldChanges): void {
    // This will be connected to the designer store
    // For now, emit an event that can be handled by the store
  }

  /**
   * Register ViewModel fields for validation
   */
  public registerViewModelFields(fields: ViewModelFieldInfo[]): void {
    this.viewModelFields.clear();
    fields.forEach((field) => {
      this.viewModelFields.set(field.path, field);
    });

    if (this.options.validateOnChange) {
      this.validateAll();
    }
  }

  /**
   * Register component for validation
   */
  public registerComponent(componentId: string, type: string, label?: string): void {
    this.componentRegistry.set(componentId, { type, label });
  }

  /**
   * Unregister component
   */
  public unregisterComponent(componentId: string): void {
    this.componentRegistry.delete(componentId);
    // Don't auto-unbind, just mark as orphan during validation
  }

  /**
   * Validate a single binding
   */
  public validateBinding(bindingId: string): BindingStatus {
    const binding = this.bindings.get(bindingId);
    if (!binding) {
      return {
        bindingId,
        status: 'error',
        message: 'Binding not found',
        fieldExists: false,
        componentExists: false,
      };
    }

    const fieldExists = this.viewModelFields.has(binding.fieldPath);
    const componentExists = this.componentRegistry.has(binding.componentId);

    if (!fieldExists && !componentExists) {
      return {
        bindingId,
        status: 'error',
        message: `Field "${binding.fieldPath}" and component not found`,
        fieldExists,
        componentExists,
      };
    }

    if (!fieldExists) {
      return {
        bindingId,
        status: 'error',
        message: `Field "${binding.fieldPath}" not found in ViewModel`,
        fieldExists,
        componentExists,
      };
    }

    if (!componentExists) {
      return {
        bindingId,
        status: 'orphan',
        message: `Component "${binding.componentId}" not found`,
        fieldExists,
        componentExists,
      };
    }

    return {
      bindingId,
      status: 'valid',
      fieldExists,
      componentExists,
    };
  }

  /**
   * Validate all bindings
   */
  public validateAll(): BindingValidationResult[] {
    const results: BindingValidationResult[] = [];

    this.bindings.forEach((binding) => {
      const status = this.validateBinding(binding.id);
      results.push({ binding, status });
    });

    return results;
  }

  /**
   * Get bindings grouped by field
   */
  public getBindingsByField(): FieldBindingGroup[] {
    const groups: Map<string, FieldBindingGroup> = new Map();

    // First, add all registered fields
    this.viewModelFields.forEach((field, path) => {
      groups.set(path, {
        fieldPath: path,
        fieldLabel: field.label,
        fieldType: field.type,
        bindings: [],
        status: 'unbound',
      });
    });

    // Add bindings to groups
    this.bindings.forEach((binding) => {
      let group = groups.get(binding.fieldPath);
      if (!group) {
        group = {
          fieldPath: binding.fieldPath,
          bindings: [],
          status: 'error', // Unknown field
        };
        groups.set(binding.fieldPath, group);
      }
      group.bindings.push(binding);
    });

    // Update status based on bindings and validation
    groups.forEach((group) => {
      if (group.bindings.length === 0) {
        group.status = 'unbound';
      } else {
        const statuses = group.bindings.map((b) => this.validateBinding(b.id).status);
        if (statuses.includes('error')) {
          group.status = 'error';
        } else if (statuses.includes('warning') || statuses.includes('orphan')) {
          group.status = 'warning';
        } else {
          group.status = 'valid';
        }
      }
    });

    return Array.from(groups.values());
  }

  /**
   * Get bindings grouped by component
   */
  public getBindingsByComponent(): ComponentBindingGroup[] {
    const groups: Map<string, ComponentBindingGroup> = new Map();

    this.bindings.forEach((binding) => {
      let group = groups.get(binding.componentId);
      if (!group) {
        const compInfo = this.componentRegistry.get(binding.componentId);
        group = {
          componentId: binding.componentId,
          componentType: compInfo?.type || 'unknown',
          componentLabel: compInfo?.label,
          bindings: [],
          status: 'valid',
        };
        groups.set(binding.componentId, group);
      }
      group.bindings.push(binding);
    });

    // Update status
    groups.forEach((group) => {
      const statuses = group.bindings.map((b) => this.validateBinding(b.id).status);
      if (statuses.includes('error')) {
        group.status = 'error';
      } else if (statuses.includes('warning') || statuses.includes('orphan')) {
        group.status = 'warning';
      } else {
        group.status = 'valid';
      }
    });

    return Array.from(groups.values());
  }

  /**
   * Find unbound fields
   */
  public getUnboundFields(): ViewModelFieldInfo[] {
    const boundFields = new Set<string>();
    this.bindings.forEach((binding) => {
      boundFields.add(binding.fieldPath);
    });

    return Array.from(this.viewModelFields.values()).filter(
      (field) => !boundFields.has(field.path),
    );
  }

  /**
   * Subscribe to binding changes
   */
  public subscribe(listener: (event: BindingChangeEvent) => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  /**
   * Emit binding change event
   */
  private emit(event: BindingChangeEvent): void {
    this.listeners.forEach((listener) => {
      try {
        listener(event);
      } catch (error) {
        console.error('[FieldBindingService] Listener error:', error);
      }
    });
  }

  /**
   * Add to index
   */
  private addToIndex(index: Map<string, Set<string>>, key: string, id: string): void {
    let set = index.get(key);
    if (!set) {
      set = new Set();
      index.set(key, set);
    }
    set.add(id);
  }

  /**
   * Remove from index
   */
  private removeFromIndex(index: Map<string, Set<string>>, key: string, id: string): void {
    const set = index.get(key);
    if (set) {
      set.delete(id);
      if (set.size === 0) {
        index.delete(key);
      }
    }
  }

  /**
   * Clear all bindings
   */
  public clear(): void {
    this.bindings.clear();
    this.fieldIndex.clear();
    this.componentIndex.clear();
  }

  /**
   * Export bindings for serialization
   */
  public exportBindings(): FieldBinding[] {
    return Array.from(this.bindings.values());
  }

  /**
   * Import bindings from serialized data
   */
  public importBindings(bindings: FieldBinding[]): void {
    this.clear();
    bindings.forEach((binding) => {
      this.bindings.set(binding.id, binding);
      this.addToIndex(this.fieldIndex, binding.fieldPath, binding.id);
      this.addToIndex(this.componentIndex, binding.componentId, binding.id);
    });
  }
}

// Export singleton instance
export const fieldBindingService = FieldBindingService.getInstance();

export default fieldBindingService;
