import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import {
  ApiError,
  addGroupMember,
  createGroup,
  disableGroup,
  listGroupMembers,
  listGroups,
  searchAdminGroups,
  searchAdminStaff,
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
  const [searchDraft, setSearchDraft] = useState('')
  const [search, setSearch] = useState<{ query: string; key: string } | null>(
    null,
  )
  const [memberPage, setMemberPage] = useState(0)
  const [memberSearchDraft, setMemberSearchDraft] = useState('')
  const [memberSearch, setMemberSearch] = useState<{
    query: string
    key: string
  } | null>(null)
  const [staffSearchDraft, setStaffSearchDraft] = useState('')
  const [staffSearch, setStaffSearch] = useState<{
    query: string
    key: string
  } | null>(null)
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
    queryKey: ['admin-groups', search?.key ?? 'list', page],
    queryFn: () =>
      search
        ? searchAdminGroups({ query: search.query }, page)
        : listGroups(page),
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    retry: false,
  })
  const staffQuery = useQuery({
    queryKey: [
      'admin-group-member-options',
      selectedGroup?.id,
      staffSearch?.key,
      staffOptionPage,
    ],
    queryFn: () =>
      searchAdminStaff(
        {
          query: staffSearch!.query,
          status: 'ACTIVE',
          excludeGroupId: selectedGroup!.id,
        },
        staffOptionPage,
      ),
    enabled: selectedGroup?.status === 'ACTIVE' && staffSearch !== null,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    retry: false,
  })
  const membersQuery = useQuery({
    queryKey: [
      'admin-group-members',
      selectedGroup?.id,
      memberSearch?.key ?? 'list',
      memberPage,
    ],
    queryFn: async () => {
      if (!memberSearch) return listGroupMembers(selectedGroup!.id, memberPage)
      const result = await searchAdminStaff(
        { query: memberSearch.query, memberOfGroupId: selectedGroup!.id },
        memberPage,
      )
      return {
        ...result,
        items: result.items.map((staff) => ({
          groupId: selectedGroup!.id,
          staffId: staff.id,
          staffDisplayName: staff.displayName,
          role: staff.role,
        })),
      }
    },
    enabled: selectedGroup?.status === 'ACTIVE',
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
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
  }, [groupsQuery.data, selectedGroup])

  if (groupsQuery.isPending && !search) {
    return (
      <AdminGroupsScreenState kind="loading" title="지원 그룹을 불러오는 중" />
    )
  }
  if (groupsQuery.isError && !search) {
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
  const activeStaff = staffQuery.data?.items ?? []
  const selectedRemoveCandidate =
    selectedGroup && removeCandidate?.groupId === selectedGroup.id
      ? removeCandidate
      : null
  const closeSelectedGroup = () => {
    setSelectedGroup(null)
    setRemoveCandidate(null)
    setDisableOpen(false)
    resetMemberSearches()
  }
  const resetMemberSearches = () => {
    setMemberSearchDraft('')
    setMemberSearch(null)
    setMemberPage(0)
    setStaffSearchDraft('')
    setStaffSearch(null)
    setStaffOptionPage(0)
    setNewMemberId('')
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
        <form
          className="admin-form"
          onSubmit={(event) => {
            event.preventDefault()
            closeSelectedGroup()
            setPage(0)
            setSearch(
              searchDraft.trim()
                ? { query: searchDraft, key: crypto.randomUUID() }
                : null,
            )
          }}
        >
          <label className="admin-field" htmlFor="group-search">
            <span>그룹 이름 검색</span>
            <input
              id="group-search"
              type="search"
              autoComplete="off"
              maxLength={254}
              value={searchDraft}
              onChange={(event) => setSearchDraft(event.target.value)}
            />
          </label>
          <div className="admin-form-actions">
            <DsButton
              type="submit"
              tone="primary"
              disabled={groupsQuery.isFetching}
            >
              그룹 검색
            </DsButton>
            <DsButton
              onClick={() => {
                closeSelectedGroup()
                setSearchDraft('')
                setSearch(null)
                setPage(0)
              }}
            >
              검색 초기화
            </DsButton>
          </div>
        </form>
        {groupsQuery.isPending ? (
          <ScreenState compact kind="loading" title="그룹을 검색하는 중" />
        ) : groupsQuery.isError ? (
          <ScreenState
            compact
            kind={
              groupsQuery.error instanceof ApiError &&
              groupsQuery.error.status === 403
                ? 'denied'
                : 'error'
            }
            title="그룹 검색 결과를 불러오지 못했습니다."
            action={<RetryButton onClick={() => void groupsQuery.refetch()} />}
          />
        ) : groupPage && groupPage.items.length === 0 ? (
          <ScreenState
            compact
            description={
              search
                ? '다른 이름으로 검색하거나 검색을 초기화해 주세요.'
                : '새 지원 그룹을 만들면 구성원과 상태를 여기에서 관리할 수 있습니다.'
            }
            kind="empty"
            title={
              search ? '검색 결과가 없습니다.' : '등록된 지원 그룹이 없습니다.'
            }
          />
        ) : groupPage ? (
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
                          resetMemberSearches()
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
        ) : null}
        {groupPage && !groupsQuery.isError ? (
          <p
            role="status"
            className="admin-muted"
          >{`${search ? '검색 결과' : '전체 그룹'} ${groupPage.totalCount}개`}</p>
        ) : null}
        {groupPage && !groupsQuery.isError && groupPage.totalPages > 1 ? (
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
                <form
                  className="admin-form"
                  onSubmit={(event) => {
                    event.preventDefault()
                    setRemoveCandidate(null)
                    setMemberPage(0)
                    setMemberSearch(
                      memberSearchDraft.trim()
                        ? { query: memberSearchDraft, key: crypto.randomUUID() }
                        : null,
                    )
                  }}
                >
                  <label className="admin-field" htmlFor="member-search">
                    <span>구성원 이름 또는 이메일 검색</span>
                    <input
                      id="member-search"
                      type="search"
                      autoComplete="off"
                      maxLength={254}
                      value={memberSearchDraft}
                      onChange={(event) =>
                        setMemberSearchDraft(event.target.value)
                      }
                    />
                  </label>
                  <div className="admin-form-actions">
                    <DsButton
                      type="submit"
                      tone="primary"
                      disabled={membersQuery.isFetching}
                    >
                      구성원 검색
                    </DsButton>
                    <DsButton
                      onClick={() => {
                        setMemberSearchDraft('')
                        setMemberSearch(null)
                        setMemberPage(0)
                        setRemoveCandidate(null)
                      }}
                    >
                      구성원 검색 초기화
                    </DsButton>
                  </div>
                </form>
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
                    <RetryButton onClick={() => void membersQuery.refetch()} />
                  </Notification>
                ) : (
                  <>
                    {membersQuery.data.items.length === 0 ? (
                      <p className="admin-muted">
                        {memberSearch
                          ? '검색 조건에 맞는 구성원이 없습니다.'
                          : '현재 활성 구성원이 없습니다.'}
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
                    <p
                      role="status"
                      className="admin-muted"
                    >{`${memberSearch ? '검색된 구성원' : '전체 구성원'} ${membersQuery.data.totalCount}명`}</p>
                    {membersQuery.data.totalPages > 1 ? (
                      <div className="admin-inline-actions">
                        <DsButton
                          disabled={memberPage === 0}
                          onClick={() => {
                            setMemberPage(memberPage - 1)
                            setRemoveCandidate(null)
                          }}
                        >
                          이전 구성원 페이지
                        </DsButton>
                        <span>{`${memberPage + 1} / ${membersQuery.data.totalPages} 구성원 페이지`}</span>
                        <DsButton
                          disabled={
                            memberPage + 1 >= membersQuery.data.totalPages
                          }
                          onClick={() => {
                            setMemberPage(memberPage + 1)
                            setRemoveCandidate(null)
                          }}
                        >
                          다음 구성원 페이지
                        </DsButton>
                      </div>
                    ) : null}
                    <form
                      className="admin-form"
                      onSubmit={(event) => {
                        event.preventDefault()
                        setStaffOptionPage(0)
                        setNewMemberId('')
                        setStaffSearch(
                          staffSearchDraft.trim()
                            ? {
                                query: staffSearchDraft,
                                key: crypto.randomUUID(),
                              }
                            : null,
                        )
                      }}
                    >
                      <label className="admin-field" htmlFor="candidate-search">
                        <span>추가할 직원 이름 또는 이메일 검색</span>
                        <input
                          id="candidate-search"
                          type="search"
                          autoComplete="off"
                          maxLength={254}
                          value={staffSearchDraft}
                          onChange={(event) =>
                            setStaffSearchDraft(event.target.value)
                          }
                        />
                      </label>
                      <p className="admin-muted">
                        이 그룹에 속하지 않은 활성 직원을 검색합니다.
                      </p>
                      <div className="admin-form-actions">
                        <DsButton
                          type="submit"
                          tone="primary"
                          disabled={staffQuery.isFetching}
                        >
                          추가할 직원 검색
                        </DsButton>
                        <DsButton
                          onClick={() => {
                            setStaffSearchDraft('')
                            setStaffSearch(null)
                            setStaffOptionPage(0)
                            setNewMemberId('')
                          }}
                        >
                          직원 검색 초기화
                        </DsButton>
                      </div>
                    </form>
                    {staffSearch && staffQuery.isPending ? (
                      <ScreenState
                        compact
                        kind="loading"
                        title="추가할 직원을 검색하는 중"
                      />
                    ) : null}
                    {staffQuery.data ? (
                      <p
                        role="status"
                        className="admin-muted"
                      >{`추가 가능한 직원 ${staffQuery.data.totalCount}명`}</p>
                    ) : null}
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
                            {!staffSearch
                              ? '이름 또는 이메일로 먼저 검색하세요'
                              : staffQuery.isPending
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
                        >
                          <RetryButton
                            onClick={() => void staffQuery.refetch()}
                          />
                        </Notification>
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
                              onClick={() => {
                                setNewMemberId('')
                                setStaffOptionPage((current) => current - 1)
                              }}
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
                              onClick={() => {
                                setNewMemberId('')
                                setStaffOptionPage((current) => current + 1)
                              }}
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
