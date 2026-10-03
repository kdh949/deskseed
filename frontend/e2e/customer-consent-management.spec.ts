import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import type {
  ConsentPolicy,
  ConsentDraft,
  ConsentVersion,
} from '../apps/staff-console/src/features/admin/customerConsentApi'

const root = '/api/v1/admin/customer-consent-policies'
const timestamp = '2026-10-03T00:00:00Z'
const admin = {
  id: '22222222-2222-4222-8222-222222222222',
  email: 'synthetic-admin@example.test',
  displayName: '합성 관리자',
  role: 'ADMIN',
  capabilities: ['ADMIN_MANAGE'],
}

for (const width of [1280, 1448]) {
  test(`admin policy creation, explicit publication, immutable history and archive at ${width}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 })
    let policy: ConsentPolicy | null = null
    const writes: string[] = []
    await page.route('**/api/v1/**', async (route) => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      if (path === '/api/v1/agent/me') return route.fulfill({ json: admin })
      if (path === '/api/v1/agent/csrf')
        return route.fulfill({
          json: { token: 'synthetic-csrf', headerName: 'X-CSRF-TOKEN' },
        })
      if (path === root && request.method() === 'GET')
        return route.fulfill({
          json: {
            items: policy
              ? [
                  {
                    ...policy,
                    publishedVersion: policy.publishedVersion?.version ?? null,
                    required: policy.draft.required,
                    displayOrder: policy.draft.displayOrder,
                  },
                ]
              : [],
            page: 0,
            size: 20,
            totalPages: policy ? 1 : 0,
            totalCount: policy ? 1 : 0,
          },
        })
      if (request.method() === 'POST' && path.startsWith(root)) {
        expect(request.headers()['x-deskseed-expected-staff-id']).toBe(admin.id)
        expect(request.headers()['x-csrf-token']).toBe('synthetic-csrf')
        writes.push(path)
        if (path === root) {
          expect(request.headers()['if-none-match']).toBe('*')
          const body = request.postDataJSON() as ConsentDraft &
            Pick<ConsentPolicy, 'policyKey' | 'context'>
          policy = {
            id: '11111111-1111-4111-8111-111111111111',
            policyKey: body.policyKey,
            context: body.context,
            lifecycle: 'DRAFT',
            aggregateVersion: 1,
            createdAt: timestamp,
            updatedAt: timestamp,
            draft: {
              title: body.title,
              document: body.document,
              required: body.required,
              displayOrder: body.displayOrder,
              draftVersion: 1,
              updatedAt: timestamp,
            },
            publishedVersion: null,
            versions: [],
          }
          return route.fulfill({ status: 201, json: policy })
        }
        if (policy && path.endsWith('/publish')) {
          expect(request.headers()['if-match']).toBe('"1"')
          const version: ConsentVersion = {
            ...policy.draft,
            policyId: policy.id,
            policyKey: policy.policyKey,
            version: 1,
            plainText: '합성 검증 문서',
            checksumSha256: 'a'.repeat(64),
            effectiveAt: timestamp,
            publishedAt: timestamp,
            publishedByStaffId: admin.id,
            publishedByDisplayName: admin.displayName,
          }
          policy = {
            ...policy,
            aggregateVersion: 2,
            lifecycle: 'PUBLISHED',
            publishedVersion: version,
            versions: [version],
          }
          return route.fulfill({ json: policy })
        }
        if (policy && path.endsWith('/archive')) {
          expect(request.headers()['if-match']).toBe('"2"')
          policy = {
            ...policy,
            aggregateVersion: 3,
            lifecycle: 'ARCHIVED',
            publishedVersion: null,
          }
          return route.fulfill({ json: policy })
        }
      }
      return route.abort()
    })
    await page.goto('/_staff/admin/customer-consent-policies')
    await expect(
      page
        .getByRole('navigation', { name: '관리자 설정 메뉴' })
        .getByRole('link', { name: '고객 동의 정책', exact: true }),
    ).toHaveAttribute('aria-current', 'page')
    await page.getByRole('button', { name: '정책 만들기' }).click()
    await page.getByLabel('정책 키', { exact: false }).fill('synthetic-terms')
    await page.getByLabel('정책 제목').fill('합성 검증 정책')
    await page.getByLabel('항목 1 내용').fill('합성 검증 문서')
    await page.getByLabel('필수 동의', { exact: true }).check()
    await page.screenshot({
      path: testInfo.outputPath('policy-draft.png'),
      fullPage: true,
    })
    await page.getByRole('button', { name: '초안 저장' }).click()
    await expect(page.getByText(/초안을 저장했습니다/)).toBeVisible()
    await page.getByRole('button', { name: '정책 발행', exact: true }).click()
    expect(writes).toHaveLength(1)
    const confirm = page.getByRole('button', { name: '확인 후 발행' })
    await confirm.focus()
    await page.keyboard.press('Enter')
    await expect(page.getByText(/정책을 발행했습니다/)).toBeVisible()
    await page.getByText('버전 1 · 합성 검증 정책 · 현재 적용').click()
    await expect(
      page
        .getByRole('region', { name: '정책 발행 이력' })
        .getByText('합성 검증 문서'),
    ).toBeVisible()
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
    await page.screenshot({
      path: testInfo.outputPath('policy-published.png'),
      fullPage: true,
    })
    await page.getByRole('button', { name: '정책 보관', exact: true }).click()
    expect(writes).toHaveLength(2)
    await page.getByRole('button', { name: '확인 후 보관' }).click()
    await expect(page.getByText(/정책을 보관했습니다/)).toBeVisible()
    await expect(page.getByLabel('정책 제목')).toBeDisabled()
    await expect(
      page
        .getByRole('region', { name: '정책 발행 이력' })
        .getByText('합성 검증 문서'),
    ).toBeVisible()
    expect(writes).toHaveLength(3)
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true)
  })
}

for (const width of [390, 768]) {
  test(`customer can recheck unavailable registration policies at ${width}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    let policyReads = 0
    let accountWrites = 0
    await page.route('**/api/v1/**', async (route) => {
      const path = new URL(route.request().url()).pathname
      if (path === '/api/v1/customer/me')
        return route.fulfill({ status: 401, json: { status: 401 } })
      if (path === '/api/v1/customer/consent-policies') {
        policyReads += 1
        return route.fulfill({
          json: {
            context: 'REGISTRATION',
            policies:
              policyReads === 1
                ? []
                : [
                    {
                      policyKey: 'synthetic-terms',
                      version: 1,
                      title: '합성 검증 정책',
                      required: true,
                      document: {
                        schemaVersion: 1,
                        blocks: [{ type: 'paragraph', text: '합성 검증 문서' }],
                      },
                    },
                  ],
          },
        })
      }
      if (path === '/api/v1/customer/registrations') accountWrites += 1
      return route.abort()
    })
    await page.goto(
      `${process.env.PLAYWRIGHT_CUSTOMER_BASE_URL ?? ''}/customer/register`,
    )
    await expect(page.getByText('가입 약관을 준비하고 있습니다.')).toBeVisible()
    await expect(
      page.getByRole('link', { name: '고객 지원 홈으로' }),
    ).toHaveAttribute('href', '/')
    await page.screenshot({
      path: testInfo.outputPath('policy-unavailable.png'),
      fullPage: true,
    })
    const retry = page.getByRole('button', { name: '약관 다시 확인' })
    await retry.focus()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('checkbox')).not.toBeChecked()
    await expect(
      page.getByRole('button', { name: '계정 만들기' }),
    ).toBeDisabled()
    expect(policyReads).toBe(2)
    expect(accountWrites).toBe(0)
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true)
  })
}
