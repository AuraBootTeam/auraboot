/**
 * SlotRegistry — runtime store for slot overrides.
 *
 * A "slot" is a named extension point in the UI tree. Core renders a slot
 * stub by default; plugins can register a higher-priority override via
 * `PluginContext.registerSlot()`. The highest-priority registered component
 * is returned by `resolve()`.
 *
 * Slots are file-level in M1 (rsync overlay); this registry exists for
 * runtime cases where multiple plugins target the same slot dynamically.
 */

export interface SlotRecord {
  slot: string
  component: unknown
  priority: number
  /** Plugin code that registered this slot, for diagnostics. */
  plugin?: string
}

export class SlotRegistry {
  private readonly bySlot = new Map<string, SlotRecord[]>()

  register(record: Omit<SlotRecord, 'priority'> & { priority?: number }): void {
    const list = this.bySlot.get(record.slot) ?? []
    list.push({ priority: 0, ...record })
    list.sort((a, b) => b.priority - a.priority)
    this.bySlot.set(record.slot, list)
  }

  /** Highest-priority override for a slot, or undefined. */
  resolve(slot: string): SlotRecord | undefined {
    const list = this.bySlot.get(slot)
    return list && list.length > 0 ? list[0] : undefined
  }

  /** All overrides for a slot, sorted by priority desc. */
  resolveAll(slot: string): readonly SlotRecord[] {
    return this.bySlot.get(slot) ?? []
  }

  /** Diagnostic: list every slot known to the registry. */
  list(): readonly { slot: string; count: number; topPlugin?: string }[] {
    return Array.from(this.bySlot.entries()).map(([slot, list]) => ({
      slot,
      count: list.length,
      topPlugin: list[0]?.plugin,
    }))
  }
}
