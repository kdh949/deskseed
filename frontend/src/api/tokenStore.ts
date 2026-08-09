const keyFor = (ticketNumber: number) => `deskseed.request-token.${ticketNumber}`

export function saveRequestToken(ticketNumber: number, token: string): void {
  window.localStorage.setItem(keyFor(ticketNumber), token)
}

export function loadRequestToken(ticketNumber: number): string | null {
  return window.localStorage.getItem(keyFor(ticketNumber))
}

export function removeRequestToken(ticketNumber: number): void {
  window.localStorage.removeItem(keyFor(ticketNumber))
}
