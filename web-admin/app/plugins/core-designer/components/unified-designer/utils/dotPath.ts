export function getByPath(source: Record<string, unknown>, path: string): unknown {
  if (!path) return source;
  return path.split('.').reduce<unknown>((current, part) => {
    if (current && typeof current === 'object') {
      return (current as Record<string, unknown>)[part];
    }
    return undefined;
  }, source);
}

export function setByPath<T extends Record<string, unknown>>(source: T, path: string, value: unknown): T {
  if (!path) return value as T;

  const parts = path.split('.');
  const result: Record<string, unknown> = { ...source };
  let cursor = result;

  for (let index = 0; index < parts.length - 1; index += 1) {
    const part = parts[index];
    const current = cursor[part];
    const next =
      current && typeof current === 'object' && !Array.isArray(current)
        ? { ...(current as Record<string, unknown>) }
        : {};
    cursor[part] = next;
    cursor = next;
  }

  cursor[parts[parts.length - 1]] = value;
  return result as T;
}
