/** UI lifecycle only: no record, permission, request-key or command state. */
const scopes: Array<{ signal: AbortSignal; priority: number }> = [];
const activeDialogs = new Set<symbol>();
const visibilityListeners = new Set<() => void>();

export function subscribeFormDialogVisibility(listener: () => void): () => void {
  visibilityListeners.add(listener);
  return () => { visibilityListeners.delete(listener); };
}

export function hasActiveFormDialog(): boolean { return activeDialogs.size > 0; }

/** Suspend an enclosing modal's focus trap while the shared form owns input. */
export function registerActiveFormDialog(): () => void {
  const identity = Symbol('form-dialog');
  activeDialogs.add(identity);
  visibilityListeners.forEach(listener => listener());
  return () => {
    activeDialogs.delete(identity);
    visibilityListeners.forEach(listener => listener());
  };
}

export function registerFormDialogScope(signal: AbortSignal, priority = 0): () => void {
  const scope = { signal, priority };
  scopes.push(scope);
  return () => {
    const index = scopes.indexOf(scope);
    if (index >= 0) scopes.splice(index, 1);
  };
}

export function currentFormDialogScope(): AbortSignal | undefined {
  // The root layout effect may run after a child's. Explicit modal priority
  // keeps its narrower lifetime authoritative regardless of effect order.
  return scopes.reduce<(typeof scopes)[number] | undefined>(
    (current, scope) => (!current || scope.priority >= current.priority ? scope : current),
    undefined,
  )?.signal;
}

export function cancelCurrentFormDialog(requestId?: number): void {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('dialog:form:cancel', { detail: { requestId } }));
  }
}
