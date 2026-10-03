import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

for (const width of [1280, 1440, 1920]) {
  test(`검색 조건 적용과 페이지 이동 ${width}px`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    const searches: Array<{
      query: string
      filters: Record<string, string>
      cursor: string | null
      sort: string
      interactionId: string | undefined
    }> = []
    await page.route('**/api/v1/**', async (route) => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      if (path === '/api/v1/agent/me')
        return route.fulfill({
          json: {
            id: '11111111-1111-4111-8111-111111111111',
            email: 'agent@example.test',
            displayName: '상담사 A',
            role: 'AGENT',
            capabilities: ['AGENT_WORKSPACE'],
          },
        })
      if (path === '/api/v1/agent/csrf')
        return route.fulfill({
          json: { token: 'csrf', headerName: 'X-CSRF-TOKEN' },
        })
      if (path === '/api/v1/agent/assignment-options')
        return route.fulfill({ json: { groups: [] } })
      if (path === '/api/v1/agent/search') {
        const body = request.postDataJSON()
        searches.push({
          ...body,
          interactionId: request.headers()['x-interaction-id'],
        })
        return route.fulfill({
          json: {
            searchEventId: '33333333-3333-4333-8333-333333333333',
            searchInteractionId: '44444444-4444-4444-8444-444444444444',
            items: [
              {
                ticketNumber: 1042,
                subject: '중복 결제 확인',
                status: 'OPEN',
                priority: 'HIGH',
                requester: {
                  id: null,
                  type: 'CUSTOMER',
                  displayName: '김민수',
                },
                group: null,
                assignee: null,
                createdAt: '2026-08-17T02:00:00Z',
                updatedAt: '2026-08-17T03:00:00Z',
                version: 7,
                isChild: false,
                openChildCount: 0,
                sla: null,
              },
            ],
            resultCount: body.cursor
              ? { value: 2, relation: 'EXACT' }
              : { value: 2, relation: 'LOWER_BOUND' },
            sort: body.sort,
            nextCursor: body.cursor ? null : 'opaque-next',
          },
        })
      }
      return route.abort()
    })
    await page.goto('/agent/search')
    await page.getByLabel('티켓 검색어').fill('중복 결제')
    await page.getByLabel('티켓 검색어').press('Enter')
    await expect(page.getByText('결과 2개 이상')).toBeVisible()
    await page.getByLabel('상태 검색 필터').selectOption('OPEN')
    await page
      .getByLabel('정렬 검색 필터')
      .selectOption('score:desc,ticketNumber:desc')
    await expect(
      page.getByText('아직 적용하지 않은 검색 조건이 있습니다'),
    ).toBeVisible()
    await expect(
      page.getByRole('region', { name: '적용된 검색 조건' }),
    ).toContainText('최근 업데이트 순')
    expect(searches).toHaveLength(1)
    await page.screenshot({
      path: testInfo.outputPath(`search-pending-${width}.png`),
      fullPage: true,
    })
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true)

    await page.getByRole('button', { name: '다음 페이지' }).click()
    await expect(page.getByText('전체 결과 2개')).toBeVisible()
    expect(searches[1]).toMatchObject({
      filters: {},
      cursor: 'opaque-next',
      sort: 'updatedAt:desc,ticketNumber:desc',
      interactionId: searches[0].interactionId,
    })
    const apply = page.getByRole('button', { name: '검색 조건 적용' })
    await apply.focus()
    await page.keyboard.press('Enter')
    await expect(
      page.getByRole('region', { name: '적용된 검색 조건' }),
    ).toContainText('상태: 처리 중')
    await expect(
      page.getByText('아직 적용하지 않은 검색 조건이 있습니다'),
    ).toHaveCount(0)
    await expect.poll(() => searches.length).toBe(3)
    expect(searches[2]).toMatchObject({
      filters: { status: 'OPEN' },
      cursor: null,
      sort: 'score:desc,ticketNumber:desc',
    })
    expect(searches[2].interactionId).not.toBe(searches[0].interactionId)
    expect(page.url()).not.toContain('중복')
  })
}
