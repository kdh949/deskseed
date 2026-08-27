// For more info, see https://github.com/storybookjs/eslint-plugin-storybook#configuration-flat-config-format
import storybook from 'eslint-plugin-storybook'

import js from '@eslint/js'
import globals from 'globals'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  {
    ignores: [
      'dist',
      'node_modules',
      'storybook-static',
      'apps/*/public/mockServiceWorker.js',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    languageOptions: {
      globals: globals.browser,
    },
    rules: {
      '@typescript-eslint/consistent-type-imports': 'error',
    },
  },
  {
    files: ['apps/customer-portal/src/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: [
                '**/staff-console/**',
                '@deskseed/staff-console',
                '@deskseed/staff-console/**',
              ],
              message:
                '고객 포털은 상담사 앱의 컴포넌트, 토큰 또는 기능을 참조할 수 없습니다.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['apps/staff-console/src/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: [
                '**/customer-portal/**',
                '@deskseed/customer-portal',
                '@deskseed/customer-portal/**',
              ],
              message:
                '상담사 앱은 고객 포털의 컴포넌트, 토큰 또는 기능을 참조할 수 없습니다.',
            },
          ],
        },
      ],
    },
  },
  storybook.configs['flat/recommended'],
)
