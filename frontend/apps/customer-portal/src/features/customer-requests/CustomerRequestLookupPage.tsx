import { CustomerRequestLookupPanel } from '../../design-system'
import { useCustomerRequestLookup } from '../customer-portal/useCustomerRequestLookup'

export function CustomerRequestLookupPage() {
  const lookup = useCustomerRequestLookup()

  return (
    <div className="customer-page">
      <CustomerRequestLookupPanel
        onSubmit={lookup.openRequest}
        onTicketNumberChange={lookup.updateTicketNumber}
        result={lookup.result}
        ticketNumber={lookup.ticketNumber}
      />
    </div>
  )
}
