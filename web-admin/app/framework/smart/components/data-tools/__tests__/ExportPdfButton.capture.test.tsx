import { describe, expect, it } from 'vitest';
import { measurePdfCapture, preparePdfCapture } from '../pdfCapture';

describe('PDF cloned capture preparation', () => {
  it('expands the full scroll surface and removes ancestor clipping without changing widget sizes', () => {
    const doc = document.implementation.createHTMLDocument('capture');
    doc.body.innerHTML =
      '<div style="overflow:hidden"><main style="height:400px;overflow:auto"><section style="height:180px"></section></main></div>';
    const target = doc.querySelector('main')!;
    preparePdfCapture(doc, target, 1200, 1700);
    expect(target.style.width).toBe('1200px');
    expect(target.style.height).toBe('1700px');
    expect(target.style.overflow).toBe('visible');
    expect(target.parentElement!.style.overflow).toBe('visible');
    expect(target.firstElementChild!.getAttribute('style')).toBe('height:180px');
  });

  it('includes a nested full-width scrolling canvas, preserving smaller widget scroll regions', () => {
    const target = document.createElement('div');
    target.innerHTML = '<main style="overflow:auto"><aside style="overflow:auto"></aside></main>';
    document.body.append(target);
    const canvas = target.querySelector('main')!;
    const note = target.querySelector('aside')!;
    for (const [node, width, height, full] of [
      [target, 1200, 400, 400],
      [canvas, 1200, 400, 1700],
      [note, 200, 100, 9000],
    ] as const) {
      Object.defineProperties(node, {
        clientWidth: { value: width },
        scrollWidth: { value: width },
        clientHeight: { value: height },
        scrollHeight: { value: full },
      });
    }
    expect(measurePdfCapture(target)).toEqual({ width: 1200, height: 1700 });
    preparePdfCapture(document, target, 1200, 1700);
    expect(canvas.style.height).toBe('1700px');
    expect(canvas.style.overflow).toBe('visible');
    expect(note.style.height).toBe('');
    target.remove();
  });

  it('retains supported card colors, SVG fills, strokes and transparent backgrounds', () => {
    const target = document.createElement('div');
    target.innerHTML =
      '<div style="color:rgb(12,34,56);background-color:rgb(230,240,255)"><svg><rect fill="#4569e8" stroke="none"/><path fill="none" stroke="#20a69c"/></svg></div>';
    document.body.append(target);
    const before = target.innerHTML;
    preparePdfCapture(document, target, 1200, 900);
    expect(target.innerHTML).toBe(before);
    expect(target.querySelector('rect')!.getAttribute('fill')).toBe('#4569e8');
    expect(target.querySelector('path')!.getAttribute('fill')).toBe('none');
    target.remove();
  });
});
