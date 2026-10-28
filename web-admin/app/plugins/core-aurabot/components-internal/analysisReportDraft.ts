import type { SimpleMessage } from '../components-shell/AuraBotProvider';

/** Only prose from this user turn may be proposed for review. Never borrow history. */
export function currentTurnNarrative(messages: SimpleMessage[], index: number): string {
  let start = index;
  while (start > 0 && messages[start - 1].sender !== 'user') start--;
  let end = index + 1;
  while (end < messages.length && messages[end].sender !== 'user') end++;
  return messages
    .slice(start, end)
    .filter((message) => message.sender === 'bot' && message.type === 'text')
    .map((message) => message.content.trim())
    .filter(Boolean)
    .join('\n\n');
}

/** Remove duplicate Markdown tables, preserving prose (including literal pipes). */
export function narrativeWithoutTables(value: string): string {
  const lines = value.split('\n');
  const output: string[] = [];
  let fenced = false;
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index];
    if (/^\s*(```|~~~)/.test(line)) fenced = !fenced;
    const separator = lines[index + 1]?.trim().replace(/^\|/, '').replace(/\|$/, '');
    if (
      !fenced &&
      line.includes('|') &&
      separator?.includes('|') &&
      separator.split('|').every((cell) => /^\s*:?-{3,}:?\s*$/.test(cell))
    ) {
      index++;
      while (index + 1 < lines.length && lines[index + 1].trim() && lines[index + 1].includes('|'))
        index++;
      continue;
    }
    output.push(line);
  }
  return output
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}
