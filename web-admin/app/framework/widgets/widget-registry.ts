/**
 * WidgetRegistry + ColumnRendererRegistry.
 *
 * Plugins register form widgets and table column renderers along with their
 * `propsSchema` (a JSON Schema). Designer reads `propsSchema` to
 * auto-generate configuration panels — no hand-written config UI.
 *
 * Validation: when a DSL block's `widgetProps` is set, the kernel validates
 * it against the registered widget's compiled schema. Failures throw with
 * the offending path + message so plugin authors get actionable errors at
 * registration / import time, not silent runtime breakage.
 */

import Ajv, { type ValidateFunction, type ErrorObject } from 'ajv'
import type { WidgetType, ColumnType } from '@auraboot/dsl-types'

export type PropsSchema = Record<string, unknown>

export interface ValidationError {
  path: string
  message: string
}

export interface ValidationResult {
  valid: boolean
  errors: ValidationError[]
}

const ajv = new Ajv({
  allErrors: true,
  strict: false,
  useDefaults: true,
  removeAdditional: false,
})

function compileOrThrow(record: { type: string; plugin?: string; propsSchema?: PropsSchema }): ValidateFunction | undefined {
  if (!record.propsSchema) return undefined
  try {
    return ajv.compile(record.propsSchema)
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    throw new Error(
      `[WidgetRegistry] invalid propsSchema for '${record.type}' (plugin='${record.plugin ?? '<unknown>'}'): ${msg}`,
    )
  }
}

function formatErrors(errors: readonly ErrorObject[] | null | undefined): ValidationError[] {
  if (!errors) return []
  return errors.map(e => ({
    path: e.instancePath || e.schemaPath || '/',
    message: e.message ?? 'invalid',
  }))
}

export interface WidgetRecord {
  type: WidgetType
  component: unknown
  appliesTo?: readonly string[]
  propsSchema?: PropsSchema
  plugin?: string
}

export interface ColumnRendererRecord {
  type: ColumnType
  component: unknown
  appliesTo?: readonly string[]
  propsSchema?: PropsSchema
  plugin?: string
}

interface RegistryEntry<T> {
  record: T
  validate?: ValidateFunction
}

abstract class TypedRegistry<T extends { type: string; plugin?: string; propsSchema?: PropsSchema }> {
  protected readonly entries = new Map<string, RegistryEntry<T>>()

  protected registerInternal(record: T, allowOverride: boolean): void {
    if (this.entries.has(record.type) && !allowOverride) {
      const existing = this.entries.get(record.type)!.record
      throw new Error(
        `[${this.constructor.name}] '${record.type}' already registered by plugin='${existing.plugin ?? '<unknown>'}', refused new registration from plugin='${record.plugin ?? '<unknown>'}'. Pass override:true to replace.`,
      )
    }
    const validate = compileOrThrow(record)
    this.entries.set(record.type, { record, validate })
  }

  resolve(type: string): T | undefined {
    return this.entries.get(type)?.record
  }

  list(): readonly T[] {
    return Array.from(this.entries.values()).map(e => e.record)
  }

  /**
   * Validate `props` against the registered type's `propsSchema`.
   *
   * - If the type isn't registered, returns valid:false with a single
   *   "unknown type" error.
   * - If the type is registered without a `propsSchema`, returns valid:true
   *   (no schema = no constraints).
   * - Otherwise runs ajv and returns structured errors.
   */
  validate(type: string, props: unknown): ValidationResult {
    const entry = this.entries.get(type)
    if (!entry) {
      return { valid: false, errors: [{ path: '/', message: `unknown type '${type}'` }] }
    }
    if (!entry.validate) return { valid: true, errors: [] }
    const ok = entry.validate(props)
    return { valid: ok as boolean, errors: ok ? [] : formatErrors(entry.validate.errors) }
  }

  /** Same as validate() but throws on failure. Convenient for import-time checks. */
  assertValid(type: string, props: unknown): void {
    const result = this.validate(type, props)
    if (!result.valid) {
      const summary = result.errors.map(e => `${e.path}: ${e.message}`).join('; ')
      throw new Error(
        `[${this.constructor.name}] props validation failed for type='${type}': ${summary}`,
      )
    }
  }
}

export class WidgetRegistry extends TypedRegistry<WidgetRecord> {
  register(record: WidgetRecord & { override?: boolean }): void {
    const { override, ...rest } = record
    this.registerInternal(rest, override === true)
  }
}

export class ColumnRendererRegistry extends TypedRegistry<ColumnRendererRecord> {
  register(record: ColumnRendererRecord & { override?: boolean }): void {
    const { override, ...rest } = record
    this.registerInternal(rest, override === true)
  }
}
