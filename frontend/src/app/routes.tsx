import { createBrowserRouter, Navigate } from 'react-router-dom';
import { AppShell } from '@/components/AppShell';
import { LoginPage } from '@/features/auth/LoginPage';
import { RequireAuth } from '@/features/auth/RequireAuth';
import { HomePage } from '@/features/home/HomePage';
import { InventoryPage } from '@/features/inventory/InventoryPage';
import { DealPage } from '@/features/deals/DealPage';
import { DealsPage } from '@/features/deals/DealsPage';
import { TemplatesPage } from '@/features/deals/TemplatesPage';
import { HoldsPage } from '@/features/reservations/HoldsPage';
import { SystemStatusPage } from '@/features/system/SystemStatusPage';

/**
 * Route table.
 *
 * Everything but the login page sits behind {@link RequireAuth}. That is a convenience
 * rather than a boundary — every endpoint refuses an unauthenticated request on its own, so
 * removing the guard would hide the screens' contents, not expose them.
 *
 * The real information architecture still follows doc 21 section 4's role-driven layouts;
 * these are the routes the first vertical slice needs.
 */
export const router = createBrowserRouter(
  [
    {
      path: '/login',
      element: <LoginPage />,
    },
    {
      path: '/',
      element: <RequireAuth />,
      children: [
        {
          element: <AppShell />,
          children: [
            { index: true, element: <Navigate to="/inventory" replace /> },
            { path: 'inventory', element: <InventoryPage /> },
            { path: 'holds', element: <HoldsPage /> },
            { path: 'deals', element: <DealsPage /> },
            { path: 'deals/:id', element: <DealPage /> },
            { path: 'payment-plans', element: <TemplatesPage /> },
            { path: 'about', element: <HomePage /> },
            { path: 'system', element: <SystemStatusPage /> },
            { path: '*', element: <Navigate to="/inventory" replace /> },
          ],
        },
      ],
    },
  ],
  {
    // Opt in to the v7 behaviours now rather than inheriting a migration later.
    future: {
      v7_relativeSplatPath: true,
      v7_fetcherPersist: true,
      v7_normalizeFormMethod: true,
      v7_partialHydration: true,
      v7_skipActionErrorRevalidation: true,
    },
  },
);
