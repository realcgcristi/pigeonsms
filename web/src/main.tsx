import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { ThemeProvider } from '@/theme/ThemeProvider';
import App from '@/App';
import { registerServiceWorker } from '@/lib/push';
import { initializeDesktopRuntime, isDesktopApp, prepareDesktopRuntime } from '@/desktop/runtime';
import '@/styles/tokens.css';
import '@/styles/base.css';

prepareDesktopRuntime();

const container = document.getElementById('root');

if (container) {
  createRoot(container).render(
    <StrictMode>
      <ThemeProvider>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </ThemeProvider>
    </StrictMode>,
  );
}

if (isDesktopApp()) void initializeDesktopRuntime();
else void registerServiceWorker();
