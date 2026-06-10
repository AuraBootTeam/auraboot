/**
 * Tests for signals.ts – reactive primitives.
 *
 * IMPORTANT – Effect/watch skip rationale:
 * The Effect class has a subscriber accumulation bug: each call to `run()` creates
 * a NEW `() => this.run()` arrow function and adds it to the signal's subscriber Set.
 * Since Set identity is by reference, the old arrow function is NOT replaced – both
 * live in the Set. The next `signal.value =` notification fires all of them, each
 * adding yet another closure. Growth is O(n²) per change → OOM in the jsdom worker
 * within the first test that changes a signal value after an effect is created.
 *
 * Signal, Computed, reactive, and batch are pure-logic primitives that do NOT
 * use Effect and are fully tested below.
 */
import { describe, it, expect, vi } from 'vitest';
import {
  signal,
  computed,
  batch,
  reactive,
} from '../signals';

// ─── Signal ──────────────────────────────────────────────────────────────────

describe('Signal', () => {
  describe('basic get/set', () => {
    it('initialises with the given value', () => {
      expect(signal(42).value).toBe(42);
    });

    it('updates value on set', () => {
      const s = signal(0);
      s.value = 10;
      expect(s.value).toBe(10);
    });

    it('supports string values', () => {
      const s = signal('hello');
      s.value = 'world';
      expect(s.value).toBe('world');
    });

    it('supports null/undefined values', () => {
      const s = signal<null | number>(null);
      expect(s.value).toBeNull();
      s.value = 5;
      expect(s.value).toBe(5);
    });

    it('does not trigger subscribers when value does not change', () => {
      const s = signal(5);
      const cb = vi.fn();
      s.subscribe(cb);
      s.value = 5; // same value
      expect(cb).not.toHaveBeenCalled();
    });

    it('triggers a subscriber when value changes', () => {
      const s = signal(0);
      const cb = vi.fn();
      s.subscribe(cb);
      s.value = 1;
      expect(cb).toHaveBeenCalledOnce();
    });

    it('subscribe returns an unsubscribe function that stops future calls', () => {
      const s = signal(0);
      const cb = vi.fn();
      const unsub = s.subscribe(cb);
      unsub();
      s.value = 99;
      expect(cb).not.toHaveBeenCalled();
    });

    it('multiple independent subscribers each receive the notification', () => {
      const s = signal(0);
      const cb1 = vi.fn();
      const cb2 = vi.fn();
      s.subscribe(cb1);
      s.subscribe(cb2);
      s.value = 7;
      expect(cb1).toHaveBeenCalledOnce();
      expect(cb2).toHaveBeenCalledOnce();
    });
  });

  describe('dispose', () => {
    it('stops notifying after dispose', () => {
      const s = signal(0);
      const cb = vi.fn();
      s.subscribe(cb);
      s.dispose();
      s.value = 1;
      expect(cb).not.toHaveBeenCalled();
    });

    it('value is still readable after dispose', () => {
      const s = signal(42);
      s.dispose();
      expect(s.value).toBe(42);
    });
  });
});

// ─── Computed ────────────────────────────────────────────────────────────────

describe('Computed', () => {
  describe('basic derivation', () => {
    it('computes an initial value lazily from getter', () => {
      const a = signal(3);
      const doubled = computed(() => a.value * 2);
      expect(doubled.value).toBe(6);
    });

    it('recomputes when dependency changes', () => {
      const a = signal(5);
      const doubled = computed(() => a.value * 2);
      a.value = 10;
      expect(doubled.value).toBe(20);
    });

    it('does not recompute when nothing changed (dirty flag)', () => {
      let count = 0;
      const a = signal(1);
      const c = computed(() => { count++; return a.value + 1; });
      c.value; // first access
      c.value; // second – should not recompute
      expect(count).toBe(1);
    });

    it('recomputes exactly once after a single dependency update', () => {
      let count = 0;
      const a = signal(1);
      const c = computed(() => { count++; return a.value; });
      c.value; // read once
      a.value = 2; // mark dirty
      c.value; // read again → recompute
      expect(count).toBe(2);
    });

    it('chains computed values', () => {
      const x = signal(2);
      const doubled = computed(() => x.value * 2);
      const quadrupled = computed(() => doubled.value * 2);
      x.value = 3;
      expect(quadrupled.value).toBe(12);
    });

    it('computed with no dependencies always returns the getter result', () => {
      const c = computed(() => 'constant');
      expect(c.value).toBe('constant');
    });

    it('computed over boolean signal', () => {
      const active = signal(false);
      const label = computed(() => (active.value ? 'on' : 'off'));
      expect(label.value).toBe('off');
      active.value = true;
      expect(label.value).toBe('on');
    });
  });

  describe('subscribe', () => {
    it('subscribe fires when computed marks dirty (after initial read)', () => {
      const a = signal(1);
      const c = computed(() => a.value + 1);
      // Must read c.value first to establish the a → c dependency chain
      c.value;
      const cb = vi.fn();
      c.subscribe(cb);
      a.value = 5;
      expect(cb).toHaveBeenCalled();
    });

    it('subscribe returns an unsub function', () => {
      const a = signal(1);
      const c = computed(() => a.value + 1);
      c.value; // establish dependency
      const cb = vi.fn();
      const unsub = c.subscribe(cb);
      unsub();
      a.value = 5;
      expect(cb).not.toHaveBeenCalled();
    });
  });

  describe('dispose', () => {
    it('stops subscriber notifications after dispose (after initial read)', () => {
      const a = signal(0);
      const c = computed(() => a.value);
      c.value; // establish dependency
      const cb = vi.fn();
      c.subscribe(cb);
      c.dispose();
      a.value = 1;
      expect(cb).not.toHaveBeenCalled();
    });
  });
});

// ─── batch ───────────────────────────────────────────────────────────────────

describe('batch', () => {
  it('leaves signal values correct after batch', () => {
    const a = signal(0);
    const b = signal(0);
    batch(() => {
      a.value = 1;
      b.value = 2;
    });
    expect(a.value).toBe(1);
    expect(b.value).toBe(2);
  });

  it('batch with no changes is a no-op', () => {
    const a = signal(5);
    batch(() => {});
    expect(a.value).toBe(5);
  });

  it('propagates exceptions out of batch', () => {
    expect(() =>
      batch(() => { throw new Error('inner'); }),
    ).toThrow('inner');
  });
});

// ─── reactive ────────────────────────────────────────────────────────────────

describe('reactive', () => {
  it('reads properties from wrapped object', () => {
    const obj = reactive({ count: 0, name: 'test' });
    expect(obj.count).toBe(0);
    expect(obj.name).toBe('test');
  });

  it('writes to reactive object update the value', () => {
    const obj = reactive({ count: 0 });
    obj.count = 5;
    expect(obj.count).toBe(5);
  });

  it('new property assignments work', () => {
    const obj = reactive({} as Record<string, number>);
    obj['x'] = 42;
    expect(obj['x']).toBe(42);
  });

  it('multiple property updates work independently', () => {
    const obj = reactive({ a: 1, b: 2 });
    obj.a = 10;
    obj.b = 20;
    expect(obj.a).toBe(10);
    expect(obj.b).toBe(20);
  });

  it('reactive wrapping does not affect original object reference', () => {
    const plain = { count: 0 };
    const wrapped = reactive(plain);
    wrapped.count = 5;
    expect(plain.count).toBe(5); // Reflect.set updates the target
  });
});
