import { describe, expect, it } from 'vitest';
import { sanitizeHtml } from '../sanitizeHtml';

describe('safe native disclosure markup', () => {
  it('keeps a closed disclosure and its accessible summary', () => {
    const host = document.createElement('div');
    host.innerHTML = sanitizeHtml('<details><summary>Rules</summary><table><tbody><tr><td>2</td></tr></tbody></table></details>');
    expect(host.querySelector('details')?.open).toBe(false);
    expect(host.querySelector('summary')?.textContent).toBe('Rules');
    expect(host.querySelector('td')?.textContent).toBe('2');
  });
  it('strips scripts, toggle handlers and unsafe links inside disclosure content', () => {
    const host = document.createElement('div');
    host.innerHTML = sanitizeHtml('<details ontoggle="alert(1)"><summary onclick="alert(1)">Rules</summary><script>alert(1)</script><a href="javascript:alert(1)">link</a></details>');
    expect(host.querySelector('script')).toBeNull();
    expect(host.querySelector('[ontoggle],[onclick]')).toBeNull();
    expect(host.querySelector('a')?.hasAttribute('href')).toBe(false);
  });
});
