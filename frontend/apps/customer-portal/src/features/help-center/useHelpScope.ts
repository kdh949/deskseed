import { useOptionalCustomerSession } from '../customer-auth/CustomerSessionContext'
export function useHelpScope() {
  const session = useOptionalCustomerSession()
  return [
    'help',
    session?.status ?? 'anonymous',
    session?.customer?.id ?? null,
  ] as const
}
