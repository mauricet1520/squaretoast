import { defineConfig } from 'vitest/config';

// One bundle serves /kds, /t/{qrToken} and /c. Relative asset paths let the LAN hub serve it under /kds/.
export default defineConfig({
  base: './',
  esbuild: { jsx: 'automatic', jsxImportSource: 'preact' },
  build: { outDir: 'dist', assetsDir: 'assets', sourcemap: false },
  server: { proxy: { '/api': 'http://localhost:8080' } },
  test: { environment: 'jsdom', include: ['src/**/*.test.ts'] },
});
