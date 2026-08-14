import { useState } from 'react'
import { ApiError } from '../../api/client'
import { AuditActivityDetailDrawer } from './AuditActivityDetailDrawer'
import { AuditExplorer, type AuditActivitiesState } from './AuditExplorer'
import { CreateAuditExportDrawer } from './CreateAuditExportDrawer'
import { useAuditActivities } from './model/useAuditActivities'
import { useAuditActivityDetail } from './model/useAuditActivityDetail'
import { useAuditActivityFilters } from './model/useAuditActivityFilters'
import { useCreateAuditExport } from './model/useCreateAuditExport'

export function AuditExplorerPage() {
  const {
    filters,
    cursor,
    hasActiveFilters,
    updateFilter,
    setCursor,
    clearFilters,
  } = useAuditActivityFilters()
  const activitiesQuery = useAuditActivities(filters, cursor)
  const [selectedActivityId, setSelectedActivityId] = useState<string | null>(
    null,
  )
  const detailQuery = useAuditActivityDetail(selectedActivityId)
  const [exportDrawerOpen, setExportDrawerOpen] = useState(false)
  const createExport = useCreateAuditExport()

  const activities: AuditActivitiesState = activitiesQuery.isPending
    ? { status: 'loading' }
    : activitiesQuery.isError
      ? {
          status: 'error',
          denied:
            activitiesQuery.error instanceof ApiError &&
            activitiesQuery.error.status === 403,
          requestId:
            activitiesQuery.error instanceof ApiError
              ? activitiesQuery.error.requestId
              : undefined,
        }
      : {
          status: 'ready',
          items: activitiesQuery.data.items,
          nextCursor: activitiesQuery.data.nextCursor,
          projection: activitiesQuery.data.projection,
        }

  return (
    <>
      <AuditExplorer
        activities={activities}
        filters={filters}
        hasActiveFilters={hasActiveFilters}
        onClearFilters={clearFilters}
        onExport={() => setExportDrawerOpen(true)}
        onNextPage={() => {
          if (activities.status === 'ready' && activities.nextCursor) {
            setCursor(activities.nextCursor)
          }
        }}
        onOpenActivity={setSelectedActivityId}
        onRetryActivities={() => activitiesQuery.refetch()}
        onUpdateFilter={updateFilter}
      />
      <AuditActivityDetailDrawer
        onClose={() => setSelectedActivityId(null)}
        onOpenActivity={setSelectedActivityId}
        onRetry={() => detailQuery.refetch()}
        open={selectedActivityId !== null}
        state={
          selectedActivityId === null
            ? null
            : detailQuery.isPending
              ? { status: 'loading' }
              : detailQuery.isError
                ? {
                    status: 'error',
                    requestId:
                      detailQuery.error instanceof ApiError
                        ? detailQuery.error.requestId
                        : undefined,
                  }
                : { status: 'ready', detail: detailQuery.data }
        }
      />
      <CreateAuditExportDrawer
        error={createExport.error}
        filters={filters}
        onClose={() => {
          setExportDrawerOpen(false)
          createExport.reset()
        }}
        onSubmit={createExport.submit}
        open={exportDrawerOpen}
        submitting={createExport.submitting}
      />
    </>
  )
}
