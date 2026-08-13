import { useEffect, useRef, type RefObject } from 'react';

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

const activeModalStack: HTMLElement[] = [];
const inertOwnership = new Map<
  HTMLElement,
  { originalInert: boolean; owners: Set<HTMLElement> }
>();

export function useModalFocusTrap(
  open: boolean,
  containerRef: RefObject<HTMLElement | null>,
  onEscape: () => void,
) {
  const onEscapeRef = useRef(onEscape);
  onEscapeRef.current = onEscape;

  useEffect(() => {
    if (!open) return;
    const container = containerRef.current;
    if (!container) return;
    const previousIndex = activeModalStack.indexOf(container);
    if (previousIndex >= 0) activeModalStack.splice(previousIndex, 1);
    activeModalStack.push(container);
    const restoreBackground = makeBackgroundInert(container);
    const returnTarget =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const frame = window.requestAnimationFrame(() => {
      if (isTopmostModal(container)) firstFocusable(container)?.focus();
    });

    const onKeyDown = (event: KeyboardEvent) => {
      if (!isTopmostModal(container)) return;
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        onEscapeRef.current();
        return;
      }
      if (event.key !== 'Tab') return;
      const focusable = focusableElements(container);
      if (focusable.length === 0) {
        event.preventDefault();
        container.focus();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };

    document.addEventListener('keydown', onKeyDown, true);
    return () => {
      window.cancelAnimationFrame(frame);
      document.removeEventListener('keydown', onKeyDown, true);
      const stackIndex = activeModalStack.indexOf(container);
      if (stackIndex >= 0) activeModalStack.splice(stackIndex, 1);
      restoreBackground();
      window.requestAnimationFrame(() => {
        const topmost = activeModalStack[activeModalStack.length - 1];
        if (!topmost) {
          if (returnTarget?.isConnected) returnTarget.focus();
          return;
        }
        if (returnTarget?.isConnected && topmost.contains(returnTarget)) {
          returnTarget.focus();
          return;
        }
        firstFocusable(topmost)?.focus();
      });
    };
  }, [containerRef, open]);
}

function isTopmostModal(container: HTMLElement): boolean {
  return activeModalStack[activeModalStack.length - 1] === container;
}

function makeBackgroundInert(container: HTMLElement): () => void {
  const owned = new Set<HTMLElement>();
  let current: HTMLElement = container;
  let parent = current.parentElement;
  while (parent) {
    for (const child of parent.children) {
      if (!(child instanceof HTMLElement) || child === current) continue;
      const ownership = inertOwnership.get(child);
      if (ownership) {
        ownership.owners.add(container);
      } else {
        inertOwnership.set(child, {
          originalInert: child.inert,
          owners: new Set([container]),
        });
        child.inert = true;
      }
      owned.add(child);
    }
    if (parent === document.body) break;
    current = parent;
    parent = current.parentElement;
  }
  return () => {
    for (const element of owned) {
      const ownership = inertOwnership.get(element);
      if (!ownership) continue;
      ownership.owners.delete(container);
      if (ownership.owners.size === 0) {
        element.inert = ownership.originalInert;
        inertOwnership.delete(element);
      }
    }
  };
}

function firstFocusable(container: HTMLElement | null): HTMLElement | null {
  return focusableElements(container)[0] ?? container;
}

function focusableElements(container: HTMLElement | null): HTMLElement[] {
  if (!container) return [];
  return [...container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)].filter(
    (element) => !element.hidden && element.getAttribute('aria-hidden') !== 'true',
  );
}
