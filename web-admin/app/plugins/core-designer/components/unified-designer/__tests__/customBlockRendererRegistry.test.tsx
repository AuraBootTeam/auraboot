import { afterEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { RecursiveBlockRenderer } from '../runtime/RecursiveBlockRenderer';
import {
  clearCustomBlockRenderers,
  registerCustomBlockRenderer,
  type CustomBlockRendererProps,
} from '../runtime/customBlockRendererRegistry';
import type { PageSchemaV3 } from '../types';

function schemaWith(blockType: string): PageSchemaV3 {
  return {
    schemaVersion: 3,
    kind: 'form',
    id: 'custom_render_page',
    blocks: [{ id: 'cb1', blockType, props: { label: 'Demo' } }],
  };
}

describe('custom block renderer registry (runtime preview extension point)', () => {
  afterEach(() => clearCustomBlockRenderers());

  it('renders a registered custom renderer for its blockType (live widget, not a generic box)', () => {
    function DemoRenderer({ block }: CustomBlockRendererProps) {
      return <div data-testid={`demo-${block.id}`}>score: {String(block.props?.label)}</div>;
    }
    registerCustomBlockRenderer('scannability-qc', DemoRenderer);

    render(<RecursiveBlockRenderer schema={schemaWith('scannability-qc')} />);

    expect(screen.getByTestId('demo-cb1')).toHaveTextContent('score: Demo');
    // It replaced — not wrapped — the generic container.
    expect(screen.queryByTestId('runtime-block-cb1')).not.toBeInTheDocument();
  });

  it('falls back to the generic container for an unregistered custom blockType (zero regression)', () => {
    render(<RecursiveBlockRenderer schema={schemaWith('totally-unknown-block')} />);

    expect(screen.getByTestId('runtime-block-cb1')).toBeInTheDocument();
  });
});
