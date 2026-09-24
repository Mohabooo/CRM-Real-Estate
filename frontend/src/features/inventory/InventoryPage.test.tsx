import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import '@/i18n';
import { AppProviders } from '@/app/AppProviders';
import type { Page, Project, Unit } from '@/api/inventory';
import { InventoryPage } from './InventoryPage';

vi.mock('@/api/inventory', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/inventory')>()),
  inventoryApi: { projects: vi.fn(), units: vi.fn() },
}));
vi.mock('@/api/auth', () => ({
  authApi: { login: vi.fn(), logout: vi.fn(), me: vi.fn(), currentTenant: vi.fn() },
}));

const { inventoryApi } = await import('@/api/inventory');
const { authApi } = await import('@/api/auth');

function page<T>(items: T[]): Page<T> {
  return { items, page: 0, size: 25, totalElements: items.length, totalPages: 1 };
}

const PROJECT: Project = {
  id: 'p1', commercialModel: 'own_inventory', sellerOfRecord: 'TENANT',
  developerId: null, nameAr: null, nameEn: 'Nile Towers', displayName: 'Nile Towers',
  location: 'New Cairo', deliveryDate: null, status: 'active',
};

function unit(overrides: Partial<Unit>): Unit {
  return {
    id: 'u1', projectId: 'p1', phaseId: null, code: 'A-101', type: 'Apartment',
    areaSqm: 140, floor: '1', view: 'Garden', listPrice: '2500000.10',
    status: 'available', blockedReason: null,
    ...overrides,
  };
}

function renderInventory() {
  return render(
    <AppProviders>
      <MemoryRouter future={{ v7_relativeSplatPath: true, v7_startTransition: true }}>
        <InventoryPage />
      </MemoryRouter>
    </AppProviders>,
  );
}

describe('InventoryPage', () => {
  beforeEach(() => {
    vi.mocked(authApi.me).mockRejectedValue(new Error('no session'));
    vi.mocked(inventoryApi.projects).mockResolvedValue(page([PROJECT]));
    vi.mocked(inventoryApi.units).mockResolvedValue(page([unit({})]));
  });

  it('shows a price without letting it through a JavaScript number', async () => {
    renderInventory();

    // 2500000.10 is exactly the value a double would render as 2500000.1.
    expect(await screen.findByText('2,500,000.10')).toBeInTheDocument();
  });

  it('asks for the default view until a status is chosen', async () => {
    renderInventory();

    await waitFor(() => expect(inventoryApi.units).toHaveBeenCalled());
    expect(vi.mocked(inventoryApi.units).mock.calls[0]?.[0].status).toBeUndefined();
  });

  it('filters using the same lowercase codes the API returns', async () => {
    const user = userEvent.setup();
    renderInventory();
    await screen.findByText('A-101');

    await user.click(screen.getByLabelText(/^status$/i));
    await user.click(await screen.findByRole('option', { name: /^sold$/i }));

    // The value came from the API's own vocabulary. Sending 'SOLD' back would be a 400,
    // which is what the API did before it learned to accept its own codes.
    await waitFor(() => {
      const calls = vi.mocked(inventoryApi.units).mock.calls;
      expect(calls[calls.length - 1]?.[0].status).toEqual(['sold']);
    });
  });

  it('offers a hold only on a unit that is actually available', async () => {
    vi.mocked(inventoryApi.units).mockResolvedValue(
      page([
        unit({ id: 'u1', code: 'A-101', status: 'available' }),
        unit({ id: 'u2', code: 'A-102', status: 'reserved' }),
        unit({ id: 'u3', code: 'A-103', status: 'sold' }),
      ]),
    );
    renderInventory();

    const available = (await screen.findByText('A-101')).closest('tr')!;
    const held = screen.getByText('A-102').closest('tr')!;
    const sold = screen.getByText('A-103').closest('tr')!;

    expect(within(available).getByRole('button', { name: /place hold/i })).toBeEnabled();
    expect(within(held).getByRole('button', { name: /place hold/i })).toBeDisabled();
    expect(within(sold).getByRole('button', { name: /place hold/i })).toBeDisabled();
  });

  it('says so plainly when the list could not be loaded', async () => {
    vi.mocked(inventoryApi.units).mockRejectedValue(new Error('boom'));
    renderInventory();

    expect(await screen.findByText(/could not be loaded/i)).toBeInTheDocument();
  });
});
