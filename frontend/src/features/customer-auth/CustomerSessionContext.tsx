import { useQuery, useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, type PropsWithChildren } from 'react'
import { getCurrentCustomer, type CurrentCustomer } from './customerAuthClient'

interface CustomerSessionValue {
  status: 'loading' | 'authenticated' | 'anonymous' | 'error'
  customer: CurrentCustomer | null
  retry: () => void
}

const CustomerSessionContext = createContext<CustomerSessionValue | null>(null)

export function CustomerSessionProvider({ children }: PropsWithChildren) {
  const queryClient = useQueryClient()
  const query = useQuery({
    queryKey: ['customer-session'],
    queryFn: getCurrentCustomer,
    retry: false,
  })
  const value: CustomerSessionValue = {
    status: query.isPending
      ? 'loading'
      : query.isError
        ? 'error'
        : query.data
          ? 'authenticated'
          : 'anonymous',
    customer: query.data ?? null,
    retry: () =>
      void queryClient.invalidateQueries({ queryKey: ['customer-session'] }),
  }
  return (
    <CustomerSessionContext.Provider value={value}>
      {children}
    </CustomerSessionContext.Provider>
  )
}

export function useCustomerSession() {
  const value = useContext(CustomerSessionContext)
  if (!value)
    throw new Error(
      'useCustomerSession must be used within CustomerSessionProvider',
    )
  return value
}

export function useOptionalCustomerSession() {
  return useContext(CustomerSessionContext)
}
