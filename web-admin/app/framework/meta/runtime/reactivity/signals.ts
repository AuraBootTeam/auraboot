/**
 * Signals - 响应式依赖追踪系统
 * 替代轮询机制，实现细粒度的响应式更新
 *
 * 灵感来自 Preact Signals 和 Vue 3 Reactivity
 */

type Subscriber = () => void;
type Getter<T> = () => T;

/**
 * Signal - 响应式值
 */
export class Signal<T> {
  private _value: T;
  private subscribers = new Set<Subscriber>();

  constructor(initialValue: T) {
    this._value = initialValue;
  }

  get value(): T {
    // 收集依赖
    if (currentEffect) {
      this.subscribers.add(currentEffect);
    }
    return this._value;
  }

  set value(newValue: T) {
    if (newValue !== this._value) {
      this._value = newValue;
      this.notify();
    }
  }

  private notify() {
    this.subscribers.forEach((subscriber) => subscriber());
  }

  subscribe(callback: Subscriber): () => void {
    this.subscribers.add(callback);
    return () => this.subscribers.delete(callback);
  }

  dispose() {
    this.subscribers.clear();
  }
}

/**
 * Computed - 派生值
 * 自动追踪依赖的 Signal，当依赖变化时重新计算
 */
export class Computed<T> {
  private _value: T | undefined;
  private _dirty = true;
  private subscribers = new Set<Subscriber>();
  private dependencies = new Set<Signal<any>>();

  constructor(private getter: Getter<T>) {}

  get value(): T {
    if (this._dirty) {
      // 收集依赖
      const prevEffect = currentEffect;
      currentEffect = () => {
        this._dirty = true;
        this.notify();
      };

      this._value = this.getter();
      this._dirty = false;

      currentEffect = prevEffect;
    }

    // 让外部订阅者也能收集到这个 computed
    if (currentEffect) {
      this.subscribers.add(currentEffect);
    }

    return this._value!;
  }

  private notify() {
    this.subscribers.forEach((subscriber) => subscriber());
  }

  subscribe(callback: Subscriber): () => void {
    this.subscribers.add(callback);
    return () => this.subscribers.delete(callback);
  }

  dispose() {
    this.subscribers.clear();
    this.dependencies.clear();
  }
}

/**
 * Effect - 副作用
 * 自动追踪依赖的 Signal/Computed，当依赖变化时重新执行
 */
export class Effect {
  private cleanup?: () => void;

  constructor(private fn: () => void | (() => void)) {
    this.run();
  }

  private run() {
    // 清理上次的副作用
    this.cleanup?.();

    const prevEffect = currentEffect;
    currentEffect = () => this.run();

    const result = this.fn();
    if (typeof result === 'function') {
      this.cleanup = result;
    }

    currentEffect = prevEffect;
  }

  dispose() {
    this.cleanup?.();
    currentEffect = null;
  }
}

// 全局当前 effect 追踪
let currentEffect: Subscriber | null = null;

/**
 * 创建响应式 Signal
 */
export function signal<T>(initialValue: T): Signal<T> {
  return new Signal(initialValue);
}

/**
 * 创建派生 Computed
 */
export function computed<T>(getter: Getter<T>): Computed<T> {
  return new Computed(getter);
}

/**
 * 创建副作用 Effect
 */
export function effect(fn: () => void | (() => void)): Effect {
  return new Effect(fn);
}

/**
 * Batch - 批量更新
 * 在批量更新期间，所有的通知都会被延迟到批量结束后执行
 */
let batchDepth = 0;
let pendingEffects = new Set<Subscriber>();

export function batch(fn: () => void): void {
  batchDepth++;
  try {
    fn();
  } finally {
    batchDepth--;
    if (batchDepth === 0) {
      const effects = Array.from(pendingEffects);
      pendingEffects.clear();
      effects.forEach((effect) => effect());
    }
  }
}

/**
 * 将通知添加到批量队列
 */
/**
 * Reactive - 响应式对象
 * 将普通对象转为响应式对象
 */
export function reactive<T extends object>(target: T): T {
  const signals = new Map<string | symbol, Signal<any>>();

  return new Proxy(target, {
    get(target, key) {
      if (!signals.has(key)) {
        signals.set(key, signal(Reflect.get(target, key)));
      }
      return signals.get(key)!.value;
    },
    set(target, key, value) {
      if (!signals.has(key)) {
        signals.set(key, signal(value));
      } else {
        signals.get(key)!.value = value;
      }
      Reflect.set(target, key, value);
      return true;
    },
  });
}

/**
 * Watch - 监听响应式值变化
 */
export function watch<T>(
  getter: () => T,
  callback: (newValue: T, oldValue: T) => void,
): () => void {
  let oldValue = getter();

  const eff = effect(() => {
    const newValue = getter();
    if (newValue !== oldValue) {
      callback(newValue, oldValue);
      oldValue = newValue;
    }
  });

  return () => eff.dispose();
}
