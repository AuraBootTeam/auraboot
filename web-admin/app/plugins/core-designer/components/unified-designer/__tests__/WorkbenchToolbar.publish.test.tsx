import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { WorkbenchToolbar } from '../workbench/WorkbenchToolbar';
import { samplePageSchemaV3 } from '../fixtures/samplePageSchemaV3';

it('does not offer a second publish for an already published page and retains unpublish', () => {
  const publish = vi.fn(); const unpublish = vi.fn();
  render(<WorkbenchToolbar document={samplePageSchemaV3} mode="edit" isDirty={false} saveStatus="saved"
    validationErrorCount={0} canUndo={false} canRedo={false} pageId="page-one" publishStatus="published"
    onModeChange={vi.fn()} onUndo={vi.fn()} onRedo={vi.fn()} onSave={vi.fn()} onPublish={publish} onUnpublish={unpublish} />);
  expect(screen.getByTestId('designer-publish')).toBeDisabled();
  fireEvent.click(screen.getByTestId('designer-publish')); expect(publish).not.toHaveBeenCalled();
  fireEvent.click(screen.getByTestId('designer-unpublish')); expect(unpublish).toHaveBeenCalledOnce();
});

it('denies unpublish to a reader even when the saved page is published', () => {
  const unpublish = vi.fn();
  render(<WorkbenchToolbar document={samplePageSchemaV3} mode="edit" isDirty={false} saveStatus="saved"
    validationErrorCount={0} canUndo={false} canRedo={false} pageId="page-one" publishStatus="published" canPublish={false}
    onModeChange={vi.fn()} onUndo={vi.fn()} onRedo={vi.fn()} onSave={vi.fn()} onPublish={vi.fn()} onUnpublish={unpublish} />);
  expect(screen.getByTestId('designer-unpublish')).toBeDisabled();
  fireEvent.click(screen.getByTestId('designer-unpublish'));
  expect(unpublish).not.toHaveBeenCalled();
});
