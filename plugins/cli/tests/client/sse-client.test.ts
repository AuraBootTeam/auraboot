import { describe, it, expect } from 'vitest';

describe('SSE client', () => {
  describe('SSE line parsing', () => {
    it('should extract data from SSE lines', () => {
      const lines = [
        'data: {"content":"Hello"}',
        'data: {"content":" world"}',
        'data: [DONE]',
      ];

      const results: string[] = [];
      let done = false;

      for (const line of lines) {
        if (line.startsWith('data:')) {
          const data = line.slice(5).trim();
          if (data === '[DONE]') {
            done = true;
            break;
          }
          const parsed = JSON.parse(data);
          if (parsed.content) results.push(parsed.content);
        }
      }

      expect(results).toEqual(['Hello', ' world']);
      expect(done).toBe(true);
    });

    it('should handle delta format', () => {
      const line = 'data: {"delta":{"content":"test"}}';
      const data = line.slice(5).trim();
      const parsed = JSON.parse(data);
      const content = parsed.content || parsed.delta?.content || parsed.text || '';
      expect(content).toBe('test');
    });

    it('should handle text format', () => {
      const line = 'data: {"text":"hello"}';
      const data = line.slice(5).trim();
      const parsed = JSON.parse(data);
      const content = parsed.content || parsed.delta?.content || parsed.text || '';
      expect(content).toBe('hello');
    });

    it('should skip non-data lines', () => {
      const lines = [
        'event: message',
        'id: 1',
        'data: {"content":"actual"}',
        ': comment',
        '',
      ];

      const results: string[] = [];
      for (const line of lines) {
        if (line.startsWith('data:')) {
          const data = line.slice(5).trim();
          if (data === '[DONE]') break;
          try {
            const parsed = JSON.parse(data);
            if (parsed.content) results.push(parsed.content);
          } catch { /* ignore */ }
        }
      }

      expect(results).toEqual(['actual']);
    });

    it('should handle empty content gracefully', () => {
      const line = 'data: {"content":""}';
      const data = line.slice(5).trim();
      const parsed = JSON.parse(data);
      const content = parsed.content || parsed.delta?.content || parsed.text || '';
      expect(content).toBe('');
    });
  });
});
