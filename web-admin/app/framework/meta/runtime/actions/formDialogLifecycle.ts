/** UI lifecycle only: no record, permission, request-key or command state. */
const scopes: Array<{ signal: AbortSignal; priority: number }> = [];

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
