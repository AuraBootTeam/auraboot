import { render, screen, cleanup } from '@testing-library/react';
import { describe, it, expect, afterEach } from 'vitest';
import { SourceTypeBadge } from '~/shared/components/SourceTypeBadge';

describe('SourceTypeBadge', () => {
  afterEach(() => {
    cleanup();
  });

  it.each([
    ['physical', '物理'],
    ['namedQuery', '虚拟(namedQuery)'],
    ['endpoint', '虚拟(endpoint)'],
    ['sqlView', '虚拟(sqlView)'],
  ])('renders %s as "%s"', (type, expected) => {
    render(<SourceTypeBadge sourceType={type} />);
    expect(screen.getByText(expected)).toBeInTheDocument();
  });

  it('defaults to physical when sourceType is undefined', () => {
    render(<SourceTypeBadge />);
    expect(screen.getByText('物理')).toBeInTheDocument();
  });

  it('shows raw value for unknown sourceType', () => {
    render(<SourceTypeBadge sourceType="projection" />);
    expect(screen.getByText('projection')).toBeInTheDocument();
  });
});
