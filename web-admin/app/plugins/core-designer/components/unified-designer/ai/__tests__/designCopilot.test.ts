import { describe, it, expect } from 'vitest';
import {
  buildDesignCopilotPrompt,
  parseDesignCopilotResponse,
  applyDesignBlocks,
  isBlockLocked,
} from '../designCopilot';
import type { PageSchemaV3 } from '../../types';

describe('buildDesignCopilotPrompt', () => {
  const base = {
    kind: 'form' as const,
    allowedBlockTypes: ['form-section', 'field', 'action-bar', 'action'],
    rootBlockType: 'form',
  };

  it('describes the current kind and only the allowed block types', () => {
    const p = buildDesignCopilotPrompt(base);
    expect(p).toContain('current page kind is "form"');
    expect(p).toContain('- form-section');
    expect(p).toContain('- field');
    expect(p).not.toContain('- dashboard');
  });

  it('instructs not to emit the root wrapper or ids', () => {
    const p = buildDesignCopilotPrompt(base);
    expect(p).toContain('do NOT emit the "form" wrapper');
    expect(p).toContain('Do NOT include "id"');
  });

  it('injects model fields, current blocks, and domain guidance when provided', () => {
    const p = buildDesignCopilotPrompt({
      ...base,
      modelFields: [{ code: 'sku', name: 'SKU', type: 'string' }],
      currentBlocks: [{ id: 'b1', blockType: 'form-section' }],
      domainGuidance: 'This is a public QR scan-landing page.',
    });
    expect(p).toContain('sku (string): SKU');
    expect(p).toContain('"blockType": "form-section"');
    expect(p).toContain('This is a public QR scan-landing page.');
  });

  it('notes empty content when no current blocks', () => {
    expect(buildDesignCopilotPrompt(base)).toContain('no content blocks');
  });
});

describe('parseDesignCopilotResponse', () => {
  it('parses plain JSON and assigns ids to every block + child', () => {
    const res = parseDesignCopilotResponse(
      JSON.stringify({
        _mergeMode: 'replace',
        blocks: [{ blockType: 'form-section', blocks: [{ blockType: 'field', field: 'sku' }] }],
      }),
    );
    expect(res.mergeMode).toBe('replace');
    expect(res.blocks[0].id).toBeTruthy();
    expect(res.blocks[0].blocks?.[0].id).toBeTruthy();
    expect(res.blocks[0].blocks?.[0].field).toBe('sku');
  });

  it('strips markdown fences', () => {
    const res = parseDesignCopilotResponse(
      '```json\n{"blocks":[{"blockType":"field"}]}\n```',
    );
    expect(res.blocks).toHaveLength(1);
    expect(res.blocks[0].blockType).toBe('field');
  });

  it('tolerates surrounding prose', () => {
    const res = parseDesignCopilotResponse(
      'Sure! Here you go:\n{"blocks":[{"blockType":"field"}]}\nHope that helps.',
    );
    expect(res.blocks[0].blockType).toBe('field');
  });

  it('defaults mergeMode to replace', () => {
    expect(parseDesignCopilotResponse('{"blocks":[]}').mergeMode).toBe('replace');
  });

  it('overwrites AI-emitted ids and dedupes against existing ids', () => {
    const res = parseDesignCopilotResponse(
      JSON.stringify({ blocks: [{ id: 'dup', blockType: 'field' }] }),
      { idFactory: () => 'dup', existingIds: new Set(['dup']) },
    );
    expect(res.blocks[0].id).not.toBe('dup'); // collided → suffixed unique
  });

  it('throws on a response without a blocks array', () => {
    expect(() => parseDesignCopilotResponse('{"foo":1}')).toThrow(/blocks/);
  });
});

describe('applyDesignBlocks', () => {
  const formDoc = (): PageSchemaV3 => ({
    schemaVersion: 3,
    kind: 'form',
    id: 'page-1',
    blocks: [
      { id: 'form-root', blockType: 'form', blocks: [{ id: 'manual-1', blockType: 'form-section' }] },
    ],
  });

  it('replaces the root container children in replace mode', () => {
    const next = applyDesignBlocks(
      formDoc(),
      { mergeMode: 'replace', blocks: [{ id: 'ai-1', blockType: 'form-section' }] },
      'form',
    );
    expect(next.blocks[0].blocks).toHaveLength(1);
    expect(next.blocks[0].blocks?.[0].id).toBe('ai-1');
  });

  it('appends to the root container children in append mode (manual content preserved)', () => {
    const next = applyDesignBlocks(
      formDoc(),
      { mergeMode: 'append', blocks: [{ id: 'ai-1', blockType: 'form-section' }] },
      'form',
    );
    expect(next.blocks[0].blocks?.map((b) => b.id)).toEqual(['manual-1', 'ai-1']);
  });

  it('wraps blocks in a fresh root container when none exists', () => {
    const doc: PageSchemaV3 = { schemaVersion: 3, kind: 'form', id: 'p', blocks: [] };
    const next = applyDesignBlocks(doc, { mergeMode: 'replace', blocks: [{ id: 'ai-1', blockType: 'field' }] }, 'form');
    expect(next.blocks[0].blockType).toBe('form');
    expect(next.blocks[0].blocks?.[0].id).toBe('ai-1');
  });

  it('merges at the page root for composite (null root container)', () => {
    const doc: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'composite',
      id: 'p',
      blocks: [{ id: 'm1', blockType: 'widget' }],
    };
    const next = applyDesignBlocks(doc, { mergeMode: 'append', blocks: [{ id: 'ai-1', blockType: 'widget' }] }, null);
    expect(next.blocks.map((b) => b.id)).toEqual(['m1', 'ai-1']);
  });

  it('does not mutate the input document', () => {
    const doc = formDoc();
    const snapshot = JSON.stringify(doc);
    applyDesignBlocks(doc, { mergeMode: 'replace', blocks: [{ id: 'ai-1', blockType: 'field' }] }, 'form');
    expect(JSON.stringify(doc)).toBe(snapshot);
  });
});

describe('MD-2 — provenance + locked-block preservation', () => {
  it('parser stamps source:ai provenance on generated blocks (and children)', () => {
    const res = parseDesignCopilotResponse(
      JSON.stringify({ blocks: [{ blockType: 'form-section', blocks: [{ blockType: 'field' }] }] }),
    );
    expect((res.blocks[0].extension as any)?.source).toBe('ai');
    expect((res.blocks[0].blocks?.[0].extension as any)?.source).toBe('ai');
  });

  it('isBlockLocked detects props.aiLocked', () => {
    expect(isBlockLocked({ id: 'a', blockType: 'field', props: { aiLocked: true } })).toBe(true);
    expect(isBlockLocked({ id: 'b', blockType: 'field', props: { aiLocked: false } })).toBe(false);
    expect(isBlockLocked({ id: 'c', blockType: 'field' })).toBe(false);
  });

  const docWithLock = (): PageSchemaV3 => ({
    schemaVersion: 3,
    kind: 'form',
    id: 'p',
    blocks: [
      {
        id: 'form-root',
        blockType: 'form',
        blocks: [
          { id: 'locked-1', blockType: 'form-section', props: { aiLocked: true } },
          { id: 'unlocked-1', blockType: 'form-section' },
        ],
      },
    ],
  });

  it('replace preserves locked blocks and drops unlocked ones, then adds AI blocks', () => {
    const next = applyDesignBlocks(
      docWithLock(),
      { mergeMode: 'replace', blocks: [{ id: 'ai-1', blockType: 'form-section' }] },
      'form',
    );
    const ids = next.blocks[0].blocks?.map((b) => b.id);
    expect(ids).toContain('locked-1'); // locked manual block survived AI re-gen
    expect(ids).not.toContain('unlocked-1'); // unlocked manual block replaced
    expect(ids).toContain('ai-1');
    expect(ids?.[0]).toBe('locked-1'); // locked blocks kept first, AI after
  });

  it('append keeps all existing (locked + unlocked) and adds AI', () => {
    const next = applyDesignBlocks(
      docWithLock(),
      { mergeMode: 'append', blocks: [{ id: 'ai-1', blockType: 'form-section' }] },
      'form',
    );
    expect(next.blocks[0].blocks?.map((b) => b.id)).toEqual(['locked-1', 'unlocked-1', 'ai-1']);
  });

  it('composite: replace preserves locked blocks at the page root', () => {
    const doc: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'composite',
      id: 'p',
      blocks: [
        { id: 'lk', blockType: 'widget', props: { aiLocked: true } },
        { id: 'free', blockType: 'widget' },
      ],
    };
    const next = applyDesignBlocks(doc, { mergeMode: 'replace', blocks: [{ id: 'ai-1', blockType: 'widget' }] }, null);
    expect(next.blocks.map((b) => b.id)).toEqual(['lk', 'ai-1']);
  });
});
