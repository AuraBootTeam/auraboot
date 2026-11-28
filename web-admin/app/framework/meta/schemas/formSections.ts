import type { BlockConfig, FieldConfig } from './types';

export type FormFieldDeclaration = FieldConfig & {
  defaultValue?: unknown;
  submitPayload?: boolean;
};

export type FormSectionConfig = BlockConfig & { fields: FormFieldDeclaration[] };

const CHILD_FORM_SCOPES = new Set(['repeater', 'subform', 'sub-table']);

/** Collect sections in one form, including layout tabs and wizard steps.
 * Row and child-form fields belong to their own value/validation scope.
 * With a visibility predicate, hidden ancestors exclude the entire subtree.
 */
function visitFormScope(
  blocks: readonly unknown[] | undefined,
  visitor: (node: Record<string, unknown>) => void,
  isVisible?: (condition: string) => boolean,
) {
  const visit = (value: unknown) => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return;
    const node = value as Record<string, unknown>;
    if (isVisible && typeof node.visibleWhen === 'string' && !isVisible(node.visibleWhen)) return;
    if (CHILD_FORM_SCOPES.has(String(node.blockType))) return;
    visitor(node);
    for (const key of ['blocks', 'tabs', 'steps']) {
      if (Array.isArray(node[key])) node[key].forEach(visit);
    }
  };
  blocks?.forEach(visit);
}

export function collectFormSections(
  blocks: readonly unknown[] | undefined,
  isVisible?: (condition: string) => boolean,
): FormSectionConfig[] {
  const sections: FormSectionConfig[] = [];
  visitFormScope(
    blocks,
    (node) => {
      if (node.blockType === 'form-section' && Array.isArray(node.fields)) {
        sections.push(node as unknown as FormSectionConfig);
      }
    },
    isVisible,
  );
  return sections;
}

export function collectFormWizardIds(
  blocks: readonly unknown[] | undefined,
  isVisible?: (condition: string) => boolean,
): string[] {
  const ids: string[] = [];
  visitFormScope(
    blocks,
    (node) => {
      if (node.blockType === 'form-wizard') ids.push(String(node.id || ''));
    },
    isVisible,
  );
  return ids;
}
