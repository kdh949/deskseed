import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

const field = {
  id: '11111111-1111-4111-8111-111111111111',
  version: 1,
  active: true,
  machineKey: 'order.number',
  type: 'SHORT_TEXT',
  staffLabel: '주문 확인 번호',
  customerLabel: '주문번호',
  customerVisible: true,
  customerEditable: true,
  agentVisible: true,
  agentEditable: true,
  searchable: false,
  analyticsEligible: false,
  sensitive: false,
}
const form = {
  id: '22222222-2222-4222-8222-222222222222',
  version: 3,
  lifecycle: 'PUBLISHED',
  name: '온라인 구매 환불 문의',
  defaultForCustomer: false,
  defaultForAgent: true,
  placements: [
    {
      fieldId: field.id,
      order: 0,
      customer: { visible: true, editable: true, required: false },
      agent: { visible: true, editable: true, required: false },
    },
  ],
  conditionalRules: [],
  allowedCustomStatusIds: [],
}
const tag = {
  id: '33333333-3333-4333-8333-333333333333',
  value: 'refund',
  label: '환불 요청',
  active: true,
  version: 1,
  highCardinalityWarning: false,
}
const status = {
  id: '44444444-4444-4444-8444-444444444444',
  machineKey: 'waiting-customer',
  agentLabel: '고객 확인 대기',
  customerLabel: '추가 정보 필요',
  statusCategory: 'PENDING',
  active: true,
  order: 0,
  defaultForCategory: false,
  allowedFormIds: [],
  description: null,
  version: 1,
}

for (const width of [1280, 1440, 1920]) {
  test(`admin configuration saved preview, filters and draft exits at ${width}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: width === 1920 ? 1080 : 900 })
    const requests: Record<string, unknown>[] = []
    await page.route('**/api/v1/**', async (route) => {
      const req = route.request()
      const path = new URL(req.url()).pathname
      if (path === '/api/v1/agent/me')
        return route.fulfill({
          json: {
            id: field.id,
            email: 'admin@example.test',
            displayName: '설정 관리자',
            role: 'ADMIN',
            capabilities: ['ADMIN_MANAGE'],
          },
        })
      if (path === '/api/v1/agent/csrf')
        return route.fulfill({
          json: { token: 'synthetic-csrf', headerName: 'X-CSRF-TOKEN' },
        })
      if (path === '/api/v1/admin/ticket-fields')
        return route.fulfill({ json: [field] })
      if (path === '/api/v1/admin/ticket-forms')
        return route.fulfill({ json: [form] })
      if (path === '/api/v1/admin/ticket-tags')
        return route.fulfill({ json: [tag] })
      if (path === '/api/v1/admin/ticket-statuses')
        return route.fulfill({ json: [status] })
      if (path === `/api/v1/admin/ticket-forms/${form.id}/preview`) {
        expect(req.method()).toBe('POST')
        expect(req.headers()['x-csrf-token']).toBe('synthetic-csrf')
        const body = req.postDataJSON()
        requests.push(body)
        return route.fulfill({
          json: {
            formId: form.id,
            formVersion: 3,
            fields: [
              {
                field,
                visible: true,
                editable: true,
                required:
                  body.fieldValues['order.number']?.shortTextValue === '123',
              },
              {
                field: {
                  ...field,
                  id: 'staff',
                  machineKey: 'staff.note',
                  customerVisible: false,
                  customerLabel: null,
                  staffLabel: '직원 전용 평가',
                },
                visible: true,
                editable: true,
                required: false,
              },
            ],
          },
        })
      }
      throw new Error(`Unexpected ${req.method()} ${path}`)
    })
    await page.goto(
      `${process.env.PLAYWRIGHT_STAFF_BASE_URL ?? ''}/admin/ticket-forms`,
    )
    await expect(
      page.getByText('발행됨 · 설정 버전 3 · 고객 기본 아님 · 상담사 기본 폼'),
    ).toBeVisible()
    await page.getByLabel('폼 검색').fill('없는 항목')
    await expect(page.getByText('조건에 맞는 폼이 없습니다.')).toBeVisible()
    await page.getByRole('button', { name: '검색 초기화' }).click()
    await page.screenshot({
      path: testInfo.outputPath(`configuration-list-${width}.png`),
      fullPage: true,
    })
    await page
      .getByRole('button', { name: '저장본 미리보기: 온라인 구매 환불 문의' })
      .focus()
    await page.keyboard.press('Enter')
    await expect(
      page.getByRole('heading', {
        name: '온라인 구매 환불 문의 저장본 미리보기',
      }),
    ).toBeFocused()
    await expect(page.getByLabel('주문번호', { exact: true })).toBeVisible()
    await expect(page.getByText('직원 전용 평가')).toHaveCount(0)
    await page.getByLabel('주문번호', { exact: true }).fill('123')
    await page.getByRole('button', { name: '조건 적용' }).click()
    await expect(page.getByLabel('주문번호 (필수)')).toHaveValue('123')
    expect(requests.at(-1)).toMatchObject({
      actorKind: 'CUSTOMER',
      ticketKind: 'CUSTOMER_REQUEST',
      statusCategory: 'NEW',
      fieldValues: { 'order.number': { shortTextValue: '123' } },
    })
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
    await page.screenshot({
      path: testInfo.outputPath(`configuration-preview-${width}.png`),
      fullPage: true,
    })
    await page.getByLabel('사용자 관점').selectOption('AGENT')
    await expect(page.getByLabel('직원 전용 평가')).toBeVisible()
    await expect(page.getByLabel('주문 확인 번호')).toHaveValue('')
    await page.getByRole('button', { name: '목록으로 돌아가기' }).click()
    await expect(
      page.getByRole('button', {
        name: '저장본 미리보기: 온라인 구매 환불 문의',
      }),
    ).toBeFocused()
    await page
      .getByRole('button', { name: '편집: 온라인 구매 환불 문의' })
      .click()
    await page
      .getByRole('textbox', { name: '폼 이름', exact: true })
      .fill('보존할 초안')
    await page.getByRole('link', { name: '티켓 필드', exact: true }).click()
    await expect(
      page.getByRole('dialog', { name: '작성한 내용을 버릴까요?' }),
    ).toBeVisible()
    await page.getByRole('button', { name: '계속 편집' }).click()
    await expect(
      page.getByRole('textbox', { name: '폼 이름', exact: true }),
    ).toHaveValue('보존할 초안')
    const dialogPromise = page.waitForEvent('dialog')
    void page.reload().catch(() => null)
    const dialog = await dialogPromise
    expect(dialog.type()).toBe('beforeunload')
    await dialog.dismiss()
    await expect(
      page.getByRole('textbox', { name: '폼 이름', exact: true }),
    ).toHaveValue('보존할 초안')
    await page.getByRole('link', { name: '티켓 필드', exact: true }).click()
    await page.getByRole('button', { name: '변경 버리기' }).click()
    await expect(
      page.getByRole('heading', { name: '티켓 필드', exact: true }),
    ).toBeVisible()
    await page.getByRole('button', { name: '편집: 주문 확인 번호' }).click()
    await expect(
      page.getByRole('heading', { name: '주문 확인 번호 편집' }),
    ).toBeFocused()
    await page.getByRole('button', { name: '편집 닫기' }).click()
    await expect(
      page.getByRole('button', { name: '편집: 주문 확인 번호' }),
    ).toBeFocused()
    await page.getByRole('link', { name: '티켓 태그', exact: true }).click()
    await page.getByLabel('태그 검색').fill('refund')
    await page.getByRole('button', { name: '환불 요청 편집' }).click()
    await page.getByLabel('상담사 표시 이름').fill('유지할 태그')
    await page.keyboard.press('Escape')
    await page.getByRole('button', { name: '계속 편집' }).click()
    await expect(page.getByLabel('상담사 표시 이름')).toHaveValue('유지할 태그')
    await page.keyboard.press('Escape')
    await page.getByRole('button', { name: '변경 버리기' }).click()
    await expect(
      page.getByRole('button', { name: '환불 요청 편집' }),
    ).toBeFocused()
    await page.getByRole('link', { name: '업무 상태', exact: true }).click()
    await page.getByLabel('처리 단계 필터').selectOption('OPEN')
    await expect(page.getByText('조건에 맞는 항목이 없습니다.')).toBeVisible()
    await page.getByRole('button', { name: '검색 초기화' }).click()
    await expect(
      page.getByRole('button', { name: '고객 확인 대기 편집' }),
    ).toBeVisible()
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true)
  })
}
