export const customerRequestQueryKeys = {
  detail: (customerId: string, ticketNumber: number) =>
    ['customer-request-detail', customerId, ticketNumber] as const,
  detailRoot: ['customer-request-detail'] as const,
  list: (customerId: string) => ['customer-request-list', customerId] as const,
  listRoot: ['customer-request-list'] as const,
}
