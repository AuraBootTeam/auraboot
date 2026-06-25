import type { FieldConfig } from '~/framework/meta/schemas/types';
import type { ExpressionContext } from '~/framework/meta/runtime/expression/context';

function readPath(source: unknown, path: string): unknown {
  if (!source || typeof source !== 'object') return undefined;
  return path.split('.').reduce<unknown>((current, segment) => {
    if (!current || typeof current !== 'object') return undefined;
    return (current as Record<string, unknown>)[segment];
  }, source);
}

function isBlank(value: unknown): boolean {
  if (value === undefined || value === null) return true;
  if (typeof value === 'string') return value.trim() === '';
  if (Array.isArray(value)) return value.length === 0;
  return false;
}

function getDependencyValue(context: ExpressionContext, dependency: string): unknown {
  const path = dependency.trim();
  if (!path) return undefined;
  if (path.includes('.')) return readPath(context, path);
  return (
    readPath(context.form, path) ??
    readPath(context.state, path) ??
    readPath(context, path)
  );
}

export function getFieldDependencies(field: FieldConfig): string[] {
  const raw = (field as FieldConfig & { dependsOn?: string | string[] }).dependsOn ?? field.dependOn;
  if (!raw) return [];
  const dependencies = Array.isArray(raw) ? raw : [raw];
  return dependencies.map((item) => String(item).trim()).filter(Boolean);
}

export function hasMissingFieldDependency(field: FieldConfig, context: ExpressionContext): boolean {
  const dependencies = getFieldDependencies(field);
  if (dependencies.length === 0) return false;
  return dependencies.some((dependency) => isBlank(getDependencyValue(context, dependency)));
}
