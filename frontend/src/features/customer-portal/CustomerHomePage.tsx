import { CustomerRequestLookupPanel } from '../../design-system'
import { useCustomerRequestLookup } from './useCustomerRequestLookup'

export function CustomerHomePage() {
  const lookup = useCustomerRequestLookup()

  return (
    <CustomerRequestLookupPanel
      onSubmit={lookup.openRequest}
      onTicketNumberChange={lookup.updateTicketNumber}
      result={lookup.result}
      ticketNumber={lookup.ticketNumber}
    />
  )
}
