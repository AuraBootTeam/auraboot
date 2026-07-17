/**
 * Regression tests for the web-form embed snippet (G1-3).
 *
 * The shipped snippet was broken in three independent ways and every customer who
 * pasted it got a 404 + an empty page. These tests pin each property.
 *
 * Contract source: platform/src/main/resources/static/crm/web-form-sdk.js (header)
 *   <div id="auraboot-form"></div>
 *   <script src="https://your-host/api/crm/forms/__FORM_PID__/sdk.js"></script>
 */

import { describe, expect, it } from 'vitest';
import {
  WEB_FORM_CONTAINER_ID,
  buildWebFormEmbedSnippet,
  webFormSdkPath,
} from '../webFormEmbed';

describe('webFormSdkPath', () => {
  it('points at the real per-form SDK endpoint (InboundFormController)', () => {
    expect(webFormSdkPath('FORM-1')).toBe('/api/crm/forms/FORM-1/sdk.js');
  });
});

describe('buildWebFormEmbedSnippet', () => {
  const snippet = buildWebFormEmbedSnippet('https://app.example.com', 'FORM-1');

  it('property 1 — uses the served SDK path, not the non-existent /sdk/web-form.js', () => {
    expect(snippet).toContain('https://app.example.com/api/crm/forms/FORM-1/sdk.js');
    expect(snippet).not.toContain('/sdk/web-form.js');
  });

  it('property 2 — bakes the pid into the URL and emits no bogus data-form-id attribute', () => {
    // The SDK substitutes __FORM_PID__ server-side; it never reads a data attribute.
    expect(snippet).not.toContain('data-form-id');
    expect(snippet).toContain('/forms/FORM-1/');
  });

  it('property 3 — ships the container element the SDK looks up', () => {
    expect(snippet).toContain(`<div id="${WEB_FORM_CONTAINER_ID}"></div>`);
    expect(WEB_FORM_CONTAINER_ID).toBe('auraboot-form');
  });

  it('puts the container before the script so the SDK finds it on execution', () => {
    const containerIdx = snippet.indexOf('<div id="auraboot-form">');
    const scriptIdx = snippet.indexOf('<script');
    expect(containerIdx).toBeGreaterThanOrEqual(0);
    expect(scriptIdx).toBeGreaterThan(containerIdx);
  });

  it('emits a well-formed script tag', () => {
    expect(snippet).toMatch(
      /^<div id="auraboot-form"><\/div>\n<script src="https:\/\/app\.example\.com\/api\/crm\/forms\/FORM-1\/sdk\.js"><\/script>$/,
    );
  });

  it('edge: trailing slashes on the origin do not produce a double slash', () => {
    expect(buildWebFormEmbedSnippet('https://app.example.com/', 'F2')).toContain(
      'https://app.example.com/api/crm/forms/F2/sdk.js',
    );
    expect(buildWebFormEmbedSnippet('https://app.example.com//', 'F2')).not.toContain(
      '.com//api',
    );
  });

  it('edge: empty origin yields a root-relative src (still resolvable same-origin)', () => {
    expect(buildWebFormEmbedSnippet('', 'F3')).toContain('src="/api/crm/forms/F3/sdk.js"');
  });
});
