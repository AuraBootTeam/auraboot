/**
 * Document Editor Page
 *
 * Provides a rich text document editor based on Tiptap/ProseMirror.
 * Supports creating and editing documents with formatting, links, and more.
 */

import React, { useState, useCallback, Suspense } from 'react';
import { RouteLoadingFallback } from '~/components/RouteLoadingFallback';

const DocumentPage = React.lazy(() =>
  import('~/framework/smart/components/document').then((m) => ({ default: m.DocumentPage })),
);

export default function DocumentEditorPage() {
  const [title, setTitle] = useState('Untitled Document');
  const [content, setContent] = useState('');
  const [saved, setSaved] = useState(false);

  const handleSave = useCallback(async (_doc: { title: string; html: string }) => {
    // TODO: Persist to backend when document storage API is ready
    setSaved(true);
    setTimeout(() => setSaved(false), 2000);
  }, []);

  return (
    <div className="mx-auto max-w-5xl px-4 py-6">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-900">Documents</h1>
        {saved && <span className="text-sm text-green-600">Saved</span>}
      </div>
      <Suspense fallback={<RouteLoadingFallback />}>
        <DocumentPage
          title={title}
          onTitleChange={setTitle}
          value={content}
          onChange={setContent}
          onSave={handleSave}
        />
      </Suspense>
    </div>
  );
}
