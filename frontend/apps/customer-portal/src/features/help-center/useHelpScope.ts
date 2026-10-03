import { useOptionalCustomerSession } from '../customer-auth/CustomerSessionContext'
export function useHelpScope() {
  const session = useOptionalCustomerSession()
  return [
    'help',
    session?.status ?? 'anonymous',
    session?.customer?.id ?? null,
  ] as const
}

export function isHelpScopeReady(scope: ReturnType<typeof useHelpScope>) {
  return (
    scope[1] === 'anonymous' ||
    (scope[1] === 'authenticated' && scope[2] !== null)
  )
}
