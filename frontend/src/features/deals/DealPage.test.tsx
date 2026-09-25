import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import '@/i18n';
import { AppProviders } from '@/app/AppProviders';
import type { Deal, PaymentPlanTemplate, Schedule } from '@/api/deals';
import type { Unit } from '@/api/inventory';
import { DealPage } from './DealPage';

vi.mock('@/api/deals', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/deals')>()),
  dealsApi: {
    get: vi.fn(),
    offeredTemplates: vi.fn(),
    addDiscount: vi.fn(),
    removeDiscount: vi.fn(),
    approveDiscount: vi.fn(),
    applyTemplate: vi.fn(),
    activate: vi.fn(),
    confirmDownPayment: vi.fn(),
    complete: vi.fn(),
    cancel: vi.fn(),
  },
}));
vi.mock('@/api/inventory', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/inventory')>()),
  inventoryApi: { projects: vi.fn(), units: vi.fn(), unit: vi.fn() },
}));
vi.mock('@/api/auth', () => ({
  authApi: { login: vi.fn(), logout: vi.fn(), me: vi.fn(), currentTenant: vi.fn() },
}));

const { dealsApi } = await import('@/api/deals');
const { inventoryApi } = await import('@/api/inventory');
const { authApi } = await import('@/api/auth');

const UNIT: Unit = {
  id: 'u1', projectId: 'p1', phaseId: null, code: 'A-101', type: 'Apartment',
  areaSqm: 140, floor: '1', view: 'Garden', listPrice: '3000000.00',
  status: 'available', blockedReason: null,
};

/** The doc 17 section 5 worked example, as the API sends it. */
function schedule(overrides: Partial<Schedule> = {}): Schedule {
  return {
    planId: 'pl1', version: 1, status: 'draft', sourceTemplateId: 't1',
    netValue: '2850000.00', downPaymentAmount: '285000.00',
    deliveryPaymentAmount: '142500.00', financedAmount: '2422500.00',
    installmentCount: 32, frequency: 'quarterly', firstDueDate: '2026-04-30',
    total: '2850000.00',
    rows: [
      { id: 'r1', sequenceNo: 1, kind: 'down_payment', dueDate: '2026-01-31', expectedAmount: '285000.00', status: 'pending' },
      { id: 'r2', sequenceNo: 2, kind: 'installment', dueDate: '2026-04-30', expectedAmount: '75703.12', status: 'pending' },
      { id: 'r3', sequenceNo: 33, kind: 'installment', dueDate: '2034-01-31', expectedAmount: '75703.28', status: 'pending' },
      { id: 'r4', sequenceNo: 34, kind: 'delivery', dueDate: '2034-06-30', expectedAmount: '142500.00', status: 'pending' },
    ],
    ...overrides,
  };
}

function deal(overrides: Partial<Deal> = {}): Deal {
  return {
    id: 'd1', unitId: 'u1', primaryCustomerId: 'c1', agentUserId: 'a1', branchId: null,
    sourceReservationId: null, dealDate: '2026-01-31',
    grossValue: '3000000.00', totalDiscount: '150000.00', netValue: '2850000.00',
    currency: 'EGP', commercialModel: 'own_inventory', status: 'draft',
    discountApproved: false, discountApprovedAt: null, approvalRequired: false,
    downPaymentConfirmedAt: null, downPaymentConfirmationApplies: false,
    activatedAt: null, completedAt: null, cancelledAt: null, cancelledReason: null,
    discounts: [
      { id: 'dd1', kind: 'percent', value: '5.0000', amount: '150000.00', reason: 'Launch', createdAt: '2026-01-31T09:00:00Z' },
    ],
    schedule: schedule(),
    ...overrides,
  };
}

const TEMPLATE: PaymentPlanTemplate = {
  id: 't1', projectId: null, name: 'Standard 8-year', shorthandLabel: null,
  downPaymentType: 'percent', downPaymentValue: '10.0000',
  deliveryPaymentPercent: '5.0000', installmentCount: 32, frequency: 'quarterly',
  firstInstallmentOffsetDays: null, active: true, createdAt: '2026-01-01T00:00:00Z',
};

function renderDeal() {
  return render(
    <AppProviders>
      <MemoryRouter
        initialEntries={['/deals/d1']}
        future={{ v7_relativeSplatPath: true, v7_startTransition: true }}
      >
        <Routes>
          <Route path="/deals/:id" element={<DealPage />} />
        </Routes>
      </MemoryRouter>
    </AppProviders>,
  );
}

describe('DealPage', () => {
  beforeEach(() => {
    vi.mocked(authApi.me).mockRejectedValue(new Error('no session'));
    vi.mocked(inventoryApi.unit).mockResolvedValue(UNIT);
    vi.mocked(dealsApi.offeredTemplates).mockResolvedValue([TEMPLATE]);
    vi.mocked(dealsApi.get).mockResolvedValue(deal());
  });

  it('shows the gross, discount and net value the server sent', async () => {
    renderDeal();

    await waitFor(() => expect(screen.getByText('3,000,000.00')).toBeInTheDocument());

    // The discount shows twice — once as the deal's total and once on the concession that
    // produced it — and the net value shows in both the value table and the schedule's
    // decomposition. Asserted as presence rather than uniqueness: saying the same number in
    // two places is the point of the layout.
    expect(screen.getAllByText('−150,000.00').length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText('2,850,000.00').length).toBeGreaterThanOrEqual(2);
  });

  it('renders the schedule total the API reported, without re-adding the rows', async () => {
    // The rows below sum to 578,906.40. The server says 2,850,000.00 — because these four
    // rows are an excerpt of thirty-four. A client that totalled what it could see would
    // contradict the contract, so the assertion is that it shows the server's figure.
    vi.mocked(dealsApi.get).mockResolvedValue(deal());
    renderDeal();

    await waitFor(() => expect(screen.getByText('Total')).toBeInTheDocument());
    const totalRow = screen.getByText('Total').closest('tr');
    expect(totalRow).not.toBeNull();
    expect(within(totalRow as HTMLElement).getByText('2,850,000.00')).toBeInTheDocument();
    expect(screen.queryByText('578,906.40')).not.toBeInTheDocument();
  });

  it('shows a due date as a calendar date, not shifted by a time zone', async () => {
    renderDeal();

    // 30 April, whatever the machine's offset. new Date("2026-04-30") is midnight UTC and
    // renders as the 29th west of Greenwich, which is a payment book a day out.
    await waitFor(() => expect(screen.getByText('Apr 30, 2026')).toBeInTheDocument());
    expect(screen.queryByText('Apr 29, 2026')).not.toBeInTheDocument();
  });

  it('refuses activation until a schedule exists, and says so', async () => {
    vi.mocked(dealsApi.get).mockResolvedValue(deal({ schedule: null }));
    renderDeal();

    await waitFor(() =>
      expect(screen.getByText('Generate a payment schedule before activating.')).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: 'Activate deal' })).toBeDisabled();
  });

  it('refuses activation while an above-threshold discount is unapproved', async () => {
    vi.mocked(dealsApi.get).mockResolvedValue(
      deal({ approvalRequired: true, discountApproved: false }),
    );
    renderDeal();

    await waitFor(() =>
      expect(
        screen.getByText('A manager must approve the discount before activating.'),
      ).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: 'Activate deal' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Approve' })).toBeEnabled();
  });

  it('allows activation once the approval the server asked for is recorded', async () => {
    vi.mocked(dealsApi.get).mockResolvedValue(
      deal({ approvalRequired: true, discountApproved: true, discountApprovedAt: '2026-02-01T10:00:00Z' }),
    );
    renderDeal();

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Activate deal' })).toBeEnabled(),
    );
  });

  it('never asks for approval when the server says the threshold was not crossed', async () => {
    renderDeal();

    await waitFor(() => expect(screen.getByText('Value')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
  });

  it('refuses a discount given as both a percentage and an amount', async () => {
    const user = userEvent.setup();
    renderDeal();

    await waitFor(() => expect(screen.getByLabelText('Percent')).toBeInTheDocument());
    await user.type(screen.getByLabelText('Percent'), '5');

    // The amount field disables itself, so the contradictory request cannot be composed at
    // all rather than being validated away after the fact.
    expect(screen.getByLabelText('Amount (EGP)')).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Add' })).toBeEnabled();
  });

  it('sends a percentage discount as a percentage', async () => {
    const user = userEvent.setup();
    vi.mocked(dealsApi.addDiscount).mockResolvedValue(deal());
    renderDeal();

    await waitFor(() => expect(screen.getByLabelText('Percent')).toBeInTheDocument());
    await user.type(screen.getByLabelText('Percent'), '7.5');
    await user.click(screen.getByRole('button', { name: 'Add' }));

    await waitFor(() =>
      expect(dealsApi.addDiscount).toHaveBeenCalledWith('d1', {
        percent: '7.5',
        reason: undefined,
      }),
    );
  });

  it('applies a template through the server and shows what came back', async () => {
    const user = userEvent.setup();
    vi.mocked(dealsApi.get).mockResolvedValue(deal({ schedule: null }));
    vi.mocked(dealsApi.applyTemplate).mockResolvedValue(deal());
    renderDeal();

    await waitFor(() => expect(screen.getByLabelText('Payment plan')).toBeInTheDocument());
    await user.click(screen.getByLabelText('Payment plan'));
    await user.click(await screen.findByRole('option', { name: /Standard 8-year/ }));
    await user.click(screen.getByRole('button', { name: 'Generate schedule' }));

    await waitFor(() =>
      expect(dealsApi.applyTemplate).toHaveBeenCalledWith('d1', 't1', undefined),
    );

    // The down payment appears as a headline figure and again as the schedule's first row,
    // which is what "the preview is the stored schedule" looks like on screen.
    await waitFor(() => expect(screen.getAllByText('285,000.00').length).toBe(2));
  });

  it('sends the terms an agent states on the deal, and nothing it did not type', async () => {
    const user = userEvent.setup();
    vi.mocked(dealsApi.get).mockResolvedValue(deal({ schedule: null }));
    vi.mocked(dealsApi.applyTemplate).mockResolvedValue(deal());
    renderDeal();

    await waitFor(() => expect(screen.getByLabelText('Payment plan')).toBeInTheDocument());
    await user.click(screen.getByLabelText('Payment plan'));
    await user.click(await screen.findByRole('option', { name: /Standard 8-year/ }));

    await user.click(screen.getByLabelText("This deal's own terms"));
    await user.type(screen.getByLabelText('Installments'), '16');
    await user.click(screen.getByRole('button', { name: 'Generate schedule' }));

    // Only the term that was typed. A blank field means "keep the plan's value", so
    // filling the rest in from the selected plan would turn what the agent left alone
    // into something they stated.
    await waitFor(() =>
      expect(dealsApi.applyTemplate).toHaveBeenCalledWith('d1', 't1', {
        installmentCount: 16,
      }),
    );
  });

  it('lets a deal state every term with no plan behind it', async () => {
    const user = userEvent.setup();
    vi.mocked(dealsApi.get).mockResolvedValue(deal({ schedule: null }));
    vi.mocked(dealsApi.applyTemplate).mockResolvedValue(deal());
    renderDeal();

    await waitFor(() => expect(screen.getByLabelText("This deal's own terms")).toBeInTheDocument());
    await user.click(screen.getByLabelText("This deal's own terms"));

    await user.type(screen.getByLabelText('Down payment (% of net)'), '10');
    await user.type(screen.getByLabelText('Held to delivery (% of net)'), '5');
    await user.type(screen.getByLabelText('Installments'), '32');
    await user.click(screen.getByLabelText('Frequency'));
    await user.click(await screen.findByRole('option', { name: 'quarterly' }));
    await user.click(screen.getByRole('button', { name: 'Generate schedule' }));

    await waitFor(() =>
      expect(dealsApi.applyTemplate).toHaveBeenCalledWith('d1', '', {
        downPaymentPercent: '10',
        deliveryPaymentPercent: '5',
        installmentCount: 32,
        frequency: 'quarterly',
      }),
    );
  });

  it('will not generate from nothing at all', async () => {
    vi.mocked(dealsApi.get).mockResolvedValue(deal({ schedule: null }));
    renderDeal();

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Generate schedule' })).toBeDisabled(),
    );
    expect(dealsApi.applyTemplate).not.toHaveBeenCalled();
  });

  it('refuses to send a malformed figure rather than letting the server reject it', async () => {
    const user = userEvent.setup();
    vi.mocked(dealsApi.get).mockResolvedValue(deal({ schedule: null }));
    renderDeal();

    await waitFor(() => expect(screen.getByLabelText('Payment plan')).toBeInTheDocument());
    await user.click(screen.getByLabelText('Payment plan'));
    await user.click(await screen.findByRole('option', { name: /Standard 8-year/ }));

    await user.click(screen.getByLabelText("This deal's own terms"));
    await user.type(screen.getByLabelText('Installments'), '3.5');

    expect(screen.getByRole('button', { name: 'Generate schedule' })).toBeDisabled();
  });

  it('offers the brokered down-payment confirmation only where it applies', async () => {
    vi.mocked(dealsApi.get).mockResolvedValue(
      deal({
        status: 'active',
        commercialModel: 'brokered_inventory',
        downPaymentConfirmationApplies: true,
        schedule: schedule({ status: 'active' }),
      }),
    );
    renderDeal();

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Record confirmation' })).toBeInTheDocument(),
    );
    expect(
      screen.getByText(
        'This records a confirmation, not a payment. The developer collects under this commercial model.',
      ),
    ).toBeInTheDocument();
  });

  it('hides the down-payment confirmation where the model has none', async () => {
    vi.mocked(dealsApi.get).mockResolvedValue(
      deal({ status: 'active', schedule: schedule({ status: 'active' }) }),
    );
    renderDeal();

    await waitFor(() => expect(screen.getByText('Activation')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'Record confirmation' })).not.toBeInTheDocument();
  });

  it('shows no paid, outstanding or overdue figure anywhere', async () => {
    renderDeal();

    await waitFor(() => expect(screen.getByText('Total')).toBeInTheDocument());
    for (const word of [/outstanding/i, /overdue/i, /\bpaid\b/i, /balance due/i]) {
      expect(screen.queryByText(word)).not.toBeInTheDocument();
    }
  });
});
