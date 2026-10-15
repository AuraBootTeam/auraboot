import { describe, it, expect } from 'vitest';
import { renderRequestToPdf, renderRequestToHtml, type ChromiumLike } from '../cli-core';

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

describe('renderRequestToHtml', () => {
  it('exports report values as self-contained escaped HTML without a browser', () => {
    const bytes = renderRequestToHtml(
      JSON.stringify({
        model: {
          title: '客户分析',
          body: [
            { blockType: 'rich-text', content: '<script>alert(1)</script>客户结论' },
            {
              blockType: 'table',
              dataSource: 'customers',
              columns: [{ field: 'name', label: '客户名称' }],
            },
          ],
        },
        dataSets: { customers: [{ name: '华辰 <科技>' }] },
      }),
    );
    const html = bytes.toString('utf8');
    expect(html.toLowerCase()).toContain('<!doctype html>');
    expect(html).toContain('客户名称');
    expect(html).toContain('华辰 &lt;科技&gt;');
    expect(html).toContain('&lt;script&gt;');
    expect(html).not.toContain('<script>');
    expect(html).not.toMatch(/<script[^>]+src=/);
  });
  it('rejects missing report blocks rather than exporting an empty success', () => {
    expect(() => renderRequestToHtml('{"model":{}}')).toThrow(/blocks/);
  });
});
