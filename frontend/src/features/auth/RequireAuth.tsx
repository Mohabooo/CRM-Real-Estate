import Box from '@mui/material/Box';
import CircularProgress from '@mui/material/CircularProgress';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from './AuthContext';

/**
 * Keeps a route behind a session.
 *
 * `loading` renders a spinner rather than redirecting. The session lives in a cookie this
 * code cannot read, so on every page load there is a moment where the answer is genuinely
 * unknown — and treating that moment as signed-out would bounce a signed-in person to the
 * login form every time they refresh.
 *
 * This is a convenience, not a security boundary. Every endpoint refuses an unauthenticated
 * request on its own; if this component were removed the data would still be unreachable.
 */
export function RequireAuth() {
  const { status } = useAuth();
  const location = useLocation();

  if (status === 'loading') {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (status === 'signedOut') {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  return <Outlet />;
}
