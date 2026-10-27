/** Source focus anchors use existing page/block identity and actual public record PIDs. */
export interface DetailSourceFocus {
  opener: HTMLElement | null;
  block: HTMLElement | null;
  blockId: string | null;
  pageId: string | null;
  recordPid: string | null;
}
export function captureDetailSourceFocus(): DetailSourceFocus {
  const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  const block = opener?.closest<HTMLElement>('[data-aura-block-id]') ?? null;
  return {
    opener,
    block,
    blockId: block?.getAttribute('data-aura-block-id') ?? null,
    pageId: block?.closest('[data-aura-page-id]')?.getAttribute('data-aura-page-id') ?? null,
    recordPid: opener?.closest('[data-record-pid]')?.getAttribute('data-record-pid') ?? null,
  };
}
function available(element: HTMLElement | null): element is HTMLElement {
  return Boolean(
    element?.isConnected &&
    !element.matches(':disabled') &&
    !element.closest('[hidden],[aria-hidden="true"],[inert]'),
  );
}
function focusSource(element: HTMLElement): void {
  const temporary = !element.hasAttribute('tabindex');
  if (temporary) {
    element.setAttribute('tabindex', '-1');
    element.addEventListener('blur', () => element.removeAttribute('tabindex'), { once: true });
  }
  element.focus({ preventScroll: true });
}
/** Do not guess a replacement action: a rebuilt record returns to its semantic row. */
export function restoreDetailSourceFocus(anchor: DetailSourceFocus | null, failed: boolean): void {
  if (!anchor) return;
  if (!failed && available(anchor.opener)) {
    anchor.opener.focus({ preventScroll: true });
    return;
  }
  const block = available(anchor.block)
    ? anchor.block
    : anchor.blockId
      ? ([...document.querySelectorAll<HTMLElement>('[data-aura-block-id]')].find(
          (candidate) =>
            candidate.getAttribute('data-aura-block-id') === anchor.blockId &&
            (candidate.closest('[data-aura-page-id]')?.getAttribute('data-aura-page-id') ??
              null) === anchor.pageId,
        ) ?? null)
      : null;
  if (!available(block)) return;
  const row =
    !failed && anchor.recordPid
      ? ([...block.querySelectorAll<HTMLElement>('[data-record-pid]')].find(
          (candidate) => candidate.getAttribute('data-record-pid') === anchor.recordPid,
        ) ?? null)
      : null;
  focusSource(available(row) ? row : block);
}
