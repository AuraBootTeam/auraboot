/**
 * RichTextEditor — TipTap-based rich text editor for form fields.
 *
 * Stores content as HTML string. Renders a toolbar with common formatting
 * options and an editor area. In display/disabled mode, renders HTML read-only.
 */
import React, { forwardRef, useCallback } from 'react';
import { useActionData } from 'react-router';
import clsx from 'clsx';
import { useSmartField } from '~/studio/hooks/runtime/useSmartComponent';
import { useSmartFieldContract } from '~/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/components/ui/field-base';
import { useEditor, EditorContent } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import Link from '@tiptap/extension-link';
import Placeholder from '@tiptap/extension-placeholder';
import { sanitizeHtml } from '~/meta/utils/sanitizeHtml';

interface RichTextEditorProps {
  name: string;
  label?: string | Record<string, string>;
  placeholder?: string | Record<string, string>;
  disabled?: boolean;
  required?: boolean;
  validationRules?: any[];
  context?: any;
  value?: string;
  defaultValue?: string;
  onChange?: (value: string) => void;
  onBlur?: () => void;
  visible?: boolean | string;
  className?: string;
  [key: string]: any;
}

const ToolbarButton: React.FC<{
  active?: boolean;
  onClick: () => void;
  title: string;
  children: React.ReactNode;
}> = ({ active, onClick, title, children }) => (
  <button
    type="button"
    onClick={onClick}
    title={title}
    className={clsx(
      'rounded p-1.5 text-sm transition-colors',
      active ? 'bg-blue-100 text-blue-700' : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900',
    )}
  >
    {children}
  </button>
);

export const RichTextEditor = forwardRef<HTMLDivElement, RichTextEditorProps>(
  (
    {
      name,
      label,
      placeholder,
      disabled = false,
      required = false,
      validationRules = [],
      context,
      value: propValue,
      defaultValue,
      onChange,
      onBlur,
      visible,
      className,
      ...restProps
    },
    ref,
  ) => {
    const st = useSmartText();

    const {
      labelText,
      placeholderText,
      required: requiredValue,
      disabled: disabledValue,
      visible: isVisible,
    } = useSmartFieldContract({
      label,
      placeholder,
      required,
      disabled,
      context,
      visible,
    });

    const field = useSmartField<string>({
      name,
      value: propValue,
      defaultValue,
      required: requiredValue,
      validationRules,
      context,
      onChange,
      onBlur,
    });

    const actionData = useActionData();
    const actionError =
      actionData?.error?.data?.name === name ? actionData?.error?.data?.desc : undefined;
    const error = field.error || actionError;
    const meta = useSmartFieldMeta({ field, externalError: actionError });
    const errorText = meta.meta.error ? st(meta.meta.error) : undefined;

    const handleUpdate = useCallback(
      ({ editor }: { editor: any }) => {
        const html = editor.getHTML();
        // TipTap returns <p></p> for empty content
        const isEmpty = html === '<p></p>' || html === '';
        field.setValue(isEmpty ? '' : html);
      },
      [field],
    );

    const editor = useEditor({
      extensions: [
        StarterKit.configure({
          heading: { levels: [1, 2, 3] },
        }),
        Link.configure({
          openOnClick: false,
          HTMLAttributes: { class: 'text-blue-600 underline' },
        }),
        Placeholder.configure({
          placeholder: placeholderText || '',
        }),
      ],
      content: field.value || '',
      editable: !disabledValue,
      onUpdate: handleUpdate,
      onBlur: () => field.onBlur?.(),
    });

    if (!isVisible) return null;

    // Display mode: render HTML read-only (disabled or readOnly)
    const isReadOnly = Boolean(restProps.readOnly);
    if (disabledValue || isReadOnly) {
      return (
        <FieldBase
          id={name}
          label={labelText}
          required={requiredValue}
          error={meta.showError ? errorText : undefined}
          className={clsx('space-y-1', className)}
        >
          <div
            ref={ref}
            className="prose prose-sm min-h-[80px] max-w-none rounded-md border border-gray-200 bg-gray-50 p-3"
            dangerouslySetInnerHTML={{ __html: sanitizeHtml(field.value || '') }}
          />
        </FieldBase>
      );
    }

    return (
      <FieldBase
        id={name}
        label={labelText}
        required={requiredValue}
        error={meta.showError ? errorText : undefined}
        className={clsx('space-y-1', className)}
      >
        <div
          ref={ref}
          className={clsx(
            'overflow-hidden rounded-md border',
            meta.showError ? 'border-red-300' : 'border-gray-300',
            'focus-within:border-blue-500 focus-within:ring-2 focus-within:ring-blue-500',
          )}
        >
          {/* Toolbar */}
          {editor && (
            <div className="flex flex-wrap items-center gap-0.5 border-b border-gray-200 bg-gray-50 px-2 py-1.5">
              <ToolbarButton
                active={editor.isActive('bold')}
                onClick={() => editor.chain().focus().toggleBold().run()}
                title="Bold"
              >
                <strong>B</strong>
              </ToolbarButton>
              <ToolbarButton
                active={editor.isActive('italic')}
                onClick={() => editor.chain().focus().toggleItalic().run()}
                title="Italic"
              >
                <em>I</em>
              </ToolbarButton>
              <ToolbarButton
                active={editor.isActive('strike')}
                onClick={() => editor.chain().focus().toggleStrike().run()}
                title="Strikethrough"
              >
                <s>S</s>
              </ToolbarButton>

              <div className="mx-1 h-5 w-px bg-gray-300" />

              <ToolbarButton
                active={editor.isActive('heading', { level: 1 })}
                onClick={() => editor.chain().focus().toggleHeading({ level: 1 }).run()}
                title="Heading 1"
              >
                H1
              </ToolbarButton>
              <ToolbarButton
                active={editor.isActive('heading', { level: 2 })}
                onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()}
                title="Heading 2"
              >
                H2
              </ToolbarButton>
              <ToolbarButton
                active={editor.isActive('heading', { level: 3 })}
                onClick={() => editor.chain().focus().toggleHeading({ level: 3 }).run()}
                title="Heading 3"
              >
                H3
              </ToolbarButton>

              <div className="mx-1 h-5 w-px bg-gray-300" />

              <ToolbarButton
                active={editor.isActive('bulletList')}
                onClick={() => editor.chain().focus().toggleBulletList().run()}
                title="Bullet List"
              >
                <svg
                  className="h-4 w-4"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth={2}
                >
                  <line x1="8" y1="6" x2="21" y2="6" />
                  <line x1="8" y1="12" x2="21" y2="12" />
                  <line x1="8" y1="18" x2="21" y2="18" />
                  <circle cx="4" cy="6" r="1.5" fill="currentColor" stroke="none" />
                  <circle cx="4" cy="12" r="1.5" fill="currentColor" stroke="none" />
                  <circle cx="4" cy="18" r="1.5" fill="currentColor" stroke="none" />
                </svg>
              </ToolbarButton>
              <ToolbarButton
                active={editor.isActive('orderedList')}
                onClick={() => editor.chain().focus().toggleOrderedList().run()}
                title="Ordered List"
              >
                <svg
                  className="h-4 w-4"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth={2}
                >
                  <line x1="10" y1="6" x2="21" y2="6" />
                  <line x1="10" y1="12" x2="21" y2="12" />
                  <line x1="10" y1="18" x2="21" y2="18" />
                  <text
                    x="2"
                    y="8"
                    fontSize="8"
                    fill="currentColor"
                    stroke="none"
                    fontFamily="sans-serif"
                  >
                    1
                  </text>
                  <text
                    x="2"
                    y="14"
                    fontSize="8"
                    fill="currentColor"
                    stroke="none"
                    fontFamily="sans-serif"
                  >
                    2
                  </text>
                  <text
                    x="2"
                    y="20"
                    fontSize="8"
                    fill="currentColor"
                    stroke="none"
                    fontFamily="sans-serif"
                  >
                    3
                  </text>
                </svg>
              </ToolbarButton>

              <div className="mx-1 h-5 w-px bg-gray-300" />

              <ToolbarButton
                active={editor.isActive('blockquote')}
                onClick={() => editor.chain().focus().toggleBlockquote().run()}
                title="Blockquote"
              >
                <svg className="h-4 w-4" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M6 17h3l2-4V7H5v6h3zm8 0h3l2-4V7h-6v6h3z" />
                </svg>
              </ToolbarButton>
              <ToolbarButton
                active={editor.isActive('codeBlock')}
                onClick={() => editor.chain().focus().toggleCodeBlock().run()}
                title="Code Block"
              >
                {'</>'}
              </ToolbarButton>

              <div className="mx-1 h-5 w-px bg-gray-300" />

              <ToolbarButton
                active={editor.isActive('link')}
                onClick={() => {
                  if (editor.isActive('link')) {
                    editor.chain().focus().unsetLink().run();
                  } else {
                    const url = window.prompt('URL:');
                    if (url) {
                      editor.chain().focus().setLink({ href: url }).run();
                    }
                  }
                }}
                title="Link"
              >
                <svg
                  className="h-4 w-4"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth={2}
                >
                  <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
                  <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
                </svg>
              </ToolbarButton>
            </div>
          )}

          {/* Editor area */}
          <EditorContent
            editor={editor}
            className="prose prose-sm min-h-[120px] max-w-none p-3 focus:outline-none [&_.ProseMirror]:min-h-[100px] [&_.ProseMirror]:outline-none [&_.ProseMirror_p.is-editor-empty:first-child::before]:pointer-events-none [&_.ProseMirror_p.is-editor-empty:first-child::before]:float-left [&_.ProseMirror_p.is-editor-empty:first-child::before]:h-0 [&_.ProseMirror_p.is-editor-empty:first-child::before]:text-gray-400 [&_.ProseMirror_p.is-editor-empty:first-child::before]:content-[attr(data-placeholder)]"
          />
        </div>
      </FieldBase>
    );
  },
);

RichTextEditor.displayName = 'RichTextEditor';

export default RichTextEditor;
