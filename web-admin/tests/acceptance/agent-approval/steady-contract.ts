export const steadyContract = Object.freeze({ minutes: 120, readers: 4, modelEveryMinutes: 5, windowMinutes: 15 });

export function canonicalJson(value: unknown): string {
  if (value === null || ['string', 'boolean'].includes(typeof value)
      || (typeof value === 'number' && Number.isFinite(value))) return JSON.stringify(value);
  if (Array.isArray(value)) return '[' + value.map(canonicalJson).join(',') + ']';
  if (typeof value === 'object' && value) return '{' + Object.keys(value).sort()
    .map(key => JSON.stringify(key) + ':' + canonicalJson((value as Record<string, unknown>)[key])).join(',') + '}';
  throw new Error('Not a JSON tool input');
}

export function assertSteadyTotals(elapsedMs: number, reads: number, turns: number, faults: number) {
  if (elapsedMs < 120 * 60_000 || reads !== 120 * 4 * 2 || turns !== 24 || faults !== 1) {
    throw new Error('Incomplete steady-state duration, business/memory reads, model turns, or fault evidence');
  }
}

export function assertRssBudget(first: number[], last: number[]) {
  if (first.length !== 15 || last.length !== 15 || [...first, ...last].some(v => !Number.isFinite(v) || v <= 0)) {
    throw new Error('Missing resource window samples');
  }
  const median = (values: number[]) => [...values].sort((a, b) => a - b)[7];
  const baselineKiB = median(first), finalKiB = median(last);
  const limitKiB = baselineKiB * 1.5 + 256 * 1024;
  if (finalKiB > limitKiB) throw new Error('RSS qualification budget exceeded');
  return { baselineKiB, finalKiB, limitKiB };
}

export function sseEvents(raw: string): Array<{ name: string; data: Record<string, unknown> }> {
  return raw.split(/\r?\n\r?\n/).flatMap(frame => {
    const name = frame.match(/^event:\s*(.+)$/m)?.[1]?.trim();
    const text = frame.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5).trim()).join('\n');
    if (!name || !text) return [];
    let data: unknown = JSON.parse(text);
    if (typeof data === 'string') data = JSON.parse(data);
    if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('Invalid SSE payload');
    return [{ name, data: data as Record<string, unknown> }];
  });
}
