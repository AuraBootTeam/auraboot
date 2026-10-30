import { describe, expect, it } from 'vitest';
import { registerColorPropertyEditor } from '../ColorPropertyEditor';
import { propertyEditorRegistry } from '../PropertyEditorRegistry';

describe('built-in property editor lifecycle', () => {
  it('retains the same editor across repeated renderer initialization without allowing a conflicting editor', () => {
    const dispose = registerColorPropertyEditor();
    const initial = propertyEditorRegistry.resolve('designer.color');
    const disposeAgain = registerColorPropertyEditor();
    expect(propertyEditorRegistry.resolve('designer.color')).toBe(initial);
    expect(() => propertyEditorRegistry.register('designer.color', () => null)).toThrow(
      'Conflicting',
    );
    disposeAgain();
    expect(() => propertyEditorRegistry.resolve('designer.color')).toThrow('Unregistered');
    dispose();
    const disposeAfterReload = registerColorPropertyEditor();
    expect(propertyEditorRegistry.resolve('designer.color')).toBe(initial);
    disposeAfterReload();
  });
});
