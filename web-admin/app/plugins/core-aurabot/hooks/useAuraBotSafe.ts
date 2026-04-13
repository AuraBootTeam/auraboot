// OSS slot stub — enterprise AuraBot integration lives in ent-aurabot-pro plugin.
// In OSS builds, AuraBot form-fill/registration calls are no-ops.

export type FormFillHandler = (_fields: Record<string, unknown>) => void | Promise<void>;

export interface AuraBotSafeHandle {
  readonly isAvailable: boolean;
  registerFormFillHandler(_handler: FormFillHandler): void;
  unregisterFormFillHandler(): void;
  ask(_prompt: string): Promise<null>;
  summarize(_text: string): Promise<null>;
}

const NOOP_HANDLE: AuraBotSafeHandle = {
  isAvailable: false,
  registerFormFillHandler: () => {},
  unregisterFormFillHandler: () => {},
  ask: async () => null,
  summarize: async () => null,
};

export function useAuraBotSafe(): AuraBotSafeHandle {
  return NOOP_HANDLE;
}
