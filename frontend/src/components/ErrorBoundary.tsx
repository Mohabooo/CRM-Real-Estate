import { Component, type ErrorInfo, type ReactNode } from 'react';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import Button from '@mui/material/Button';
import Box from '@mui/material/Box';
import { isApiError } from '@/api/errors';

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

/**
 * Catches render-time failures so one broken component does not blank the whole application.
 *
 * Shows the correlation id when the failure came from an API call, because that is the one
 * piece of information that turns "it broke" into a searchable log entry.
 */
export class ErrorBoundary extends Component<Props, State> {
  override state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    // Replaced by a real reporting sink when one is chosen; production telemetry is not a
    // decided concern yet and inventing one here would be premature.
    console.error('Unhandled render error', error, info.componentStack);
  }

  private readonly handleReset = () => {
    this.setState({ error: null });
  };

  override render(): ReactNode {
    const { error } = this.state;
    if (!error) {
      return this.props.children;
    }

    const correlationId = isApiError(error) ? error.correlationId : undefined;

    return (
      <Box sx={{ p: 3, maxWidth: 720, mx: 'auto' }}>
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={this.handleReset}>
              Try again
            </Button>
          }
        >
          <AlertTitle>Something went wrong</AlertTitle>
          {error.message}
          {correlationId ? (
            <Box component="p" sx={{ mt: 1, mb: 0, fontFamily: 'monospace', fontSize: '0.8rem' }}>
              Reference: {correlationId}
            </Box>
          ) : null}
        </Alert>
      </Box>
    );
  }
}
