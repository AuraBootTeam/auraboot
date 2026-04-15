/**
 * QuickNoteWidget — Simple textarea for personal notes.
 *
 * Features:
 * - Loads from GET /api/user-notes
 * - Auto-save on blur or after 1s debounce via PUT /api/user-notes
 * - Footer: "Last saved: {time}" or "Saving..." indicator
 * - Placeholder via i18n
 *
 * @since 6.5.0
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { get, put } from '~/shared/services/http-client';
import { useI18n } from '~/contexts/I18nContext';

interface UserNoteResponse {
  content: string | null;
  updatedAt: string | null;
}

interface QuickNoteWidgetProps {
  className?: string;
}

export function QuickNoteWidget({ className = '' }: QuickNoteWidgetProps) {
  const { t } = useI18n();
  const [content, setContent] = useState('');
  const [saving, setSaving] = useState(false);
  const [lastSaved, setLastSaved] = useState<Date | null>(null);
  const [loading, setLoading] = useState(true);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const contentRef = useRef(content);
  contentRef.current = content;

  const saveNote = useCallback(async (text: string) => {
    setSaving(true);
    const result = await put<UserNoteResponse>('/api/user-notes', { content: text });
    if (result.code === '0' && result.data?.updatedAt) {
      setLastSaved(new Date(result.data.updatedAt));
    } else {
      setLastSaved(new Date());
    }
    setSaving(false);
  }, []);

  const debouncedSave = useCallback(
    (text: string) => {
      if (debounceRef.current) {
        clearTimeout(debounceRef.current);
      }
      debounceRef.current = setTimeout(() => {
        saveNote(text);
      }, 1000);
    },
    [saveNote],
  );

  useEffect(() => {
    const load = async () => {
      setLoading(true);
      const result = await get<UserNoteResponse>('/api/user-notes');
      if (result.code === '0' && result.data) {
        setContent(result.data.content ?? '');
        if (result.data.updatedAt) {
          setLastSaved(new Date(result.data.updatedAt));
        }
      }
      setLoading(false);
    };
    load();
  }, []);

  // Cleanup debounce on unmount
  useEffect(() => {
    return () => {
      if (debounceRef.current) {
        clearTimeout(debounceRef.current);
      }
    };
  }, []);

  const handleChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const text = e.target.value;
    setContent(text);
    debouncedSave(text);
  };

  const handleBlur = () => {
    if (debounceRef.current) {
      clearTimeout(debounceRef.current);
      debounceRef.current = null;
    }
    saveNote(contentRef.current);
  };

  const formatLastSaved = () => {
    if (!lastSaved) return '';
    const now = new Date();
    const diff = now.getTime() - lastSaved.getTime();
    if (diff < 60000) return t('workbench.quickNote.justSaved', {}, 'Just saved');
    return `${t('workbench.quickNote.lastSaved', {}, 'Last saved')}: ${lastSaved.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
  };

  return (
    <div className={`flex h-full flex-col ${className}`} data-testid="quick-note-widget">
      {/* Header */}
      <div className="mb-2 flex items-center justify-between">
        <span className="text-sm font-semibold text-gray-900">
          {t('workbench.quickNote.title', {}, 'Quick Note')}
        </span>
        <span className="text-[10px] text-gray-400">
          {'\uD83D\uDCDD'}
        </span>
      </div>

      {/* Textarea */}
      {loading ? (
        <div className="flex flex-1 items-center justify-center">
          <div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
        </div>
      ) : (
        <textarea
          value={content}
          onChange={handleChange}
          onBlur={handleBlur}
          placeholder={t('workbench.quickNote.placeholder', {}, 'Write a quick note...')}
          className="flex-1 resize-none rounded-lg border border-gray-200 bg-gray-50/50 p-3 text-[13px] leading-relaxed text-gray-700 placeholder-gray-400 outline-none transition-colors focus:border-blue-300 focus:bg-white focus:ring-1 focus:ring-blue-200"
          data-testid="quick-note-textarea"
        />
      )}

      {/* Footer */}
      <div className="mt-2 flex items-center justify-end">
        {saving ? (
          <span className="flex items-center gap-1 text-[10px] text-blue-500">
            <span className="h-2 w-2 animate-spin rounded-full border border-blue-500 border-t-transparent" />
            {t('workbench.quickNote.saving', {}, 'Saving...')}
          </span>
        ) : (
          <span className="text-[10px] text-gray-400">{formatLastSaved()}</span>
        )}
      </div>
    </div>
  );
}
