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
    const existing = this.editors.get(key);
    if (!key.trim() || (existing && existing !== editor)) {
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
