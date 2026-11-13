/** Full-width scrolling surfaces may sit one level inside the exported ref. */
function scrollingSurfaces(target: HTMLElement): HTMLElement[] {
  const view = target.ownerDocument.defaultView;
  if (!view) return [];
  return Array.from(target.querySelectorAll<HTMLElement>('*')).filter((node) => {
    const style = view.getComputedStyle(node);
    return (
      /(auto|scroll)/.test(`${style.overflowY} ${style.overflow}`) &&
      node.scrollHeight > node.clientHeight + 1 &&
      node.clientWidth >= target.clientWidth * 0.8
    );
  });
}

export function measurePdfCapture(target: HTMLElement): { width: number; height: number } {
  const origin = target.getBoundingClientRect();
  let width = Math.max(target.clientWidth, target.scrollWidth);
  let height = Math.max(target.clientHeight, target.scrollHeight);
  for (const node of scrollingSurfaces(target)) {
    const box = node.getBoundingClientRect();
    width = Math.max(width, box.left - origin.left + node.scrollWidth);
    height = Math.max(height, box.top - origin.top + node.scrollHeight);
  }
  return { width: Math.ceil(width), height: Math.ceil(height) };
}

/** Expand only the cloned capture surface; never mutate the live designer. */
export function preparePdfCapture(
  doc: Document,
  target: HTMLElement,
  width: number,
  height: number,
): void {
  const surfaces = scrollingSurfaces(target).map((node) => ({ node, height: node.scrollHeight }));
  for (const { node, height: fullHeight } of surfaces) {
    node.style.height = `${fullHeight}px`;
    node.style.flex = 'none';
    node.style.overflow = 'visible';
    node.scrollTop = 0;
    node.scrollLeft = 0;
  }
  target.style.width = `${width}px`;
  target.style.height = `${height}px`;
  for (let node: HTMLElement | null = target; node; node = node.parentElement) {
    node.style.overflow = 'visible';
    node.scrollTop = 0;
    node.scrollLeft = 0;
  }
  sanitizeUnsupportedPdfColors(doc.documentElement);
}

function sanitizeUnsupportedPdfColors(root: HTMLElement): void {
  const view = root.ownerDocument.defaultView;
  if (!view) return;
  const nodes = [root, ...Array.from(root.querySelectorAll<HTMLElement>('*'))];
  const colors = new Map<string, string>();
  for (const node of nodes) {
    const style = view.getComputedStyle(node);
    for (const [property, fallback] of [
      ['color', '#111827'],
      ['background-color', '#ffffff'],
      ['border-top-color', '#d1d5db'],
      ['border-right-color', '#d1d5db'],
      ['border-bottom-color', '#d1d5db'],
      ['border-left-color', '#d1d5db'],
      ['outline-color', '#2563eb'],
      ['text-decoration-color', '#111827'],
      ['caret-color', '#111827'],
      ['fill', '#111827'],
      ['stroke', '#111827'],
    ]) {
      const value = style.getPropertyValue(property);
      if (usesUnsupportedColor(value)) {
        const key = `${fallback}:${value}`;
        if (!colors.has(key)) colors.set(key, rasterColor(node.ownerDocument, value, fallback));
        node.style.setProperty(property, colors.get(key)!, 'important');
      }
    }
    sanitizeComplexColor(node, style, 'box-shadow', 'none');
    sanitizeComplexColor(node, style, 'text-shadow', 'none');
    sanitizeComplexColor(node, style, 'background-image', 'none');
  }
}

/** Browser canvas resolves CSS Color 4 values to an sRGB pixel for html2canvas. */
function rasterColor(doc: Document, value: string, fallback: string): string {
  try {
    const canvas = doc.createElement('canvas');
    canvas.width = canvas.height = 1;
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    if (!ctx) return fallback;
    ctx.fillStyle = value;
    ctx.fillRect(0, 0, 1, 1);
    const [r, g, b, a] = ctx.getImageData(0, 0, 1, 1).data;
    return `rgba(${r}, ${g}, ${b}, ${a / 255})`;
  } catch {
    return fallback;
  }
}

function sanitizeComplexColor(
  node: HTMLElement,
  style: CSSStyleDeclaration,
  property: string,
  fallback: string,
): void {
  const value = style.getPropertyValue(property);
  if (!usesUnsupportedColor(value)) return;
  node.style.setProperty(property, fallback, 'important');
}

function usesUnsupportedColor(value: string | null | undefined): boolean {
  return Boolean(value && /(?:oklch|oklab|lab|lch|color)\(/i.test(value));
}
