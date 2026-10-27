import type { ComponentType } from 'react';
import type { FieldAdapter } from '~/ui/field-adapter';
import type { PropertySchema } from './types';
import type { LocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';

export interface RegisteredPropertyEditorProps {
  schema: PropertySchema<string | LocalizedText>;
  adapter: FieldAdapter<unknown>;
}

/** Explicit extension seam; unknown and conflicting controls fail closed. */
export class PropertyEditorRegistry {
  private readonly editors = new Map<string, ComponentType<RegisteredPropertyEditorProps>>();

  register(key: string, editor: ComponentType<RegisteredPropertyEditorProps>): () => void {
    if (!key.trim()) {
      throw new Error(`Conflicting property editor registration: ${key}`);
    }
    // Vite HMR re-evaluates importer modules after any web-admin file change while older
    // instances of this registry survive in the module graph; the re-registration is the SAME
    // editor component under a fresh module identity, so identical editors overwrite instead
    // of failing every page behind the dev error overlay.
    const existing = this.editors.get(key);
    if (existing && existing !== editor) {
      throw new Error(`Conflicting property editor registration: ${key}`);
    }
    this.editors.set(key, editor);
    return () => {
      if (this.editors.get(key) === editor) this.editors.delete(key);
    };
  }

  resolve(key: string): ComponentType<RegisteredPropertyEditorProps> {
    const editor = this.editors.get(key);
    if (!editor) throw new Error(`Unregistered property editor: ${key}`);
    return editor;
  }
}

export const propertyEditorRegistry = new PropertyEditorRegistry();
