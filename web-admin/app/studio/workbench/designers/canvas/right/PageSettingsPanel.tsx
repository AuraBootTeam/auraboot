/**
 * PageSettingsPanel — Page-level settings for the composite page
 *
 * Shows: title, page key (readonly), description.
 * Receives page title/description from parent via props and calls onChange.
 *
 * @since 4.0.0
 */

import React from 'react';
import type { CanvasBlock } from '~/studio/domain/canvas/types';

// Section title style (shared visual pattern)
const sectionTitle: React.CSSProperties = {
  fontSize: 10,
  fontWeight: 600,
  letterSpacing: '0.08em',
  textTransform: 'uppercase',
  color: '#9ca3af',
  marginBottom: 8,
  marginTop: 0,
};

const label: React.CSSProperties = {
  display: 'block',
  fontSize: 11,
  color: '#6b7280',
  marginBottom: 3,
};

const inputStyle: React.CSSProperties = {
  width: '100%',
  fontSize: 12,
  color: '#374151',
  border: '1px solid #e5e7eb',
  borderRadius: 4,
  padding: '4px 8px',
  outline: 'none',
  background: '#fff',
  boxSizing: 'border-box',
};

const inputReadonly: React.CSSProperties = {
  ...inputStyle,
  background: '#f9fafb',
  color: '#9ca3af',
  cursor: 'not-allowed',
};

export interface PageSettingsPanelProps {
  title: string;
  pageKey?: string;
  description: string;
  onTitleChange: (title: string) => void;
  onDescriptionChange: (description: string) => void;
}

export const PageSettingsPanel: React.FC<PageSettingsPanelProps> = ({
  title,
  pageKey,
  description,
  onTitleChange,
  onDescriptionChange,
}) => {
  return (
    <div style={{ padding: '12px 0' }} data-testid="page-settings-panel">
      <p style={sectionTitle}>Page Settings</p>

      {/* Title */}
      <div style={{ marginBottom: 12 }}>
        <label style={label}>Title</label>
        <input
          type="text"
          value={title}
          onChange={(e) => onTitleChange(e.target.value)}
          placeholder="Page title..."
          style={inputStyle}
          data-testid="page-settings-title"
        />
      </div>

      {/* Page key (readonly) */}
      {pageKey && (
        <div style={{ marginBottom: 12 }}>
          <label style={label}>Page Key</label>
          <input
            type="text"
            value={pageKey}
            readOnly
            style={inputReadonly}
            data-testid="page-settings-key"
          />
          <span style={{ fontSize: 10, color: '#9ca3af', marginTop: 3, display: 'block' }}>
            Auto-generated, cannot be changed
          </span>
        </div>
      )}

      {/* Description */}
      <div style={{ marginBottom: 12 }}>
        <label style={label}>Description</label>
        <textarea
          value={description}
          onChange={(e) => onDescriptionChange(e.target.value)}
          placeholder="Optional description..."
          rows={3}
          style={{
            ...inputStyle,
            resize: 'vertical',
            fontFamily: 'inherit',
          }}
          data-testid="page-settings-description"
        />
      </div>
    </div>
  );
};

export default PageSettingsPanel;
