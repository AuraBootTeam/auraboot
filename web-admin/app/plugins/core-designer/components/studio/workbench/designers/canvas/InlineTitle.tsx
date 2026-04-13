/**
 * InlineTitle — Editable title and description for canvas pages
 *
 * Renders two borderless, transparent inputs for page title and description.
 * Used at the top of CanvasBody.
 *
 * @since 4.0.0
 */

import React from 'react';

export interface InlineTitleProps {
  title: string;
  description: string;
  onTitleChange: (value: string) => void;
  onDescriptionChange: (value: string) => void;
}

export const InlineTitle: React.FC<InlineTitleProps> = ({
  title,
  description,
  onTitleChange,
  onDescriptionChange,
}) => {
  return (
    <div className="mb-6" data-testid="canvas-inline-title">
      <input
        type="text"
        value={title}
        onChange={(e) => onTitleChange(e.target.value)}
        placeholder="Untitled"
        className="block w-full border-0 bg-transparent p-0 text-2xl font-bold text-gray-900 placeholder-gray-300 outline-none focus:ring-0"
        data-testid="canvas-title-input"
      />
      <input
        type="text"
        value={description}
        onChange={(e) => onDescriptionChange(e.target.value)}
        placeholder="Add description..."
        className="mt-1 block w-full border-0 bg-transparent p-0 text-sm text-gray-500 placeholder-gray-300 outline-none focus:ring-0"
        data-testid="canvas-description-input"
      />
    </div>
  );
};

export default InlineTitle;
