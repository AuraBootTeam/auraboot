import { describe, it, expect } from 'vitest';
import { renderRequestToPdf, type ChromiumLike } from '../cli-core';

const fakeChromium: ChromiumLike = {
  async launch() {
    return {
      async newPage() {
        return {
          async setContent() {},
          async pdf() {
            return new TextEncoder().encode('%PDF-FAKE');
          },
        };
      },
      async close() {},
    };
  },
};

const loadFake = async () => fakeChromium;

describe('renderRequestToPdf', () => {
  it('parses a render request and returns PDF bytes', async () => {
    const req = JSON.stringify({
      model: { blocks: [{ blockType: 'rich-text', content: 'hi' }] },
      dataSets: {},
    });
    const pdf = await renderRequestToPdf(req, loadFake);
    expect(pdf.subarray(0, 5).toString('latin1')).toBe('%PDF-');
  });

  it('accepts the canonical `body` block list (not just `blocks`)', async () => {
    const req = JSON.stringify({
      model: { title: 't', body: [{ blockType: 'rich-text', content: 'hi' }] },
    });
    const pdf = await renderRequestToPdf(req, loadFake);
    expect(pdf.subarray(0, 5).toString('latin1')).toBe('%PDF-');
  });

  it('rejects a request missing both model.blocks[] and model.body[]', async () => {
    await expect(renderRequestToPdf('{"model":{}}', loadFake)).rejects.toThrow(/blocks/);
  });

  it('rejects invalid JSON', async () => {
    await expect(renderRequestToPdf('not json', loadFake)).rejects.toThrow();
  });
});
