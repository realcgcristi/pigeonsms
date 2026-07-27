import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@': path.resolve(process.cwd(), 'src') } },
  build: { target: 'es2022', sourcemap: false, chunkSizeWarningLimit: 2000 },
  server: { port: 5183 },
});
