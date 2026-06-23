import { describe, it, expect } from 'vitest';
import { render, fireEvent } from '@testing-library/react';
import { Sparkline } from '../Sparkline';

describe('Sparkline', () => {
  it('renders a polyline with one point per data entry', () => {
    const { container } = render(
      <Sparkline points={[1, 2, 3, 4, 5, 6, 7]} width={60} height={20} stroke="#635bff" />,
    );
    const polyline = container.querySelector('polyline');
    expect(polyline).not.toBeNull();
    const pts = polyline!.getAttribute('points')!.trim().split(/\s+/);
    expect(pts).toHaveLength(7);
    expect(polyline!.getAttribute('stroke')).toBe('#635bff');
  });

  it('renders nothing when points is empty (avoids dead-UI baseline)', () => {
    const { container } = render(<Sparkline points={[]} width={60} height={20} />);
    expect(container.querySelector('svg')).toBeNull();
    expect(container.querySelector('line')).toBeNull();
  });

  it('renders nothing for a single-value series', () => {
    const { container } = render(<Sparkline points={[5]} width={60} height={20} />);
    expect(container.querySelector('svg')).toBeNull();
  });

  // ── Tooltip state / handler tests ────────────────────────────────────────

  it('tooltip is hidden by default', () => {
    const { queryByTestId } = render(<Sparkline points={[10, 20, 30]} />);
    expect(queryByTestId('sparkline-tooltip')).toBeNull();
  });

  it('shows tooltip with correct value when hovering point N', () => {
    const points = [10, 20, 30];
    const { container, getByTestId } = render(<Sparkline points={points} />);

    // Hit-area circles are rendered one per point
    const hitCircles = Array.from(container.querySelectorAll('circle[fill="transparent"]'));
    expect(hitCircles).toHaveLength(3);

    // Hover the second point (index 1, value 20)
    fireEvent.mouseEnter(hitCircles[1]);

    const tooltip = getByTestId('sparkline-tooltip');
    expect(tooltip).not.toBeNull();

    const tooltipText = getByTestId('sparkline-tooltip-text');
    expect(tooltipText.textContent).toBe('20');
  });

  it('shows tooltip with label when labels prop is provided', () => {
    const points = [10, 20, 30];
    const labels = ['Jan', 'Feb', 'Mar'];
    const { container, getByTestId } = render(<Sparkline points={points} labels={labels} />);

    const hitCircles = Array.from(container.querySelectorAll('circle[fill="transparent"]'));
    // Hover the third point (index 2, value 30, label "Mar")
    fireEvent.mouseEnter(hitCircles[2]);

    const tooltipText = getByTestId('sparkline-tooltip-text');
    expect(tooltipText.textContent).toBe('Mar: 30');
  });

  it('hides tooltip when mouse leaves the SVG', () => {
    const points = [5, 15, 25];
    const { container, queryByTestId } = render(<Sparkline points={points} />);

    const hitCircles = Array.from(container.querySelectorAll('circle[fill="transparent"]'));
    fireEvent.mouseEnter(hitCircles[0]);

    // Tooltip should be visible now
    expect(queryByTestId('sparkline-tooltip')).not.toBeNull();

    // Mouse leaves the SVG wrapper
    const svg = container.querySelector('svg')!;
    fireEvent.mouseLeave(svg);

    expect(queryByTestId('sparkline-tooltip')).toBeNull();
  });

  it('switches tooltip value when hovering a different point', () => {
    const points = [100, 200, 300];
    const { container, getByTestId } = render(<Sparkline points={points} />);

    const hitCircles = Array.from(container.querySelectorAll('circle[fill="transparent"]'));

    fireEvent.mouseEnter(hitCircles[0]);
    expect(getByTestId('sparkline-tooltip-text').textContent).toBe('100');

    fireEvent.mouseEnter(hitCircles[2]);
    expect(getByTestId('sparkline-tooltip-text').textContent).toBe('300');
  });

  it('renders a hit-area circle for each data point', () => {
    const points = [1, 2, 3, 4, 5];
    const { container } = render(<Sparkline points={points} />);
    const hitCircles = container.querySelectorAll('circle[fill="transparent"]');
    expect(hitCircles).toHaveLength(5);
  });

  it('accepts optional labels without breaking when labels are absent', () => {
    // Ensure the component renders correctly without labels (backward compat)
    const { container } = render(<Sparkline points={[7, 14, 21]} />);
    const svg = container.querySelector('svg');
    expect(svg).not.toBeNull();
    const polyline = container.querySelector('polyline');
    expect(polyline).not.toBeNull();
  });
});
