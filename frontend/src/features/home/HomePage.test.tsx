import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import '@/i18n';
import { AppProviders } from '@/app/AppProviders';
import { AppShell } from '@/components/AppShell';
import { HomePage } from './HomePage';

function renderApp(initialPath = '/') {
  return render(
    <AppProviders>
      <MemoryRouter
        initialEntries={[initialPath]}
        future={{ v7_relativeSplatPath: true, v7_startTransition: true }}
      >
        <Routes>
          <Route path="/" element={<AppShell />}>
            <Route index element={<HomePage />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </AppProviders>,
  );
}

describe('HomePage', () => {
  it('states what the foundation contains', () => {
    renderApp();

    expect(screen.getByRole('heading', { name: /platform foundation/i })).toBeInTheDocument();
    expect(screen.getByText(/exact decimal arithmetic/i)).toBeInTheDocument();
  });

  it('states plainly which business modules are not implemented', () => {
    renderApp();

    // A reviewer should not have to guess whether a missing feature is broken or unbuilt.
    expect(screen.getByText(/leads and customers/i)).toBeInTheDocument();
    expect(screen.getByText(/commission engine/i)).toBeInTheDocument();
  });

  it('switches the document to right-to-left when Arabic is selected', async () => {
    const user = userEvent.setup();
    renderApp();

    expect(document.documentElement.getAttribute('dir')).toBe('ltr');

    await user.click(screen.getByRole('button', { name: 'العربية' }));

    expect(document.documentElement.getAttribute('dir')).toBe('rtl');
    expect(document.documentElement.getAttribute('lang')).toBe('ar');
  });
});
