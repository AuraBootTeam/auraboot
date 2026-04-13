/**
 * WidgetRegistry + ColumnRendererRegistry.
 *
 * Plugins register form widgets and table column renderers along with their
 * `propsSchema` (a JSON Schema subset). Designer reads `propsSchema` to
 * auto-generate configuration panels — no hand-written config UI.
 *
 * Validation: when a DSL block's `widgetProps` is set, the kernel validates
 * it against the registered widget's `propsSchema` at import time. Invalid
 * configs reject during plugin import, not at runtime.
 */

import type { WidgetType, ColumnType } from '@auraboot/dsl-types'

export type PropsSchema = Record<string, unknown>

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

abstract class TypedRegistry<T extends { type: string; plugin?: string }> {
  protected readonly records = new Map<string, T>()

  protected registerInternal(record: T, allowOverride: boolean): void {
    if (this.records.has(record.type) && !allowOverride) {
      const existing = this.records.get(record.type)!
      throw new Error(
        `[${this.constructor.name}] '${record.type}' already registered by plugin='${existing.plugin ?? '<unknown>'}', refused new registration from plugin='${record.plugin ?? '<unknown>'}'. Pass override:true to replace.`,
      )
    }
    this.records.set(record.type, record)
  }

  resolve(type: string): T | undefined {
    return this.records.get(type)
  }

  list(): readonly T[] {
    return Array.from(this.records.values())
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
