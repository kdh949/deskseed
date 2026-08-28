import react from '@vitejs/plugin-react'
import { storybookTest } from '@storybook/addon-vitest/vitest-plugin'
import { playwright } from '@vitest/browser-playwright'
import { realpathSync } from 'node:fs'
import path from 'node:path'
import { defineConfig } from 'vitest/config'

const frontendRoot = path.resolve(import.meta.dirname, '../..')
const nodeModulesRoot = realpathSync(path.join(frontendRoot, 'node_modules'))

export default defineConfig({
  root: import.meta.dirname,
  base: '/_customer/',
  build: {
    emptyOutDir: true,
    manifest: true,
    outDir: '../../dist/_customer',
  },
  plugins: [react()],
  server: {
    fs: {
      allow: [frontendRoot, nodeModulesRoot],
    },
    port: 5174,
    proxy: {
      '/_staff': 'http://127.0.0.1:45174',
      '/agent': 'http://127.0.0.1:45174',
      '/admin': 'http://127.0.0.1:45174',
      '/api': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    },
  },
  test: {
    projects: [
      {
        extends: true,
        test: {
          name: 'unit',
          environment: 'jsdom',
          setupFiles: path.join(import.meta.dirname, 'src/test/setup.ts'),
          exclude: ['node_modules/**', 'dist/**'],
        },
      },
      {
        extends: true,
        plugins: [
          storybookTest({
            configDir: path.join(import.meta.dirname, '.storybook'),
          }),
        ],
        test: {
          name: 'storybook',
          browser: {
            enabled: true,
            headless: true,
            provider: playwright({}),
            instances: [{ browser: 'chromium' }],
          },
        },
      },
    ],
  },
})
