/**
 * Generic registry primitive for runtime lookup tables.
 *
 * Double-register warns instead of throwing so HMR can re-run init modules
 * without crashing the app. Unknown keys return `undefined` so callers can
 * decide whether to fall back, warn, or render a placeholder.
 */

export interface Registry<T> {
  register(key: string, value: T): void;
  get(key: string): T | undefined;
  has(key: string): boolean;
  size(): number;
  list(): Array<[string, T]>;
}

export function createRegistry<T>(name: string): Registry<T> {
  const entries = new Map<string, T>();

  return {
    register(key, value) {
      if (entries.has(key)) {
        console.warn(`[${name}] re-registering "${key}" (HMR or duplicate init)`);
      }
      entries.set(key, value);
    },
    get(key) {
      return entries.get(key);
    },
    has(key) {
      return entries.has(key);
    },
    size() {
      return entries.size;
    },
    list() {
      return Array.from(entries.entries());
    },
  };
}
