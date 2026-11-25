import { defineConfig } from 'vitest/config';

// One bundle serves /kds, /t/{qrToken} and /c. Assets use absolute /assets/ paths so deep links load them;
// the LAN hub serves /assets/* from the same files.
export default defineConfig({
  base: '/',
  esbuild: { jsx: 'automatic', jsxImportSource: 'preact' },
  build: { outDir: 'dist', assetsDir: 'assets', sourcemap: false },
  server: { proxy: { '/api': 'http://localhost:8080' } },
  test: { environment: 'jsdom', include: ['src/**/*.test.ts'] },
});
