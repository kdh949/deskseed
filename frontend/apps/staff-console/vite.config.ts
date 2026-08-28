import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'
import { realpathSync } from 'node:fs'
import path from 'node:path'
import { storybookTest } from '@storybook/addon-vitest/vitest-plugin'
import { playwright } from '@vitest/browser-playwright'

const frontendRoot = path.resolve(import.meta.dirname, '../..')
const nodeModulesRoot = realpathSync(path.join(frontendRoot, 'node_modules'))

// More info at: https://storybook.js.org/docs/next/writing-tests/integrations/vitest-addon
export default defineConfig({
  root: import.meta.dirname,
  base: '/_staff/',
  build: {
    emptyOutDir: true,
    manifest: true,
    outDir: '../../dist/_staff',
  },
  plugins: [react()],
  server: {
    fs: {
      allow: [frontendRoot, nodeModulesRoot],
    },
    port: 5173,
    proxy: {
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
          exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
        },
      },
      {
        extends: true,
        plugins: [
          // The plugin will run tests for the stories defined in your Storybook config
          // See options at: https://storybook.js.org/docs/next/writing-tests/integrations/vitest-addon#storybooktest
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
            instances: [
              {
                browser: 'chromium',
              },
            ],
          },
        },
      },
    ],
  },
})
