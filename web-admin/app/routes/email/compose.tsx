/**
 * EmailComposePage — compose and send an email.
 *
 * Features:
 *  - Account selector (when multiple accounts available)
 *  - To / CC / BCC fields
 *  - Subject input
 *  - TipTap rich text body
 *  - Open/click tracking toggle
 *  - Accepts ?threadId query param for replies
 *  - Send → toast + navigate to sent
 */

import { useState, useEffect, useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { useEditor, EditorContent } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import Link from '@tiptap/extension-link';
import Placeholder from '@tiptap/extension-placeholder';
import { PaperAirplaneIcon, XMarkIcon } from '@heroicons/react/24/outline';
import { listAccounts, sendEmail, type EmailAccount } from '~/shared/services/emailService';
import { useToastContext } from '~/contexts/ToastContext';

function ToolbarBtn({
  active,
  onClick,
  title,
  children,
}: {
  active?: boolean;
  onClick: () => void;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      title={title}
      className={`rounded p-1.5 text-sm transition-colors ${
        active ? 'bg-blue-100 text-blue-700' : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900'
      }`}
    >
      {children}
    </button>
  );
}

function parseEmails(raw: string): string[] {
  return raw
    .split(/[,;\s]+/)
    .map((s) => s.trim())
    .filter(Boolean);
}

export default function EmailComposePage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const threadId = searchParams.get('threadId') || undefined;
  const { showSuccessToast, showErrorToast } = useToastContext();

  const [accounts, setAccounts] = useState<EmailAccount[]>([]);
  const [selectedAccountId, setSelectedAccountId] = useState<number | null>(null);
  const [to, setTo] = useState('');
  const [cc, setCc] = useState('');
  const [bcc, setBcc] = useState('');
  const [showCc, setShowCc] = useState(false);
  const [showBcc, setShowBcc] = useState(false);
  const [subject, setSubject] = useState(threadId ? 'Re: ' : '');
  const [trackOpens, setTrackOpens] = useState(true);
  const [trackClicks, setTrackClicks] = useState(true);
  const [sending, setSending] = useState(false);

  const editor = useEditor({
    extensions: [
      StarterKit.configure({ heading: { levels: [1, 2, 3] } }),
      Link.configure({
        openOnClick: false,
        HTMLAttributes: { class: 'text-blue-600 underline' },
      }),
      Placeholder.configure({ placeholder: 'Write your message…' }),
    ],
    content: '',
  });

  // Load accounts
  useEffect(() => {
    listAccounts().then((list) => {
      setAccounts(list.filter((a) => a.status === 'active'));
      if (list.length > 0) setSelectedAccountId(list[0].id);
    });
  }, []);

  const handleSend = useCallback(async () => {
    if (!selectedAccountId) {
      showErrorToast('Please select an email account');
      return;
    }
    const toList = parseEmails(to);
    if (toList.length === 0) {
      showErrorToast('Please enter at least one recipient');
      return;
    }
    if (!subject.trim()) {
      showErrorToast('Subject is required');
      return;
    }

    setSending(true);
    try {
      const body = editor?.getHTML() || '';
      const ok = await sendEmail({
        accountId: selectedAccountId,
        to: toList,
        cc: cc ? parseEmails(cc) : undefined,
        bcc: bcc ? parseEmails(bcc) : undefined,
        subject: subject.trim(),
        body,
        threadId,
        trackOpens,
        trackClicks,
      });
      if (ok) {
        showSuccessToast('Email sent successfully');
        navigate('/email?tab=outbound');
      } else {
        showErrorToast('Failed to send email');
      }
    } catch {
      showErrorToast('Failed to send email');
    } finally {
      setSending(false);
    }
  }, [
    selectedAccountId,
    to,
    cc,
    bcc,
    subject,
    editor,
    threadId,
    trackOpens,
    trackClicks,
    navigate,
    showSuccessToast,
    showErrorToast,
  ]);

  return (
    <div className="mx-auto max-w-3xl px-4 py-6" data-testid="email-compose-page">
      <div className="rounded-xl border border-gray-200 bg-white shadow-sm dark:border-gray-700 dark:bg-gray-800">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-5 py-4 dark:border-gray-700">
          <h1 className="text-lg font-semibold text-gray-900 dark:text-white">
            {threadId ? 'Reply' : 'New Message'}
          </h1>
          <button
            onClick={() => navigate(-1)}
            className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-700"
          >
            <XMarkIcon className="h-5 w-5" />
          </button>
        </div>

        <div className="space-y-4 p-5">
          {/* From account selector */}
          {accounts.length > 1 && (
            <div className="flex items-center gap-3">
              <label className="w-16 flex-shrink-0 text-sm font-medium text-gray-500 dark:text-gray-400">
                From
              </label>
              <select
                value={selectedAccountId ?? ''}
                onChange={(e) => setSelectedAccountId(Number(e.target.value))}
                data-testid="from-account-select"
                className="flex-1 rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-900 focus:border-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-800 dark:text-white"
              >
                {accounts.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.displayName ? `${a.displayName} <${a.emailAddress}>` : a.emailAddress}
                  </option>
                ))}
              </select>
            </div>
          )}

          {/* To */}
          <div className="flex items-center gap-3">
            <label className="w-16 flex-shrink-0 text-sm font-medium text-gray-500 dark:text-gray-400">
              To
            </label>
            <div className="flex flex-1 items-center gap-2">
              <input
                type="text"
                value={to}
                onChange={(e) => setTo(e.target.value)}
                placeholder="recipient@example.com"
                data-testid="compose-to"
                className="flex-1 rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 focus:border-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-800 dark:text-white dark:placeholder-gray-500"
              />
              <div className="flex gap-1">
                {!showCc && (
                  <button
                    type="button"
                    onClick={() => setShowCc(true)}
                    className="text-xs text-blue-500 hover:text-blue-700"
                  >
                    CC
                  </button>
                )}
                {!showBcc && (
                  <button
                    type="button"
                    onClick={() => setShowBcc(true)}
                    className="text-xs text-blue-500 hover:text-blue-700"
                  >
                    BCC
                  </button>
                )}
              </div>
            </div>
          </div>

          {/* CC */}
          {showCc && (
            <div className="flex items-center gap-3">
              <label className="w-16 flex-shrink-0 text-sm font-medium text-gray-500 dark:text-gray-400">
                CC
              </label>
              <input
                type="text"
                value={cc}
                onChange={(e) => setCc(e.target.value)}
                placeholder="cc@example.com"
                data-testid="compose-cc"
                className="flex-1 rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 focus:border-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-800 dark:text-white dark:placeholder-gray-500"
              />
            </div>
          )}

          {/* BCC */}
          {showBcc && (
            <div className="flex items-center gap-3">
              <label className="w-16 flex-shrink-0 text-sm font-medium text-gray-500 dark:text-gray-400">
                BCC
              </label>
              <input
                type="text"
                value={bcc}
                onChange={(e) => setBcc(e.target.value)}
                placeholder="bcc@example.com"
                data-testid="compose-bcc"
                className="flex-1 rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 focus:border-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-800 dark:text-white dark:placeholder-gray-500"
              />
            </div>
          )}

          {/* Subject */}
          <div className="flex items-center gap-3">
            <label className="w-16 flex-shrink-0 text-sm font-medium text-gray-500 dark:text-gray-400">
              Subject
            </label>
            <input
              type="text"
              value={subject}
              onChange={(e) => setSubject(e.target.value)}
              placeholder="Email subject"
              data-testid="compose-subject"
              className="flex-1 rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 focus:border-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-800 dark:text-white dark:placeholder-gray-500"
            />
          </div>

          {/* Rich text editor */}
          <div className="rounded-lg border border-gray-200 dark:border-gray-600">
            {/* Toolbar */}
            {editor && (
              <div className="flex items-center gap-0.5 border-b border-gray-200 px-2 py-1 dark:border-gray-600">
                <ToolbarBtn
                  active={editor.isActive('bold')}
                  onClick={() => editor.chain().focus().toggleBold().run()}
                  title="Bold"
                >
                  <strong>B</strong>
                </ToolbarBtn>
                <ToolbarBtn
                  active={editor.isActive('italic')}
                  onClick={() => editor.chain().focus().toggleItalic().run()}
                  title="Italic"
                >
                  <em>I</em>
                </ToolbarBtn>
                <ToolbarBtn
                  active={editor.isActive('strike')}
                  onClick={() => editor.chain().focus().toggleStrike().run()}
                  title="Strikethrough"
                >
                  <s>S</s>
                </ToolbarBtn>
                <div className="mx-1 h-4 w-px bg-gray-200 dark:bg-gray-600" />
                <ToolbarBtn
                  active={editor.isActive('bulletList')}
                  onClick={() => editor.chain().focus().toggleBulletList().run()}
                  title="Bullet list"
                >
                  ≡
                </ToolbarBtn>
                <ToolbarBtn
                  active={editor.isActive('orderedList')}
                  onClick={() => editor.chain().focus().toggleOrderedList().run()}
                  title="Ordered list"
                >
                  1.
                </ToolbarBtn>
                <div className="mx-1 h-4 w-px bg-gray-200 dark:bg-gray-600" />
                <ToolbarBtn
                  active={editor.isActive('blockquote')}
                  onClick={() => editor.chain().focus().toggleBlockquote().run()}
                  title="Quote"
                >
                  &ldquo;
                </ToolbarBtn>
              </div>
            )}
            <EditorContent
              editor={editor}
              data-testid="compose-body"
              className="min-h-[200px] px-4 py-3 text-sm text-gray-900 focus:outline-none dark:text-white [&_.ProseMirror]:min-h-[180px] [&_.ProseMirror]:outline-none [&_.ProseMirror_p.is-editor-empty:first-child::before]:pointer-events-none [&_.ProseMirror_p.is-editor-empty:first-child::before]:float-left [&_.ProseMirror_p.is-editor-empty:first-child::before]:text-gray-400 [&_.ProseMirror_p.is-editor-empty:first-child::before]:content-[attr(data-placeholder)]"
            />
          </div>

          {/* Tracking options */}
          <div className="flex items-center gap-6 text-sm">
            <label className="flex cursor-pointer items-center gap-2 text-gray-600 dark:text-gray-300">
              <input
                type="checkbox"
                checked={trackOpens}
                onChange={(e) => setTrackOpens(e.target.checked)}
                data-testid="track-opens-toggle"
                className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
              />
              Track opens
            </label>
            <label className="flex cursor-pointer items-center gap-2 text-gray-600 dark:text-gray-300">
              <input
                type="checkbox"
                checked={trackClicks}
                onChange={(e) => setTrackClicks(e.target.checked)}
                data-testid="track-clicks-toggle"
                className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
              />
              Track clicks
            </label>
          </div>

          {/* Send button */}
          <div className="flex justify-end">
            <button
              onClick={handleSend}
              disabled={sending}
              data-testid="send-email-btn"
              className="flex items-center gap-2 rounded-lg bg-blue-600 px-5 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {sending ? (
                <div className="h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" />
              ) : (
                <PaperAirplaneIcon className="h-4 w-4" />
              )}
              {sending ? 'Sending…' : 'Send'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
