import { createBrowserRouter, Navigate } from 'react-router-dom';
import { AppShell } from '@/components/AppShell';
import { HomePage } from '@/features/home/HomePage';
import { SystemStatusPage } from '@/features/system/SystemStatusPage';

/**
 * Route table.
 *
 * Flat and small by design: the real information architecture follows the role-driven layouts
 * in doc 21 section 4, which need the business modules to exist first. Feature routes will be
 * added per module rather than centralised here indefinitely.
 */
export const router = createBrowserRouter(
  [
    {
      path: '/',
      element: <AppShell />,
      children: [
        { index: true, element: <HomePage /> },
        { path: 'system', element: <SystemStatusPage /> },
        { path: '*', element: <Navigate to="/" replace /> },
      ],
    },
  ],
  {
    // Opt in to the v7 behaviours now rather than inheriting a migration later. Adopting
    // them while the route table is two entries long costs nothing.
    future: {
      v7_relativeSplatPath: true,
      v7_fetcherPersist: true,
      v7_normalizeFormMethod: true,
      v7_partialHydration: true,
      v7_skipActionErrorRevalidation: true,
    },
  },
);
