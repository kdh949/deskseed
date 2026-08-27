import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import {
  ApiError,
  addGroupMember,
  createGroup,
  disableGroup,
  listGroupMembers,
  listGroups,
  listStaff,
  removeGroupMember,
  renameGroup,
} from '../../api/client'
import type { GroupMembership, SupportGroup } from '../../api/types'
import {
  DsButton,
  Notification,
  RetryButton,
  ScreenState,
} from '../../design-system'

export function AdminGroupsPage() {
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [staffOptionPage, setStaffOptionPage] = useState(0)
  const [newGroupName, setNewGroupName] = useState('')
  const [selectedGroup, setSelectedGroup] = useState<SupportGroup | null>(null)
  const [renamedGroup, setRenamedGroup] = useState('')
  const [newMemberId, setNewMemberId] = useState('')
  const [groupValidationError, setGroupValidationError] = useState<
    string | null
  >(null)
  const [removeCandidate, setRemoveCandidate] =
    useState<GroupMembership | null>(null)
  const [disableOpen, setDisableOpen] = useState(false)

  const groupsQuery = useQuery({
    queryKey: ['admin-groups', page],
    queryFn: () => listGroups(page),
    retry: false,
  })
  const staffQuery = useQuery({
    queryKey: ['admin-group-member-options', staffOptionPage],
    queryFn: () => listStaff(staffOptionPage, 100),
    enabled: selectedGroup?.status === 'ACTIVE',
    retry: false,
  })
  const membersQuery = useQuery({
    queryKey: ['admin-group-members', selectedGroup?.id],
    queryFn: () => listGroupMembers(selectedGroup!.id),
    enabled: selectedGroup?.status === 'ACTIVE',
    retry: false,
  })
  const refreshGroups = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['admin-groups'] }),
      queryClient.invalidateQueries({ queryKey: ['admin-group-members'] }),
      queryClient.invalidateQueries({
        queryKey: ['admin-group-member-options'],
      }),
    ])
  }
  const createMutation = useMutation({
    mutationFn: createGroup,
    onSuccess: async (group) => {
      setNewGroupName('')
      setGroupValidationError(null)
      await refreshGroups()
      setSelectedGroup(group)
      setRenamedGroup(group.name)
    },
  })
  const renameMutation = useMutation({
    mutationFn: ({ id, name }: { id: string; name: string }) =>
      renameGroup(id, name),
    onSuccess: async (group) => {
      setSelectedGroup(group)
      setRenamedGroup(group.name)
      await refreshGroups()
    },
  })
  const disableMutation = useMutation({
    mutationFn: disableGroup,
    onSuccess: async () => {
      setSelectedGroup(null)
      setRemoveCandidate(null)
      setDisableOpen(false)
      await refreshGroups()
    },
  })
  const addMemberMutation = useMutation({
    mutationFn: ({ groupId, staffId }: { groupId: string; staffId: string }) =>
      addGroupMember(groupId, staffId),
    onSuccess: async () => {
      setNewMemberId('')
      await refreshGroups()
    },
  })
  const removeMemberMutation = useMutation({
    mutationFn: ({ groupId, staffId }: { groupId: string; staffId: string }) =>
      removeGroupMember(groupId, staffId),
    onSuccess: async () => {
      setRemoveCandidate(null)
      await refreshGroups()
    },
  })

  useEffect(() => {
    if (!selectedGroup) return
    const current = groupsQuery.data?.items.find(
      (group) => group.id === selectedGroup.id,
    )
    if (current) setSelectedGroup(current)
    else {
      setSelectedGroup(null)
      setRemoveCandidate(null)
    }
  }, [groupsQuery.data, selectedGroup])

  if (groupsQuery.isPending) {
    return (
      <AdminGroupsScreenState kind="loading" title="지원 그룹을 불러오는 중" />
    )
  }
  if (groupsQuery.isError) {
    const denied =
      groupsQuery.error instanceof ApiError && groupsQuery.error.status === 403
    return (
      <AdminGroupsScreenState
        action={
          denied ? undefined : (
            <RetryButton onClick={() => void groupsQuery.refetch()} />
          )
        }
        description={
          denied
            ? '지원 그룹과 구성원 관리는 ADMIN만 수행할 수 있습니다.'
            : '잠시 후 지원 그룹 목록을 다시 요청해 주세요.'
        }
        kind={denied ? 'denied' : 'error'}
        title={
          denied
            ? '그룹 관리 권한이 없습니다.'
            : '지원 그룹을 불러오지 못했습니다.'
        }
      />
    )
  }

  const submitCreate = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const name = newGroupName.trim()
    if (!name) {
      setGroupValidationError('그룹 이름을 입력해 주세요.')
      return
    }
    setGroupValidationError(null)
    createMutation.mutate(name)
  }
  const submitRename = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!selectedGroup) return
    const name = renamedGroup.trim()
    if (!name) {
      setGroupValidationError('그룹 이름을 입력해 주세요.')
      return
    }
    setGroupValidationError(null)
    renameMutation.mutate({ id: selectedGroup.id, name })
  }

  const groupPage = groupsQuery.data
  const memberIds = new Set(
    membersQuery.data?.items.map((member) => member.staffId),
  )
  const activeStaff = (staffQuery.data?.items ?? []).filter(
    (staff) => staff.status === 'ACTIVE' && !memberIds.has(staff.id),
  )
  const selectedRemoveCandidate =
    selectedGroup && removeCandidate?.groupId === selectedGroup.id
      ? removeCandidate
      : null
  const closeSelectedGroup = () => {
    setSelectedGroup(null)
    setRemoveCandidate(null)
    setDisableOpen(false)
  }
  const changeGroupPage = (nextPage: number) => {
    setPage(nextPage)
    closeSelectedGroup()
  }

  return (
    <main aria-label="그룹 관리" className="admin-page">
      <header className="admin-page-header">
        <div>
          <h1>그룹</h1>
          <p>
            그룹의 이름과 활성 구성원을 관리합니다. 티켓 소유권은 이 화면에서
            변경하지 않습니다.
          </p>
        </div>
        <DsButton onClick={() => void groupsQuery.refetch()} tone="secondary">
          그룹 목록 새로고침
        </DsButton>
      </header>

      <section aria-labelledby="create-group-heading" className="admin-surface">
        <h2 id="create-group-heading">지원 그룹 생성</h2>
        <form className="admin-form" onSubmit={submitCreate}>
          <label className="admin-field" htmlFor="new-group-name">
            <span>그룹 이름</span>
            <input
              id="new-group-name"
              maxLength={100}
              onChange={(event) => setNewGroupName(event.target.value)}
              value={newGroupName}
            />
          </label>
          {groupValidationError ? (
            <Notification title={groupValidationError} tone="warning" />
          ) : null}
          {createMutation.isError ? (
            <GroupMutationNotification
              action="그룹을 생성"
              error={createMutation.error}
            />
          ) : null}
          {createMutation.isSuccess ? (
            <Notification title="지원 그룹을 만들었습니다." tone="success" />
          ) : null}
          <div className="admin-form-actions">
            <DsButton
              disabled={createMutation.isPending}
              tone="primary"
              type="submit"
            >
              {createMutation.isPending ? '그룹 생성 중…' : '지원 그룹 생성'}
            </DsButton>
          </div>
        </form>
      </section>

      <section aria-labelledby="group-list-heading" className="admin-surface">
        <h2 id="group-list-heading">지원 그룹</h2>
        {groupPage.items.length === 0 ? (
          <ScreenState
            compact
            description="새 지원 그룹을 만들면 구성원과 상태를 여기에서 관리할 수 있습니다."
            kind="empty"
            title="등록된 지원 그룹이 없습니다."
          />
        ) : (
          <div className="admin-table-wrap">
            <table className="admin-table">
              <caption className="sr-only">지원 그룹 목록</caption>
              <thead>
                <tr>
                  <th scope="col">이름</th>
                  <th scope="col">상태</th>
                  <th scope="col">구성원</th>
                  <th scope="col">작업</th>
                </tr>
              </thead>
              <tbody>
                {groupPage.items.map((group) => (
                  <tr key={group.id}>
                    <td>{group.name}</td>
                    <td>{group.status === 'ACTIVE' ? '활성' : '비활성'}</td>
                    <td>{group.memberCount}</td>
                    <td>
                      <DsButton
                        aria-expanded={selectedGroup?.id === group.id}
                        onClick={() => {
                          setSelectedGroup(group)
                          setRemoveCandidate(null)
                          setRenamedGroup(group.name)
                          setNewMemberId('')
                          setGroupValidationError(null)
                          setDisableOpen(false)
                        }}
                        tone="secondary"
                      >
                        그룹 관리
                      </DsButton>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {groupPage.totalPages > 1 ? (
          <div className="admin-inline-actions">
            <DsButton
              disabled={page === 0}
              onClick={() => changeGroupPage(page - 1)}
              tone="secondary"
            >
              이전 페이지
            </DsButton>
            <span className="admin-muted">{`${page + 1} / ${groupPage.totalPages} 페이지`}</span>
            <DsButton
              disabled={page + 1 >= groupPage.totalPages}
              onClick={() => changeGroupPage(page + 1)}
              tone="secondary"
            >
              다음 페이지
            </DsButton>
          </div>
        ) : null}
      </section>

      {selectedGroup ? (
        <section
          aria-labelledby="selected-group-heading"
          className="admin-surface"
        >
          <div className="admin-page-header">
            <div>
              <h2 id="selected-group-heading">{selectedGroup.name}</h2>
              <p>
                {selectedGroup.status === 'ACTIVE'
                  ? '활성 그룹'
                  : '비활성 그룹'}
              </p>
            </div>
            <DsButton onClick={closeSelectedGroup} tone="secondary">
              닫기
            </DsButton>
          </div>
          {selectedGroup.status === 'ACTIVE' ? (
            <>
              <form className="admin-form" onSubmit={submitRename}>
                <label className="admin-field" htmlFor="rename-group-name">
                  <span>그룹 이름 변경</span>
                  <input
                    id="rename-group-name"
                    maxLength={100}
                    onChange={(event) => setRenamedGroup(event.target.value)}
                    value={renamedGroup}
                  />
                </label>
                {renameMutation.isError ? (
                  <GroupMutationNotification
                    action="그룹 이름을 변경"
                    error={renameMutation.error}
                  />
                ) : null}
                {renameMutation.isSuccess ? (
                  <Notification
                    title="그룹 이름을 변경했습니다."
                    tone="success"
                  />
                ) : null}
                <div className="admin-form-actions">
                  <DsButton
                    disabled={renameMutation.isPending}
                    tone="primary"
                    type="submit"
                  >
                    {renameMutation.isPending ? '이름 변경 중…' : '이름 변경'}
                  </DsButton>
                </div>
              </form>

              <section
                aria-labelledby="group-members-heading"
                className="admin-surface"
              >
                <h3 id="group-members-heading">활성 구성원</h3>
                {membersQuery.isPending ? (
                  <ScreenState
                    compact
                    kind="loading"
                    title="그룹 구성원을 불러오는 중"
                  />
                ) : membersQuery.isError ? (
                  <Notification
                    title="그룹 구성원을 불러오지 못했습니다."
                    tone="danger"
                  >
                    <p>새로고침한 뒤 다시 시도해 주세요.</p>
                  </Notification>
                ) : (
                  <>
                    {membersQuery.data.items.length === 0 ? (
                      <p className="admin-muted">
                        현재 활성 구성원이 없습니다.
                      </p>
                    ) : (
                      <div className="admin-table-wrap">
                        <table className="admin-table">
                          <caption className="sr-only">
                            그룹 구성원 목록
                          </caption>
                          <thead>
                            <tr>
                              <th scope="col">이름</th>
                              <th scope="col">역할</th>
                              <th scope="col">작업</th>
                            </tr>
                          </thead>
                          <tbody>
                            {membersQuery.data.items.map((member) => (
                              <tr key={member.staffId}>
                                <td>{member.staffDisplayName}</td>
                                <td>{member.role}</td>
                                <td>
                                  <DsButton
                                    aria-expanded={
                                      selectedRemoveCandidate?.staffId ===
                                      member.staffId
                                    }
                                    onClick={() => setRemoveCandidate(member)}
                                    tone="secondary"
                                  >
                                    구성원 제거
                                  </DsButton>
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}
                    <form
                      className="admin-form"
                      onSubmit={(event) => {
                        event.preventDefault()
                        if (!newMemberId) {
                          setGroupValidationError(
                            '추가할 활성 직원을 선택해 주세요.',
                          )
                          return
                        }
                        setGroupValidationError(null)
                        addMemberMutation.mutate({
                          groupId: selectedGroup.id,
                          staffId: newMemberId,
                        })
                      }}
                    >
                      <label className="admin-field" htmlFor="group-new-member">
                        <span>활성 직원 추가</span>
                        <select
                          disabled={staffQuery.isPending || staffQuery.isError}
                          id="group-new-member"
                          onChange={(event) =>
                            setNewMemberId(event.target.value)
                          }
                          value={newMemberId}
                        >
                          <option value="">
                            {staffQuery.isPending
                              ? '직원 목록을 불러오는 중…'
                              : staffQuery.isError
                                ? '직원 목록을 불러오지 못했습니다.'
                                : '직원을 선택하세요'}
                          </option>
                          {activeStaff.map((staff) => (
                            <option key={staff.id} value={staff.id}>
                              {`${staff.displayName} (${staff.email})`}
                            </option>
                          ))}
                        </select>
                      </label>
                      {staffQuery.isError ? (
                        <Notification
                          title="직원 선택 목록을 불러오지 못했습니다."
                          tone="danger"
                        />
                      ) : null}
                      {groupValidationError ? (
                        <Notification
                          title={groupValidationError}
                          tone="warning"
                        />
                      ) : null}
                      {addMemberMutation.isError ? (
                        <GroupMutationNotification
                          action="구성원을 추가"
                          error={addMemberMutation.error}
                        />
                      ) : null}
                      {addMemberMutation.isSuccess ? (
                        <Notification
                          title="그룹 구성원을 추가했습니다."
                          tone="success"
                        />
                      ) : null}
                      <div className="admin-inline-actions">
                        <DsButton
                          disabled={
                            addMemberMutation.isPending ||
                            activeStaff.length === 0
                          }
                          tone="primary"
                          type="submit"
                        >
                          {addMemberMutation.isPending
                            ? '구성원 추가 중…'
                            : '구성원 추가'}
                        </DsButton>
                        {staffQuery.data && staffQuery.data.totalPages > 1 ? (
                          <>
                            <DsButton
                              disabled={staffOptionPage === 0}
                              onClick={() =>
                                setStaffOptionPage((current) => current - 1)
                              }
                              tone="secondary"
                              type="button"
                            >
                              이전 직원 페이지
                            </DsButton>
                            <span className="admin-muted">{`${staffOptionPage + 1} / ${staffQuery.data.totalPages} 직원 페이지`}</span>
                            <DsButton
                              disabled={
                                staffOptionPage + 1 >=
                                staffQuery.data.totalPages
                              }
                              onClick={() =>
                                setStaffOptionPage((current) => current + 1)
                              }
                              tone="secondary"
                              type="button"
                            >
                              다음 직원 페이지
                            </DsButton>
                          </>
                        ) : null}
                      </div>
                    </form>
                  </>
                )}
              </section>

              <div className="admin-confirmation">
                <p>
                  비활성화는 기존 티켓 소유권을 옮기지 않습니다. 서버가 사용
                  중인 그룹과 티켓 제약을 확인합니다.
                </p>
                <DsButton
                  aria-expanded={disableOpen}
                  onClick={() => setDisableOpen((open) => !open)}
                  tone="secondary"
                >
                  그룹 비활성화
                </DsButton>
              </div>
              {disableOpen ? (
                <div
                  className="admin-confirmation"
                  role="group"
                  aria-label="그룹 비활성화 최종 확인"
                >
                  <p>{`${selectedGroup.name} 그룹을 비활성화할까요?`}</p>
                  <DsButton
                    disabled={disableMutation.isPending}
                    onClick={() => disableMutation.mutate(selectedGroup.id)}
                    tone="primary"
                  >
                    {disableMutation.isPending
                      ? '비활성화 중…'
                      : '비활성화 확정'}
                  </DsButton>
                  <DsButton
                    disabled={disableMutation.isPending}
                    onClick={() => setDisableOpen(false)}
                    tone="secondary"
                  >
                    취소
                  </DsButton>
                  {disableMutation.isError ? (
                    <GroupMutationNotification
                      action="그룹을 비활성화"
                      error={disableMutation.error}
                    />
                  ) : null}
                </div>
              ) : null}
            </>
          ) : (
            <Notification title="비활성 그룹" tone="info">
              <p>
                비활성 그룹에는 구성원을 추가하거나 이름을 변경할 수 없습니다.
              </p>
            </Notification>
          )}
          {selectedRemoveCandidate ? (
            <div
              className="admin-confirmation"
              role="group"
              aria-label="구성원 제거 최종 확인"
            >
              <p>{`${selectedRemoveCandidate.staffDisplayName}을(를) ${selectedGroup.name} 그룹에서 제거할까요?`}</p>
              <DsButton
                disabled={removeMemberMutation.isPending}
                onClick={() =>
                  removeMemberMutation.mutate({
                    groupId: selectedRemoveCandidate.groupId,
                    staffId: selectedRemoveCandidate.staffId,
                  })
                }
                tone="primary"
              >
                {removeMemberMutation.isPending
                  ? '제거 중…'
                  : '구성원 제거 확정'}
              </DsButton>
              <DsButton
                disabled={removeMemberMutation.isPending}
                onClick={() => setRemoveCandidate(null)}
                tone="secondary"
              >
                취소
              </DsButton>
              {removeMemberMutation.isError ? (
                <GroupMutationNotification
                  action="구성원을 제거"
                  error={removeMemberMutation.error}
                />
              ) : null}
            </div>
          ) : null}
        </section>
      ) : null}
    </main>
  )
}

function GroupMutationNotification({
  action,
  error,
}: {
  action: string
  error: unknown
}) {
  const conflict = error instanceof ApiError && error.status === 409
  return (
    <Notification
      title={
        conflict ? `${action}할 수 없습니다.` : `${action}하지 못했습니다.`
      }
      tone={conflict ? 'conflict' : 'danger'}
    >
      <p>
        {conflict
          ? '서버가 현재 그룹·직원·티켓 제약을 확인했습니다. 입력은 유지됩니다.'
          : '입력은 유지됩니다. 잠시 후 다시 시도해 주세요.'}
      </p>
    </Notification>
  )
}

function AdminGroupsScreenState({
  action,
  description,
  kind,
  title,
}: {
  action?: ReactNode
  description?: string
  kind: 'denied' | 'error' | 'loading'
  title: string
}) {
  return (
    <main className="admin-page">
      <ScreenState
        action={action}
        description={description}
        kind={kind}
        title={title}
      />
    </main>
  )
}
