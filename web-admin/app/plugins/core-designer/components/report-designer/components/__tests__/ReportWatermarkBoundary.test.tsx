import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { I18nProvider } from '~/contexts/I18nContext';
import { ReportWatermarkBlock } from '../../blocks/ReportWatermarkBlock';

afterEach(cleanup);
describe('report watermark containment', () => {
  for (const repeat of [true, false]) {
    it(`keeps the ${repeat ? 'repeating' : 'single'} runtime watermark in its report containing block`, () => {
      const { container } = render(
        <I18nProvider initialLocale="zh-CN" initialData={{ fixture: 'loaded' }}>
          <ReportWatermarkBlock block={{ id: 'w', blockType: 'watermark', text: 'Customer delivery', repeat }} mode="runtime" />
        </I18nProvider>,
      );
      expect(container.firstElementChild).toHaveStyle({ position: 'absolute', width: '100%', height: '100%', pointerEvents: 'none' });
      expect(screen.getAllByText('Customer delivery')).toHaveLength(repeat ? 32 : 1);
    });
    it(`keeps the ${repeat ? 'repeating' : 'single'} design sample in normal layout flow`, () => {
      const { container } = render(
        <I18nProvider initialLocale="zh-CN" initialData={{ fixture: 'loaded' }}>
          <ReportWatermarkBlock block={{ id: 'w', blockType: 'watermark', text: 'Customer delivery', repeat }} mode="design" />
        </I18nProvider>,
      );
      expect(container.firstElementChild).toHaveStyle({ position: 'relative', height: '120px' });
      expect(screen.getAllByText('Customer delivery')).toHaveLength(repeat ? 12 : 1);
    });
  }
});
