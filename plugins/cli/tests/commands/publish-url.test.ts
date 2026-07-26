import { describe, expect, it } from 'vitest';
import { resolveVisitUrl } from '../../src/commands/publish.js';

describe('publish visit URL', () => {
  it('uses the first visible plugin menu path instead of a hard-coded sample route', () => {
    expect(resolveVisitUrl('http://127.0.0.1:6496/', [
      { path: '/hidden', visible: false },
      { path: '/p/clientv4final_feedback', visible: true },
    ])).toBe('http://127.0.0.1:6496/p/clientv4final_feedback');
  });

  it('omits the hint when the plugin has no navigable menu', () => {
    expect(resolveVisitUrl('http://127.0.0.1:6496', [])).toBeNull();
  });
});
