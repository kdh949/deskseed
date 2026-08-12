import { defineConfig, devices } from '@playwright/test'

const devServerPort = process.env.PLAYWRIGHT_DEV_SERVER_PORT ?? '45173'
const baseURL =
  process.env.PLAYWRIGHT_BASE_URL ?? `http://127.0.0.1:${devServerPort}`
const browserName = process.env.PLAYWRIGHT_BROWSER ?? 'chromium'
if (!['chromium', 'firefox', 'webkit'].includes(browserName)) {
  throw new Error(`Unsupported PLAYWRIGHT_BROWSER: ${browserName}`)
}
const browserDevice =
  browserName === 'firefox'
    ? devices['Desktop Firefox']
    : browserName === 'webkit'
      ? devices['Desktop Safari']
      : devices['Desktop Chrome']

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  workers: 1,
  reporter: process.env.CI ? 'github' : 'list',
  outputDir: 'test-results',
  snapshotPathTemplate: '{testDir}/__screenshots__/{platform}/{arg}{ext}',
  expect: {
    toHaveScreenshot: {
      animations: 'disabled',
      maxDiffPixelRatio: 0.01,
      threshold: 0.2,
    },
  },
  use: {
    ...devices['Desktop Chrome'],
    baseURL,
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    colorScheme: 'light',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: process.env.PLAYWRIGHT_USE_EXISTING_SERVER
    ? undefined
    : {
        command: `npm run dev -- --host 127.0.0.1 --port ${devServerPort}`,
        url: baseURL,
        reuseExistingServer: !process.env.CI,
      },
  projects: [
    {
      name: browserName,
      ignoreSnapshots: browserName !== 'chromium',
      use: { ...browserDevice },
    },
  ],
})
