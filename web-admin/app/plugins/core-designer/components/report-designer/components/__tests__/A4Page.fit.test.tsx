import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { A4Page, reportPageMetrics } from '../A4Page';
import type { PageConfig } from '../../types';
const page: PageConfig = {
  size: 'A4',
  orientation: 'landscape',
  margin: { top: 10, right: 10, bottom: 10, left: 10 },
};
describe('Report physical paper and viewport fit', () => {
  it('fits landscape paper without changing the physical paper or margins', () => {
    const before = JSON.stringify(page);
    render(
      <A4Page page={page} fitWidth={600}>
        <p>Delivery report</p>
      </A4Page>,
    );
    const paper = screen.getByTestId('report-paper');
    expect(paper).toHaveStyle({ width: '891px', minHeight: '630px', padding: '30px' });
    expect(Number(paper.getAttribute('data-scale'))).toBeCloseTo(600 / 891);
    expect(JSON.stringify(page)).toBe(before);
  });
  it('keeps natural size when no fit is requested or the viewport is wider', () => {
    expect(reportPageMetrics(page).zoom).toBe(1);
    expect(reportPageMetrics(page, 1500).zoom).toBe(1);
    expect(reportPageMetrics(page, 0).zoom).toBe(1);
  });
  it('uses orientation and supported paper sizes when deriving fit', () => {
    expect(reportPageMetrics({ ...page, orientation: 'portrait' }, 500)).toMatchObject({
      widthPx: 630,
      minHeightPx: 891,
      zoom: 500 / 630,
    });
    expect(reportPageMetrics({ ...page, size: 'A3' }, 600)).toMatchObject({
      widthPx: 1260,
      minHeightPx: 891,
      zoom: 600 / 1260,
    });
  });
});
