import React, { useEffect, useState } from 'react';
import type { EditorProps } from '@monaco-editor/react';

const ClientEditor = React.lazy(() => import('./MonacoEditor.client'));

/** A client-only boundary for the platform's bundled Monaco editor. */
export default function MonacoEditor(props: EditorProps) {
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);
  if (!mounted) return null;
  return (
    <React.Suspense fallback={null}>
      <ClientEditor {...props} />
    </React.Suspense>
  );
}
