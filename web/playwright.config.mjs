import { defineConfig } from '@playwright/test';
import { microphonePath } from './test/audio-fixture.mjs';
export default defineConfig({
  globalSetup: './test/audio-fixture.mjs',
  testDir: './test',
  testMatch: '**/*.spec.mjs',
  timeout: 60000,
  workers: 1,
  reporter: 'list',
  outputDir: '../review/web-tests',
  use: {
    baseURL: 'http://127.0.0.1:4173/',
    viewport: { width: 960, height: 680 },
    launchOptions: {
      args: [
        '--enable-unsafe-swiftshader',
        '--use-angle=swiftshader',
        '--use-fake-device-for-media-stream',
        '--use-fake-ui-for-media-stream',
        '--use-file-for-fake-audio-capture=' + microphonePath,
      ],
    },
  },
  projects: [
    { name: 'chromium', testIgnore: '**/playback.spec.mjs', use: { browserName: 'chromium' } },
    ...['firefox', 'webkit'].map((browserName) => ({
      name: browserName,
      testMatch: '**/playback.spec.mjs',
      use: { browserName, launchOptions: { args: [] } },
    })),
  ],
  webServer: {
    command: 'node scripts/serve.mjs',
    url: 'http://127.0.0.1:4173/',
    reuseExistingServer: !process.env.CI,
  },
});
