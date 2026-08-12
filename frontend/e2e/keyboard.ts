import type { Page } from '@playwright/test'

export async function pressSequentialTab(
  page: Page,
  browserName: string,
  reverse = false,
) {
  const modifiers = [
    ...(browserName === 'webkit' ? ['Alt'] : []),
    ...(reverse ? ['Shift'] : []),
  ]
  await page.keyboard.press([...modifiers, 'Tab'].join('+'))
}
