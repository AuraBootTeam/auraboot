import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ReportDocumentProvider, useReportDocument } from '../../state/ReportDocumentProvider';
import { ReportTemplateActions } from '../ReportTemplateActions';
import { createEmptyReport } from '../../types';
import { serializeReportTemplate } from '../../services/reportTemplateIO';
vi.mock('~/utils/i18n', () => ({ useSmartText: () => (value: { en: string }) => value.en }));
function Harness({ disabled = false }: { disabled?: boolean }) {
  const state = useReportDocument();
  return (
    <>
      <ReportTemplateActions disabled={disabled} />
      <output data-testid="document-title">{state.report?.title}</output>
      <button onClick={state.undo}>Undo import</button>
    </>
  );
}
function upload(content: string) {
  const file = new File([content], 'sample.report-template.json', { type: 'application/json' });
  Object.defineProperty(file, 'text', { value: async () => content });
  fireEvent.change(screen.getByTestId('report-template-import-input'), {
    target: { files: [file] },
  });
}
describe('ReportTemplateActions', () => {
  it('imports through the file action, reports state and restores the original document on undo', async () => {
    render(
      <ReportDocumentProvider>
        <Harness />
      </ReportDocumentProvider>,
    );
    const original = screen.getByTestId('document-title').textContent;
    upload(serializeReportTemplate(createEmptyReport('Customer analysis')));
    await waitFor(() =>
      expect(screen.getByTestId('document-title')).toHaveTextContent('Customer analysis'),
    );
    expect(screen.getByTestId('report-template-imported')).toBeVisible();
    fireEvent.click(screen.getByText('Undo import'));
    expect(screen.getByTestId('document-title').textContent).toBe(original);
  });
  it('leaves the document intact and exposes an inline error on invalid files', async () => {
    render(
      <ReportDocumentProvider>
        <Harness />
      </ReportDocumentProvider>,
    );
    const original = screen.getByTestId('document-title').textContent;
    upload('{"format":"wrong"}');
    expect(await screen.findByRole('alert')).toHaveTextContent('current design was not replaced');
    expect(screen.getByTestId('document-title').textContent).toBe(original);
  });
  it('keeps every template action disabled for read-only or saving hosts', async () => {
    render(
      <ReportDocumentProvider>
        <Harness disabled />
      </ReportDocumentProvider>,
    );
    const original = screen.getByTestId('document-title').textContent;
    expect(screen.getByTestId('report-template-import')).toBeDisabled();
    expect(screen.getByTestId('report-template-export')).toBeDisabled();
    await act(async () => upload(serializeReportTemplate(createEmptyReport('Forbidden'))));
    expect(screen.getByTestId('document-title').textContent).toBe(original);
    expect(screen.queryByTestId('report-template-imported')).not.toBeInTheDocument();
  });
});
