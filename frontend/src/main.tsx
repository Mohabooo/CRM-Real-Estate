import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router-dom';
import '@/i18n';
import { AppProviders } from '@/app/AppProviders';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { router } from '@/app/routes';

const container = document.getElementById('root');
if (!container) {
  throw new Error('Root element #root was not found');
}

createRoot(container).render(
  <StrictMode>
    <ErrorBoundary>
      <AppProviders>
        <RouterProvider router={router} future={{ v7_startTransition: true }} />
      </AppProviders>
    </ErrorBoundary>
  </StrictMode>,
);
