import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import '@/i18n';
import { ApiError } from '@/api/errors';
import { AppProviders } from '@/app/AppProviders';
import { LoginPage } from './LoginPage';

vi.mock('@/api/auth', () => ({
  authApi: {
    login: vi.fn(),
    logout: vi.fn(),
    me: vi.fn(),
    currentTenant: vi.fn(),
  },
}));

const { authApi } = await import('@/api/auth');

function renderLogin() {
  return render(
    <AppProviders>
      <MemoryRouter
        initialEntries={['/login']}
        future={{ v7_relativeSplatPath: true, v7_startTransition: true }}
      >
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/inventory" element={<p>Inventory</p>} />
        </Routes>
      </MemoryRouter>
    </AppProviders>,
  );
}

describe('LoginPage', () => {
  beforeEach(() => {
    vi.mocked(authApi.me).mockRejectedValue(
      new ApiError({ code: 'UNAUTHORIZED', message: 'no session', status: 401 }),
    );
    vi.mocked(authApi.currentTenant).mockResolvedValue({
      id: 't', name: 'Nile Towers', slug: 'nile-towers',
      status: 'active', defaultCommercialModel: 'own_inventory',
    });
  });

  it('asks for a company key, because an address alone does not identify an account', async () => {
    renderLogin();

    // users.email is unique per tenant, not globally (C9). Without the company key the same
    // address in two companies is ambiguous, so the field is not optional decoration.
    expect(await screen.findByLabelText(/company key/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/email address/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/password/i)).toBeInTheDocument();
  });

  it('keeps the button disabled until all three fields are filled', async () => {
    const user = userEvent.setup();
    renderLogin();

    const button = await screen.findByRole('button', { name: /sign in/i });
    expect(button).toBeDisabled();

    await user.type(screen.getByLabelText(/company key/i), 'nile-towers');
    await user.type(screen.getByLabelText(/email address/i), 'owner@example.com');
    expect(button).toBeDisabled();

    await user.type(screen.getByLabelText(/password/i), 'a-long-enough-password');
    expect(button).toBeEnabled();
  });

  it('sends the three fields, trimmed', async () => {
    const user = userEvent.setup();
    vi.mocked(authApi.login).mockResolvedValue({
      userId: 'u', tenantId: 't', role: 'SALES_AGENT', branchId: 'b',
      mayAdministerIdentity: false, branchScope: 'OWN_BRANCH',
    });
    renderLogin();

    await user.type(await screen.findByLabelText(/company key/i), '  nile-towers  ');
    await user.type(screen.getByLabelText(/email address/i), ' owner@example.com ');
    await user.type(screen.getByLabelText(/password/i), 'a-long-enough-password');
    await user.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() =>
      expect(authApi.login).toHaveBeenCalledWith({
        company: 'nile-towers',
        email: 'owner@example.com',
        password: 'a-long-enough-password',
      }),
    );
  });

  it("shows the server's message rather than guessing which field was wrong", async () => {
    const user = userEvent.setup();
    vi.mocked(authApi.login).mockRejectedValue(
      new ApiError({
        code: 'UNAUTHORIZED',
        message: 'Those sign-in details are not correct',
        status: 401,
      }),
    );
    renderLogin();

    await user.type(await screen.findByLabelText(/company key/i), 'nile-towers');
    await user.type(screen.getByLabelText(/email address/i), 'owner@example.com');
    await user.type(screen.getByLabelText(/password/i), 'wrong-password-entirely');
    await user.click(screen.getByRole('button', { name: /sign in/i }));

    // The server answers the same way for a wrong password, an unknown address and an
    // unknown company, on purpose. A form that guessed which one it was would undo that.
    expect(await screen.findByText(/those sign-in details are not correct/i)).toBeInTheDocument();
  });

  it('explains a network failure differently from a refusal', async () => {
    const user = userEvent.setup();
    vi.mocked(authApi.login).mockRejectedValue(
      new ApiError({ code: 'NETWORK_ERROR', message: 'Failed to fetch', status: 0 }),
    );
    renderLogin();

    await user.type(await screen.findByLabelText(/company key/i), 'nile-towers');
    await user.type(screen.getByLabelText(/email address/i), 'owner@example.com');
    await user.type(screen.getByLabelText(/password/i), 'a-long-enough-password');
    await user.click(screen.getByRole('button', { name: /sign in/i }));

    // "Check your connection" and "your details are wrong" call for different actions.
    expect(await screen.findByText(/not reachable/i)).toBeInTheDocument();
  });
});
