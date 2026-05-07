/** Shared test harness wrapping tree under I18n + Shell providers. */

import { render } from '@testing-library/react';
import type { ReactElement, ReactNode } from 'react';

import { I18nProvider } from '~/contexts/I18nContext';
import { AuraBotShellProvider } from '../AuraBotProvider';

interface ProvidersProps {
  children: ReactNode;
}

function AllProviders({ children }: ProvidersProps): ReactElement {
  return (
    <I18nProvider initialData={{}} initialLocale="en-US">
      <AuraBotShellProvider>{children}</AuraBotShellProvider>
    </I18nProvider>
  );
}

export function renderWithShell(
  ui: ReactElement,
  options?: Parameters<typeof render>[1],
) {
  return render(ui, { wrapper: AllProviders, ...(options ?? {}) });
}

export { AllProviders };
