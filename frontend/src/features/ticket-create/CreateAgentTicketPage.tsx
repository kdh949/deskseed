import { useQuery } from '@tanstack/react-query'
import { listTicketAssignmentOptions } from '../../api/client'
import { CreateAgentTicketForm } from './CreateAgentTicketForm'
import { useCreateAgentTicket } from './model/useCreateAgentTicket'
import { useRequesterSearch } from './model/useRequesterSearch'
import type { AssignmentOptionsState } from './CreateAgentTicketForm'

export function CreateAgentTicketPage() {
  const assignmentOptionsQuery = useQuery({
    queryKey: ['ticket-assignment-options'],
    queryFn: listTicketAssignmentOptions,
  })
  const { submit, submitting, error, warnings } = useCreateAgentTicket()
  const requesterSearch = useRequesterSearch()

  const assignmentOptions: AssignmentOptionsState =
    assignmentOptionsQuery.isPending
      ? { status: 'loading' }
      : assignmentOptionsQuery.isError
        ? { status: 'error' }
        : { status: 'ready', groups: assignmentOptionsQuery.data.groups }

  return (
    <CreateAgentTicketForm
      assignmentOptions={assignmentOptions}
      error={error}
      onRetryOptions={() => assignmentOptionsQuery.refetch()}
      onSubmit={submit}
      requesterSearch={requesterSearch}
      submitting={submitting}
      warnings={warnings}
    />
  )
}
