/**
 * DocumentPage Component
 *
 * A rich text document editor with support for @-referencing data records
 * and embedding data views (tables/charts). Built on Tiptap (ProseMirror).
 */

import React, { useState, useCallback, useEffect } from 'react';
import { useEditor, EditorContent } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import Placeholder from '@tiptap/extension-placeholder';
import Link from '@tiptap/extension-link';
import { cn } from '~/utils/cn';

export interface DocumentPageProps {
  /** Document content (HTML string) */
  value?: string;
  /** Callback when content changes */
  onChange?: (html: string) => void;
  /** Document title */
  title?: string;
  /** Callback when title changes */
  onTitleChange?: (title: string) => void;
  /** Whether the document is read-only */
  readOnly?: boolean;
  /** Callback when save is triggered */
  onSave?: (content: { title: string; html: string }) => void;
  /** Whether saving is in progress */
  saving?: boolean;
  /** Custom CSS class */
  className?: string;
}

interface ToolbarButtonProps {
  active?: boolean;
  disabled?: boolean;
  onClick: () => void;
  title: string;
  children: React.ReactNode;
}

const ToolbarButton: React.FC<ToolbarButtonProps> = ({
  active,
  disabled,
  onClick,
  title,
  children,
}) => (
  <button
    type="button"
    onClick={onClick}
    disabled={disabled}
    title={title}
    className={cn(
      'rounded p-1.5 text-sm transition-colors',
      active ? 'bg-blue-100 text-blue-700' : 'text-gray-600 hover:bg-gray-100',
      disabled && 'cursor-not-allowed opacity-50',
    )}
  >
    {children}
  </button>
);

/**
 * DocumentPage - Rich text document editor
 */
export const DocumentPage: React.FC<DocumentPageProps> = ({
  value = '',
  onChange,
  title = '',
  onTitleChange,
  readOnly = false,
  onSave,
  saving = false,
  className,
}) => {
  const [docTitle, setDocTitle] = useState(title);
  const [lastSaved, setLastSaved] = useState<Date | null>(null);

  const editor = useEditor({
    immediatelyRender: false,
    extensions: [
      StarterKit.configure({
        heading: { levels: [1, 2, 3] },
      }),
      Placeholder.configure({
        placeholder: 'Start writing your document...',
      }),
      Link.configure({
        openOnClick: false,
        autolink: true,
      }),
    ],
    content: value,
    editable: !readOnly,
    onUpdate: ({ editor }) => {
      onChange?.(editor.getHTML());
    },
  });

  // Update editor content when value prop changes
  useEffect(() => {
    if (editor && value !== editor.getHTML()) {
      editor.commands.setContent(value);
    }
  }, [value, editor]);

  // Update editable state
  useEffect(() => {
    if (editor) {
      editor.setEditable(!readOnly);
    }
  }, [editor, readOnly]);

  const handleTitleChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      setDocTitle(e.target.value);
      onTitleChange?.(e.target.value);
    },
    [onTitleChange],
  );

  const handleSave = useCallback(() => {
    if (!editor) return;
    onSave?.({ title: docTitle, html: editor.getHTML() });
    setLastSaved(new Date());
  }, [editor, docTitle, onSave]);

  // Keyboard shortcut: Ctrl+S
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 's') {
        e.preventDefault();
        handleSave();
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [handleSave]);

  const addLink = useCallback(() => {
    if (!editor) return;
    const url = window.prompt('Enter URL:');
    if (url) {
      editor.chain().focus().extendMarkRange('link').setLink({ href: url }).run();
    }
  }, [editor]);

  if (!editor) return null;

  return (
    <div
      className={cn(
        'flex flex-col rounded-lg border border-gray-200 bg-white shadow-sm',
        className,
      )}
    >
      {/* Title */}
      <div className="px-6 pt-6">
        <input
          type="text"
          value={docTitle}
          onChange={handleTitleChange}
          placeholder="Untitled Document"
          disabled={readOnly}
          className={cn(
            'w-full border-none text-3xl font-bold text-gray-900 outline-none',
            'placeholder:text-gray-300',
            readOnly && 'cursor-default bg-transparent',
          )}
        />
      </div>

      {/* Toolbar */}
      {!readOnly && (
        <div className="sticky top-0 z-10 flex flex-wrap items-center gap-0.5 border-b border-gray-200 bg-white px-4 py-1.5">
          {/* Text formatting */}
          <ToolbarButton
            active={editor.isActive('bold')}
            onClick={() => editor.chain().focus().toggleBold().run()}
            title="Bold (Ctrl+B)"
          >
            <span className="text-xs font-bold">B</span>
          </ToolbarButton>
          <ToolbarButton
            active={editor.isActive('italic')}
            onClick={() => editor.chain().focus().toggleItalic().run()}
            title="Italic (Ctrl+I)"
          >
            <span className="text-xs italic">I</span>
          </ToolbarButton>
          <ToolbarButton
            active={editor.isActive('strike')}
            onClick={() => editor.chain().focus().toggleStrike().run()}
            title="Strikethrough"
          >
            <span className="text-xs line-through">S</span>
          </ToolbarButton>
          <ToolbarButton
            active={editor.isActive('code')}
            onClick={() => editor.chain().focus().toggleCode().run()}
            title="Inline Code"
          >
            <span className="font-mono text-[10px]">&lt;/&gt;</span>
          </ToolbarButton>

          <div className="mx-1 h-5 w-px bg-gray-300" />

          {/* Headings */}
          <ToolbarButton
            active={editor.isActive('heading', { level: 1 })}
            onClick={() => editor.chain().focus().toggleHeading({ level: 1 }).run()}
            title="Heading 1"
          >
            <span className="text-xs font-bold">H1</span>
          </ToolbarButton>
          <ToolbarButton
            active={editor.isActive('heading', { level: 2 })}
            onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()}
            title="Heading 2"
          >
            <span className="text-xs font-bold">H2</span>
          </ToolbarButton>
          <ToolbarButton
            active={editor.isActive('heading', { level: 3 })}
            onClick={() => editor.chain().focus().toggleHeading({ level: 3 }).run()}
            title="Heading 3"
          >
            <span className="text-xs font-bold">H3</span>
          </ToolbarButton>

          <div className="mx-1 h-5 w-px bg-gray-300" />

          {/* Lists */}
          <ToolbarButton
            active={editor.isActive('bulletList')}
            onClick={() => editor.chain().focus().toggleBulletList().run()}
            title="Bullet List"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M4 6h16M4 12h16M4 18h16"
              />
            </svg>
          </ToolbarButton>
          <ToolbarButton
            active={editor.isActive('orderedList')}
            onClick={() => editor.chain().focus().toggleOrderedList().run()}
            title="Numbered List"
          >
            <span className="font-mono text-[10px]">1.</span>
          </ToolbarButton>

          <div className="mx-1 h-5 w-px bg-gray-300" />

          {/* Block elements */}
          <ToolbarButton
            active={editor.isActive('blockquote')}
            onClick={() => editor.chain().focus().toggleBlockquote().run()}
            title="Blockquote"
          >
            <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z"
              />
            </svg>
          </ToolbarButton>
          <ToolbarButton
            active={editor.isActive('codeBlock')}
            onClick={() => editor.chain().focus().toggleCodeBlock().run()}
            title="Code Block"
          >
            <span className="font-mono text-[10px]">{'{}'}</span>
          </ToolbarButton>
          <ToolbarButton
            onClick={() => editor.chain().focus().setHorizontalRule().run()}
            title="Horizontal Rule"
          >
            <span className="text-xs">---</span>
          </ToolbarButton>

          <div className="mx-1 h-5 w-px bg-gray-300" />

          {/* Link */}
          <ToolbarButton active={editor.isActive('link')} onClick={addLink} title="Add Link">
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1"
              />
            </svg>
          </ToolbarButton>

          {/* Undo/Redo */}
          <div className="mx-1 h-5 w-px bg-gray-300" />
          <ToolbarButton
            onClick={() => editor.chain().focus().undo().run()}
            disabled={!editor.can().undo()}
            title="Undo (Ctrl+Z)"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M3 10h10a4 4 0 014 4v2M3 10l6 6m-6-6l6-6"
              />
            </svg>
          </ToolbarButton>
          <ToolbarButton
            onClick={() => editor.chain().focus().redo().run()}
            disabled={!editor.can().redo()}
            title="Redo (Ctrl+Shift+Z)"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M21 10h-10a4 4 0 00-4 4v2M21 10l-6 6m6-6l-6-6"
              />
            </svg>
          </ToolbarButton>

          {/* Save */}
          <div className="flex-1" />
          {onSave && (
            <div className="flex items-center gap-2">
              {lastSaved && (
                <span className="text-xs text-gray-400">
                  Saved {lastSaved.toLocaleTimeString()}
                </span>
              )}
              <button
                type="button"
                onClick={handleSave}
                disabled={saving}
                className={cn(
                  'rounded-md px-3 py-1 text-xs font-medium',
                  'bg-blue-500 text-white hover:bg-blue-600',
                  'disabled:cursor-not-allowed disabled:opacity-50',
                  'transition-colors',
                )}
              >
                {saving ? 'Saving...' : 'Save'}
              </button>
            </div>
          )}
        </div>
      )}

      {/* Editor content */}
      <div className="flex-1 px-6 py-4">
        <EditorContent
          editor={editor}
          className={cn(
            'prose prose-sm min-h-[300px] max-w-none',
            'prose-headings:text-gray-900',
            'prose-p:text-gray-700 prose-p:leading-relaxed',
            'prose-a:text-blue-600 prose-a:no-underline hover:prose-a:underline',
            'prose-code:text-pink-600 prose-code:bg-pink-50 prose-code:px-1 prose-code:rounded',
            'prose-blockquote:border-l-blue-500 prose-blockquote:text-gray-600',
            'prose-li:text-gray-700',
            '[&_.ProseMirror]:min-h-[300px] [&_.ProseMirror]:outline-none',
            '[&_.ProseMirror_p.is-editor-empty:first-child]:before:content-[attr(data-placeholder)]',
            '[&_.ProseMirror_p.is-editor-empty:first-child]:before:text-gray-400',
            '[&_.ProseMirror_p.is-editor-empty:first-child]:before:float-left',
            '[&_.ProseMirror_p.is-editor-empty:first-child]:before:h-0',
            '[&_.ProseMirror_p.is-editor-empty:first-child]:before:pointer-events-none',
          )}
        />
      </div>

      {/* Footer with word count */}
      <div className="flex items-center justify-between border-t border-gray-100 px-6 py-2 text-xs text-gray-400">
        <span>
          {editor.storage.characterCount?.characters?.() ?? editor.getText().length} characters
        </span>
        <span>{readOnly ? 'Read only' : 'Editing'}</span>
      </div>
    </div>
  );
};

export default DocumentPage;
