/** Orthogonal form layout settings shared by the DSL runtime and designer. */
export interface FormPresentation {
  width: 'comfortable' | 'wide' | 'full';
  columns: 1 | 2 | 3;
  surface: 'card' | 'plain';
  density: 'comfortable' | 'compact';
  actions: 'sticky' | 'inline' | 'fixed';
  guidance: 'none' | 'aside';
}

export function resolveFormPresentation(
  extension: Record<string, any> = {},
  embedded = false,
): FormPresentation {
  const legacy = extension.presentation === 'workspace';
  const value = extension.formPresentation ?? {};
  const choice = <T extends string>(candidate: unknown, options: readonly T[], fallback: T): T =>
    options.includes(candidate as T) ? (candidate as T) : fallback;
  return {
    width: choice(
      value.width,
      ['comfortable', 'wide', 'full'],
      legacy ? 'full' : extension.formWidth === 'comfortable' ? 'comfortable' : 'wide',
    ),
    columns: value.columns === 1 || value.columns === 3 ? value.columns : 2,
    surface: value.surface === 'plain' ? 'plain' : 'card',
    density: value.density === 'compact' ? 'compact' : 'comfortable',
    actions: embedded
      ? 'inline'
      : choice(value.actions, ['sticky', 'inline', 'fixed'], legacy ? 'fixed' : 'sticky'),
    guidance: embedded
      ? 'none'
      : choice(value.guidance, ['none', 'aside'], legacy ? 'aside' : 'none'),
  };
}

export function formGridClass(columns: number): string {
  return columns === 1
    ? 'grid-cols-1'
    : columns === 3
      ? 'grid-cols-1 md:grid-cols-2 xl:grid-cols-3'
      : 'grid-cols-1 md:grid-cols-2';
}

export function formFieldSpanClass(span: number, columns: number): string {
  if (columns === 1) return '';
  if (span >= 12) return columns === 3 ? 'md:col-span-2 xl:col-span-3' : 'md:col-span-2';
  return columns === 3 && span >= 8 ? 'md:col-span-2' : '';
}

/** Authoring rejects invalid values; runtime still tolerates older persisted pages. */
export function validateFormPresentation(value: unknown): string[] {
  if (value === undefined) return [];
  if (!value || typeof value !== 'object' || Array.isArray(value)) return ['formPresentation'];
  const options: Record<string, readonly unknown[]> = {
    width: ['comfortable', 'wide', 'full'],
    columns: [1, 2, 3],
    surface: ['card', 'plain'],
    density: ['comfortable', 'compact'],
    actions: ['sticky', 'inline', 'fixed'],
    guidance: ['none', 'aside'],
  };
  return Object.entries(value).flatMap(([key, candidate]) =>
    options[key] && !options[key].includes(candidate) ? [`formPresentation.${key}`] : [],
  );
}
