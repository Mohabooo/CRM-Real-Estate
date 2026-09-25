import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import '@/i18n';
import { AppProviders } from '@/app/AppProviders';
import type { PaymentPlanTemplate } from '@/api/deals';
import type { Me, Tenant } from '@/api/auth';
import type { Page, Project } from '@/api/inventory';
import { TemplatesPage } from './TemplatesPage';

vi.mock('@/api/deals', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/deals')>()),
  dealsApi: {
    templates: vi.fn(),
    createTemplate: vi.fn(),
    updateTemplate: vi.fn(),
    archiveTemplate: vi.fn(),
    restoreTemplate: vi.fn(),
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

const { dealsApi } = await import('@/api/deals');
const { inventoryApi } = await import('@/api/inventory');
const { authApi } = await import('@/api/auth');

const PROJECT: Project = {
  id: 'p1', commercialModel: 'own_inventory', sellerOfRecord: 'TENANT', developerId: null,
  nameAr: null, nameEn: 'Nile Towers', displayName: 'Nile Towers', location: null,
  deliveryDate: null, status: 'active',
};

const TENANT: Tenant = { id: 't1', name: 'Demo', slug: 'demo' } as Tenant;

function me(role: Me['role']): Me {
  return {
    userId: 'u1', tenantId: 't1', role, branchId: null,
    mayAdministerIdentity: false, branchScope: 'TENANT_WIDE',
  };
}

function template(overrides: Partial<PaymentPlanTemplate> = {}): PaymentPlanTemplate {
  return {
    id: 't-1', projectId: null, name: 'Standard 8-year',
    shorthandLabel: '10% down, 8 years quarterly, 5% on delivery',
    downPaymentType: 'percent', downPaymentValue: '10.0000',
    deliveryPaymentPercent: '5.0000', installmentCount: 32, frequency: 'quarterly',
    firstInstallmentOffsetDays: null, active: true, createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function page<T>(items: T[]): Page<T> {
  return { items, page: 0, size: 25, totalElements: items.length, totalPages: 1 };
}

function renderTemplates() {
  return render(
    <AppProviders>
      <MemoryRouter future={{ v7_relativeSplatPath: true, v7_startTransition: true }}>
        <TemplatesPage />
      </MemoryRouter>
    </AppProviders>,
  );
}

describe('TemplatesPage', () => {
  beforeEach(() => {
    vi.mocked(authApi.me).mockResolvedValue(me('OPERATIONS'));
    vi.mocked(authApi.currentTenant).mockResolvedValue(TENANT);
    vi.mocked(inventoryApi.projects).mockResolvedValue(page([PROJECT]));
    vi.mocked(dealsApi.templates).mockResolvedValue([template()]);
  });

  it('describes a template from its own fields, not its free-text label', async () => {
    // The shorthand is text somebody typed and may predate an edit to the numbers under
    // it, so the terms column is built from the fields themselves.
    vi.mocked(dealsApi.templates).mockResolvedValue([
      template({ shorthandLabel: 'stale label nobody updated' }),
    ]);
    renderTemplates();

    await waitFor(() => expect(screen.getByText('Standard 8-year')).toBeInTheDocument());
    expect(screen.getByText(/10.0000% down · 32 × quarterly · 5.0000% on delivery/))
      .toBeInTheDocument();
  });

  it('hides archived templates until asked, then shows them', async () => {
    const user = userEvent.setup();
    vi.mocked(dealsApi.templates).mockResolvedValue([
      template(),
      template({ id: 't-2', name: 'Retired plan', active: false }),
    ]);
    renderTemplates();

    await waitFor(() => expect(screen.getByText('Standard 8-year')).toBeInTheDocument());
    expect(screen.queryByText('Retired plan')).not.toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: 'Show archived' }));
    expect(await screen.findByText('Retired plan')).toBeInTheDocument();
  });

  it('offers archive for a live template and restore for a retired one', async () => {
    const user = userEvent.setup();
    vi.mocked(dealsApi.templates).mockResolvedValue([
      template({ id: 't-2', name: 'Retired plan', active: false }),
    ]);
    vi.mocked(dealsApi.restoreTemplate).mockResolvedValue(template({ id: 't-2' }));
    renderTemplates();

    await user.click(await screen.findByRole('checkbox', { name: 'Show archived' }));
    await user.click(await screen.findByRole('button', { name: 'Restore' }));

    await waitFor(() => expect(dealsApi.restoreTemplate).toHaveBeenCalledWith('t-2'));
    // Archiving is never a delete: TPL-003 keeps a template with live plans behind it.
    expect(screen.queryByRole('button', { name: /delete/i })).not.toBeInTheDocument();
  });

  it('refuses administration to a role that may not administer', async () => {
    vi.mocked(authApi.me).mockResolvedValue(me('SALES_AGENT'));
    renderTemplates();

    await waitFor(() =>
      expect(
        screen.getByText('Only operations and owners can create or change payment plans.'),
      ).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: 'New payment plan' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Edit' })).toBeDisabled();
  });

  it('sends a percentage down payment as a percentage and never both fields', async () => {
    const user = userEvent.setup();
    vi.mocked(dealsApi.createTemplate).mockResolvedValue(template());
    renderTemplates();

    await user.click(await screen.findByRole('button', { name: 'New payment plan' }));
    await user.type(screen.getByLabelText(/^Name/), '5-year monthly');
    await user.type(screen.getByLabelText('Down payment (% of net)'), '15');
    await user.clear(screen.getByLabelText('Held to delivery (% of net)'));
    await user.type(screen.getByLabelText('Held to delivery (% of net)'), '10');
    await user.type(screen.getByLabelText('Installments'), '60');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(dealsApi.createTemplate).toHaveBeenCalledTimes(1));
    const call = vi.mocked(dealsApi.createTemplate).mock.calls[0];
    expect(call).toBeDefined();
    const sent = call![0];

    expect(sent.downPaymentPercent).toBe('15');
    expect(sent.downPaymentAmount).toBeUndefined();
    expect(sent.deliveryPaymentPercent).toBe('10');
    expect(sent.installmentCount).toBe(60);
    // Blank means the documented default of one frequency interval, which is not the same
    // as zero days and must not be sent as one.
    expect(sent.firstInstallmentOffsetDays).toBeUndefined();
  });

  it('switches the down payment to a fixed amount without carrying the other value', async () => {
    const user = userEvent.setup();
    vi.mocked(dealsApi.createTemplate).mockResolvedValue(template());
    renderTemplates();

    await user.click(await screen.findByRole('button', { name: 'New payment plan' }));
    await user.type(screen.getByLabelText('Down payment (% of net)'), '15');
    await user.click(screen.getByRole('button', { name: 'Amount' }));

    // The field clears on switch: a "15" left behind would silently become 15 EGP.
    expect(screen.getByLabelText('Down payment (EGP)')).toHaveValue('');

    await user.type(screen.getByLabelText(/^Name/), 'Cash heavy');
    await user.type(screen.getByLabelText('Down payment (EGP)'), '500000');
    await user.clear(screen.getByLabelText('Held to delivery (% of net)'));
    await user.type(screen.getByLabelText('Held to delivery (% of net)'), '0');
    await user.type(screen.getByLabelText('Installments'), '12');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(dealsApi.createTemplate).toHaveBeenCalledTimes(1));
    const call = vi.mocked(dealsApi.createTemplate).mock.calls[0];
    expect(call).toBeDefined();
    const sent = call![0];
    expect(sent.downPaymentAmount).toBe('500000');
    expect(sent.downPaymentPercent).toBeUndefined();
  });

  it('computes no money at all in the template editor', async () => {
    const user = userEvent.setup();
    renderTemplates();

    await user.click(await screen.findByRole('button', { name: 'New payment plan' }));
    const dialog = await screen.findByRole('dialog');

    // Fill in a complete, realistic plan. If the screen were going to work anything out,
    // this is the point at which it would have everything it needed to try.
    await user.type(within(dialog).getByLabelText(/^Name/), 'Standard');
    await user.type(within(dialog).getByLabelText('Down payment (% of net)'), '10');
    await user.type(within(dialog).getByLabelText('Installments'), '32');

    // A template has no net value to apply itself to, so no figure here could be anything
    // but arithmetic invented against a price nobody has agreed. Asserted as the absence
    // of derived output — a schedule table, or any grouped money — rather than the absence
    // of the word "schedule", which the explanatory copy uses legitimately.
    expect(within(dialog).queryByRole('table')).not.toBeInTheDocument();
    expect(within(dialog).queryByText(/\d{1,3},\d{3}/)).not.toBeInTheDocument();
    expect(within(dialog).queryByText(/EGP\s*[\d,]+\.\d{2}/)).not.toBeInTheDocument();

    expect(
      within(dialog).getByText(
        'A plan has no value of its own. The schedule is generated on a deal, where the net value is known.',
      ),
    ).toBeInTheDocument();
  });

  it('reports a failed load as a fault rather than an empty list', async () => {
    vi.mocked(dealsApi.templates).mockRejectedValue(new Error('boom'));
    renderTemplates();

    await waitFor(() =>
      expect(screen.getByText('Payment plans could not be loaded.')).toBeInTheDocument(),
    );
  });

  it('keeps the existing table shape so nothing else regressed', async () => {
    renderTemplates();

    const header = await screen.findByRole('row', { name: /Name/ });
    expect(within(header).getAllByRole('columnheader')).toHaveLength(5);
  });
});
