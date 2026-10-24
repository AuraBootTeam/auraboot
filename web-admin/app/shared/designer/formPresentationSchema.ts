import type { PropertySchema } from './types';

/** Shared page-level form properties for CRUD generation and both designer hosts. */
export const formPresentationProperties: PropertySchema<string>[] = [
  ...[
    ['width', 'Form width', ['comfortable', 'wide', 'full']],
    ['surface', 'Section surface', ['card', 'plain']],
    ['density', 'Field density', ['comfortable', 'compact']],
    ['actions', 'Action bar', ['sticky', 'inline', 'fixed']],
    ['guidance', 'Guidance placement', ['none', 'aside']],
  ].map(([key, label, values]) => ({
    key: `extension.formPresentation.${key}`,
    label: label as string,
    type: 'select' as const,
    options: (values as string[]).map((value) => ({ label: value, value })),
  })),
  {
    key: 'extension.formPresentation.columns',
    label: 'Form columns',
    type: 'number',
    min: 1,
    max: 3,
  },
];
