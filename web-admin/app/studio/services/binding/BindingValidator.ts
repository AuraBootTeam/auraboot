/**
 * Binding Validator
 *
 * Validates field-component bindings and provides suggestions.
 *
 * @since 3.2.0
 */

import { fieldBindingService } from './FieldBindingService';
import type {
  FieldBinding,
  BindingStatus,
  BindingValidationResult,
  BindingSuggestion,
  ViewModelFieldInfo,
} from './types';

/**
 * Validation options
 */
interface ValidationOptions {
  /** Check for orphan bindings */
  checkOrphans?: boolean;
  /** Check for type mismatches */
  checkTypes?: boolean;
  /** Include suggestions */
  includeSuggestions?: boolean;
}

/**
 * Validation summary
 */
export interface ValidationSummary {
  total: number;
  valid: number;
  warnings: number;
  errors: number;
  orphans: number;
  results: BindingValidationResult[];
}

/**
 * Type compatibility map
 */
const TYPE_COMPATIBLE_COMPONENTS: Record<string, string[]> = {
  STRING: ['SmartInput', 'SmartTextArea', 'SmartSelect', 'input', 'textarea'],
  TEXT: ['SmartTextArea', 'SmartRichText', 'textarea'],
  INTEGER: ['SmartNumber', 'SmartInput', 'input'],
  DECIMAL: ['SmartNumber', 'SmartInput', 'input'],
  BOOLEAN: ['SmartSwitch', 'SmartCheckbox', 'checkbox', 'switch'],
  DATE: ['SmartDatePicker', 'SmartDateRangePicker', 'date'],
  DATETIME: ['SmartDateTimePicker', 'datetime'],
  ENUM: ['SmartSelect', 'SmartRadio', 'SmartCheckboxGroup', 'select', 'radio'],
  REF: ['SmartSelect', 'SmartLookup', 'SmartTreeSelect', 'select'],
  FILE: ['SmartUpload', 'upload'],
  IMAGE: ['SmartImageUpload', 'SmartUpload', 'upload'],
};

/**
 * Binding Validator Class
 */
export class BindingValidator {
  private static instance: BindingValidator;

  private viewModelFields: Map<string, ViewModelFieldInfo> = new Map();
  private componentTypes: Map<string, string> = new Map();

  private constructor() {}

  /**
   * Get singleton instance
   */
  public static getInstance(): BindingValidator {
    if (!BindingValidator.instance) {
      BindingValidator.instance = new BindingValidator();
    }
    return BindingValidator.instance;
  }

  /**
   * Update ViewModel fields cache
   */
  public setViewModelFields(fields: ViewModelFieldInfo[]): void {
    this.viewModelFields.clear();
    fields.forEach((field) => {
      this.viewModelFields.set(field.path, field);
    });
  }

  /**
   * Update component types cache
   */
  public setComponentTypes(components: Map<string, string>): void {
    this.componentTypes = new Map(components);
  }

  /**
   * Register a component type
   */
  public registerComponentType(componentId: string, type: string): void {
    this.componentTypes.set(componentId, type);
  }

  /**
   * Validate all bindings
   */
  public validateAll(options: ValidationOptions = {}): ValidationSummary {
    const defaultOptions: Required<ValidationOptions> = {
      checkOrphans: true,
      checkTypes: true,
      includeSuggestions: true,
      ...options,
    };

    const bindings = fieldBindingService.getAllBindings();
    const results: BindingValidationResult[] = [];

    let valid = 0;
    let warnings = 0;
    let errors = 0;
    let orphans = 0;

    bindings.forEach((binding) => {
      const result = this.validateBinding(binding, defaultOptions);
      results.push(result);

      switch (result.status.status) {
        case 'valid':
          valid++;
          break;
        case 'warning':
          warnings++;
          break;
        case 'error':
          errors++;
          break;
        case 'orphan':
          orphans++;
          break;
      }
    });

    return {
      total: bindings.length,
      valid,
      warnings,
      errors,
      orphans,
      results,
    };
  }

  /**
   * Validate a single binding
   */
  public validateBinding(
    binding: FieldBinding,
    options: Required<ValidationOptions> = {
      checkOrphans: true,
      checkTypes: true,
      includeSuggestions: true,
    },
  ): BindingValidationResult {
    const field = this.viewModelFields.get(binding.fieldPath);
    const componentType = this.componentTypes.get(binding.componentId);

    const fieldExists = !!field;
    const componentExists = !!componentType;

    let status: BindingStatus['status'] = 'valid';
    let message: string | undefined;
    const suggestions: BindingSuggestion[] = [];

    // Check field existence
    if (!fieldExists) {
      status = 'error';
      message = `Field "${binding.fieldPath}" not found in ViewModel`;

      if (options.includeSuggestions) {
        // Suggest similar fields
        const similar = this.findSimilarFields(binding.fieldPath);
        if (similar.length > 0) {
          similar.forEach((similarField) => {
            suggestions.push({
              type: 'rebind',
              description: `Rebind to "${similarField.path}"`,
              action: () => {
                fieldBindingService.unbind(binding.id);
                fieldBindingService.bind(
                  similarField.path,
                  binding.componentId,
                  binding.propertyPath,
                  binding.mode,
                );
              },
            });
          });
        }

        suggestions.push({
          type: 'unbind',
          description: 'Remove this binding',
          action: () => fieldBindingService.unbind(binding.id),
        });
      }
    }

    // Check component existence
    if (options.checkOrphans && !componentExists) {
      status = status === 'error' ? 'error' : 'orphan';
      message = message
        ? `${message}; Component not found`
        : `Component "${binding.componentId}" not found`;

      if (options.includeSuggestions) {
        suggestions.push({
          type: 'unbind',
          description: 'Remove orphan binding',
          action: () => fieldBindingService.unbind(binding.id),
        });
      }
    }

    // Check type compatibility
    if (options.checkTypes && fieldExists && componentExists) {
      const typeCheck = this.checkTypeCompatibility(field!.type, componentType!);
      if (!typeCheck.compatible) {
        status = status === 'error' ? 'error' : 'warning';
        message = message ? `${message}; ${typeCheck.message}` : typeCheck.message;

        if (options.includeSuggestions && typeCheck.suggestedTypes) {
          suggestions.push({
            type: 'fix',
            description: `Consider using: ${typeCheck.suggestedTypes.join(', ')}`,
            action: () => {},
          });
        }
      }
    }

    return {
      binding,
      status: {
        bindingId: binding.id,
        status,
        message,
        fieldExists,
        componentExists,
      },
      suggestions: options.includeSuggestions ? suggestions : undefined,
    };
  }

  /**
   * Check type compatibility between field and component
   */
  private checkTypeCompatibility(
    fieldType: string,
    componentType: string,
  ): {
    compatible: boolean;
    message?: string;
    suggestedTypes?: string[];
  } {
    const compatibleComponents = TYPE_COMPATIBLE_COMPONENTS[fieldType.toUpperCase()];

    if (!compatibleComponents) {
      return { compatible: true }; // Unknown field type, assume compatible
    }

    const isCompatible = compatibleComponents.some(
      (comp) =>
        componentType.toLowerCase().includes(comp.toLowerCase()) ||
        comp.toLowerCase().includes(componentType.toLowerCase()),
    );

    if (isCompatible) {
      return { compatible: true };
    }

    return {
      compatible: false,
      message: `Type mismatch: ${fieldType} field bound to ${componentType}`,
      suggestedTypes: compatibleComponents,
    };
  }

  /**
   * Find similar fields by name
   */
  private findSimilarFields(fieldPath: string): ViewModelFieldInfo[] {
    const parts = fieldPath.split('.');
    const fieldName = parts[parts.length - 1].toLowerCase();

    const similar: ViewModelFieldInfo[] = [];

    this.viewModelFields.forEach((field) => {
      const existingParts = field.path.split('.');
      const existingName = existingParts[existingParts.length - 1].toLowerCase();

      // Check for similar names
      if (
        existingName.includes(fieldName) ||
        fieldName.includes(existingName) ||
        this.levenshteinDistance(fieldName, existingName) <= 2
      ) {
        similar.push(field);
      }
    });

    return similar.slice(0, 3); // Return top 3 suggestions
  }

  /**
   * Calculate Levenshtein distance for fuzzy matching
   */
  private levenshteinDistance(a: string, b: string): number {
    const matrix: number[][] = [];

    for (let i = 0; i <= b.length; i++) {
      matrix[i] = [i];
    }

    for (let j = 0; j <= a.length; j++) {
      matrix[0][j] = j;
    }

    for (let i = 1; i <= b.length; i++) {
      for (let j = 1; j <= a.length; j++) {
        if (b.charAt(i - 1) === a.charAt(j - 1)) {
          matrix[i][j] = matrix[i - 1][j - 1];
        } else {
          matrix[i][j] = Math.min(
            matrix[i - 1][j - 1] + 1,
            matrix[i][j - 1] + 1,
            matrix[i - 1][j] + 1,
          );
        }
      }
    }

    return matrix[b.length][a.length];
  }

  /**
   * Get validation report
   */
  public getReport(): {
    summary: ValidationSummary;
    unboundFields: ViewModelFieldInfo[];
    orphanBindings: FieldBinding[];
  } {
    const summary = this.validateAll();

    const unboundFields = Array.from(this.viewModelFields.values()).filter((field) => {
      const bindings = fieldBindingService.getBindingsForField(field.path);
      return bindings.length === 0;
    });

    const orphanBindings = summary.results
      .filter((r) => r.status.status === 'orphan')
      .map((r) => r.binding);

    return {
      summary,
      unboundFields,
      orphanBindings,
    };
  }

  /**
   * Auto-fix all orphan bindings
   */
  public fixOrphans(): number {
    const orphans = fieldBindingService
      .getAllBindings()
      .filter((binding) => !this.componentTypes.has(binding.componentId));

    orphans.forEach((binding) => {
      fieldBindingService.unbind(binding.id);
    });

    return orphans.length;
  }
}

// Export singleton instance
export const bindingValidator = BindingValidator.getInstance();

export default bindingValidator;
