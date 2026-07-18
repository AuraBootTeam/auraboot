export function sanitizeDmnFilenamePart(value: string): string {
  const normalized = value.trim().replace(/[^a-zA-Z0-9._-]+/g, '_').replace(/^_+|_+$/g, '');
  return normalized || 'decision_table';
}

export function downloadDmnXml(decisionCode: string, dmnXml: string): void {
  if (
    typeof window === 'undefined' ||
    typeof document === 'undefined' ||
    !window.URL?.createObjectURL ||
    !dmnXml
  ) {
    return;
  }
  const blob = new Blob([dmnXml], { type: 'application/xml;charset=utf-8' });
  const url = window.URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `${sanitizeDmnFilenamePart(decisionCode)}.dmn.xml`;
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.URL.revokeObjectURL(url);
}
