import { defineConfig } from 'vitest/config';

// One bundle serves /kds, /t/{qrToken} and /c. Assets use absolute /assets/ paths so deep links load them;
// the LAN hub serves /assets/* from the same files.
export default defineConfig({
  base: '/',
  esbuild: { jsx: 'automatic', jsxImportSource: 'preact' },
  build: { outDir: 'dist', assetsDir: 'assets', sourcemap: false },
  // SQUARETOAST_API=https://localhost:8443 for scripts/local-dev/backend.sh (its self-signed cert, hence secure: false).
  server: { proxy: { '/api': { target: process.env.SQUARETOAST_API ?? 'http://localhost:8080', secure: false } } },
  test: { environment: 'jsdom', include: ['src/**/*.test.ts'] },
});
