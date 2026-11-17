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
  private readonly editors = new Map<string, {
    editor: ComponentType<RegisteredPropertyEditorProps>;
    owner?: symbol;
  }>();

  register(key: string, editor: ComponentType<RegisteredPropertyEditorProps>): () => void {
    return this.registerEntry(key, editor);
  }

  /** An explicit module owner may replace its own editor during SSR/HMR reevaluation. */
  registerOwned(key: string, editor: ComponentType<RegisteredPropertyEditorProps>, owner: symbol): () => void {
    if (typeof owner !== 'symbol') throw new Error('Property editor module owner must be a symbol');
    return this.registerEntry(key, editor, owner);
  }

  private registerEntry(key: string, editor: ComponentType<RegisteredPropertyEditorProps>, owner?: symbol): () => void {
    if (!key.trim()) {
      throw new Error(`Conflicting property editor registration: ${key}`);
    }
    const existing = this.editors.get(key);
    const sameOwner = owner !== undefined && existing?.owner === owner;
    const sameUnownedEditor = owner === undefined && existing?.owner === undefined && existing?.editor === editor;
    if (existing && !sameOwner && !sameUnownedEditor) {
      throw new Error(`Conflicting property editor registration: ${key}`);
    }
    const registration = { editor, owner };
    this.editors.set(key, registration);
    return () => {
      // A disposer from a previous module generation must not remove its replacement.
      if (this.editors.get(key) === registration) this.editors.delete(key);
    };
  }

  /**
   * Test seam: clears one key unconditionally. Production code never needs
   * this (register/dispose is the managed lifecycle); it exists so suites
   * that assert the fail-closed/conflicting semantics can start from a
   * known state even when a retried run left residue in this singleton.
   */
  clear(key: string): void {
    this.editors.delete(key);
  }

  resolve(key: string): ComponentType<RegisteredPropertyEditorProps> {
    const registration = this.editors.get(key);
    if (!registration) throw new Error(`Unregistered property editor: ${key}`);
    return registration.editor;
  }
}

export const propertyEditorRegistry = new PropertyEditorRegistry();
