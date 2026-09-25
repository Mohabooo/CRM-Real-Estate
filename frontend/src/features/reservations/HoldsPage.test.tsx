import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import '@/i18n';
import { AppProviders } from '@/app/AppProviders';
import type { Me, Tenant } from '@/api/auth';
import type { Page, Unit } from '@/api/inventory';
import type { Reservation, ReservationStatus } from '@/api/reservations';
import { HoldsPage } from './HoldsPage';

vi.mock('@/api/reservations', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/reservations')>()),
  reservationsApi: {
    list: vi.fn(),
    place: vi.fn(),
    confirm: vi.fn(),
    release: vi.fn(),
    cancel: vi.fn(),
    extend: vi.fn(),
  },
}));
vi.mock('@/api/inventory', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/inventory')>()),
  inventoryApi: { projects: vi.fn(), units: vi.fn(), unit: vi.fn() },
}));
vi.mock('@/api/auth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/auth')>()),
  authApi: { login: vi.fn(), logout: vi.fn(), me: vi.fn(), currentTenant: vi.fn() },
}));

const { reservationsApi } = await import('@/api/reservations');
const { inventoryApi } = await import('@/api/inventory');
const { authApi } = await import('@/api/auth');

const TENANT = { id: 't1', name: 'Demo', slug: 'demo' } as Tenant;

const ME: Me = {
  userId: 'u1', tenantId: 't1', role: 'OPERATIONS', branchId: null,
  mayAdministerIdentity: false, branchScope: 'TENANT_WIDE',
};

const UNIT: Unit = {
  id: 'unit-1', projectId: 'p1', phaseId: null, code: 'A-101', type: 'Apartment',
  areaSqm: 140, floor: '1', view: 'Garden', listPrice: '3000000.00',
  status: 'available', blockedReason: null,
};

function hold(status: ReservationStatus): Reservation {
  return {
    id: 'r1', unitId: 'unit-1', leadId: null, customerId: 'c1', agentUserId: 'u1',
    branchId: null, reservedAt: '2026-09-01T10:00:00Z',
    expiresAt: '2099-09-08T10:00:00Z', originalExpiresAt: '2099-09-08T10:00:00Z',
    extensionCount: 0, depositAmount: null, depositReceived: false,
    depositMeaning: 'TENANT_CASH', status, closedReason: null, closedAt: null,
  };
}

function page<T>(items: T[]): Page<T> {
  return { items, page: 0, size: 50, totalElements: items.length, totalPages: 1 };
}

function renderHolds() {
  return render(
    <AppProviders>
      <MemoryRouter future={{ v7_relativeSplatPath: true, v7_startTransition: true }}>
        <HoldsPage />
      </MemoryRouter>
    </AppProviders>,
  );
}

describe('HoldsPage', () => {
  beforeEach(() => {
    vi.mocked(authApi.me).mockResolvedValue(ME);
    vi.mocked(authApi.currentTenant).mockResolvedValue(TENANT);
    vi.mocked(inventoryApi.units).mockResolvedValue(page([UNIT]));
    vi.mocked(reservationsApi.list).mockResolvedValue(page([hold('pending')]));
  });

  it('offers cancel and not release on a pending hold', async () => {
    renderHolds();

    // Doc 18 section 3 has no pending -> released row. Offering Release here produced
    // "a reservation cannot move from pending to released" from a button that could never
    // have worked, and left a pending hold with no way out of the interface at all.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Cancel hold' })).toBeInTheDocument(),
    );
    expect(screen.queryByRole('button', { name: 'Release' })).not.toBeInTheDocument();
  });

  it('offers release and not cancel on a confirmed hold', async () => {
    vi.mocked(reservationsApi.list).mockResolvedValue(page([hold('confirmed')]));
    renderHolds();

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Release' })).toBeInTheDocument(),
    );
    expect(screen.queryByRole('button', { name: 'Cancel hold' })).not.toBeInTheDocument();
  });

  it('cancels a pending hold without demanding a reason', async () => {
    const user = userEvent.setup();
    vi.mocked(reservationsApi.cancel).mockResolvedValue(hold('cancelled'));
    renderHolds();

    await user.click(await screen.findByRole('button', { name: 'Cancel hold' }));
    const dialog = await screen.findByRole('dialog');

    // The table's precondition column for pending -> cancelled is empty, so the confirm
    // button is live with the reason untouched.
    const confirm = within(dialog).getByRole('button', { name: 'Cancel hold' });
    expect(confirm).toBeEnabled();
    await user.click(confirm);

    await waitFor(() =>
      expect(reservationsApi.cancel).toHaveBeenCalledWith('r1', undefined),
    );
    expect(reservationsApi.release).not.toHaveBeenCalled();
  });

  it('still requires a reason to release a confirmed hold', async () => {
    const user = userEvent.setup();
    vi.mocked(reservationsApi.list).mockResolvedValue(page([hold('confirmed')]));
    vi.mocked(reservationsApi.release).mockResolvedValue(hold('released'));
    renderHolds();

    await user.click(await screen.findByRole('button', { name: 'Release' }));
    const dialog = await screen.findByRole('dialog');

    // The unit goes back on the market, so somebody will ask why.
    expect(within(dialog).getByRole('button', { name: 'Release' })).toBeDisabled();

    await user.type(within(dialog).getByLabelText(/Reason/), 'Customer withdrew');
    await user.click(within(dialog).getByRole('button', { name: 'Release' }));

    await waitFor(() =>
      expect(reservationsApi.release).toHaveBeenCalledWith('r1', 'Customer withdrew'),
    );
  });

  it('shows the server’s reason when a unit was taken before confirmation', async () => {
    const user = userEvent.setup();
    const { ApiError } = await import('@/api/errors');
    vi.mocked(reservationsApi.confirm).mockRejectedValue(
      new ApiError({
        code: 'CONFLICT',
        message:
          'This hold cannot be confirmed: that unit is now sold, and only an available '
          + 'unit can be withheld',
        status: 409,
        correlationId: 'c1',
      }),
    );
    renderHolds();

    await user.click(await screen.findByRole('button', { name: 'Confirm' }));

    // A pending hold withholds nothing, so the unit can be sold out from under it. The
    // agent needs to be told what it became, not merely that it is unavailable.
    expect(await screen.findByText(/that unit is now sold/)).toBeInTheDocument();
  });
});
