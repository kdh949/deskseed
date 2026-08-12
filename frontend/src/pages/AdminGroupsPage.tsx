import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
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
} from '../api/client'
import type { GroupMembership, StaffAccount, SupportGroup } from '../api/types'
import { Notification, ScreenState } from '../shared/ui/system'

function groupError(error: unknown): string {
  if (!(error instanceof ApiError)) return '요청을 처리할 수 없습니다.'
  const code = error.problem?.code
  if (error.status === 403) return '관리자 권한이 필요합니다.'
  if (code === 'DUPLICATE_GROUP_NAME') return '이미 사용 중인 그룹 이름입니다.'
  if (code === 'DUPLICATE_MEMBERSHIP') return '이미 이 그룹에 속한 직원입니다.'
  if (code === 'MEMBER_HAS_ASSIGNED_TICKETS')
    return '이 그룹에서 현재 배정된 티켓이 있어 멤버를 제거할 수 없습니다.'
  if (code === 'GROUP_HAS_ASSIGNED_TICKETS')
    return '현재 티켓이 배정된 그룹은 비활성화할 수 없습니다.'
  return `${error.message}${error.requestId ? ` 요청 ID: ${error.requestId}` : ''}`
}

const GROUP_PAGE_SIZE = 20
const STAFF_CANDIDATE_PAGE_SIZE = 50
const MEMBER_PAGE_SIZE = 20

export function AdminGroupsPage() {
  const [groups, setGroups] = useState<SupportGroup[]>([])
  const [staff, setStaff] = useState<StaffAccount[]>([])
  const [members, setMembers] = useState<GroupMembership[]>([])
  const [groupPage, setGroupPage] = useState(0)
  const [groupTotalCount, setGroupTotalCount] = useState(0)
  const [groupTotalPages, setGroupTotalPages] = useState(0)
  const [staffPage, setStaffPage] = useState(0)
  const [staffTotalCount, setStaffTotalCount] = useState(0)
  const [staffTotalPages, setStaffTotalPages] = useState(0)
  const [memberPage, setMemberPage] = useState(0)
  const [memberTotalCount, setMemberTotalCount] = useState(0)
  const [memberTotalPages, setMemberTotalPages] = useState(0)
  const [selectedId, setSelectedId] = useState('')
  const [newName, setNewName] = useState('')
  const [renameValue, setRenameValue] = useState('')
  const [memberStaffId, setMemberStaffId] = useState('')
  const [loading, setLoading] = useState(true)
  const [membersLoading, setMembersLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const errorRef = useRef<HTMLDivElement>(null)

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [nextGroups, nextStaff] = await Promise.all([
        listGroups(groupPage, GROUP_PAGE_SIZE),
        listStaff(staffPage, STAFF_CANDIDATE_PAGE_SIZE),
      ])
      setGroups(nextGroups.items)
      setGroupTotalCount(nextGroups.totalCount)
      setGroupTotalPages(nextGroups.totalPages)
      setStaff(nextStaff.items.filter((item) => item.status === 'ACTIVE'))
      setStaffTotalCount(nextStaff.totalCount)
      setStaffTotalPages(nextStaff.totalPages)
      setSelectedId(
        (current) =>
          nextGroups.items.find(
            (group) => group.id === current && group.status === 'ACTIVE',
          )?.id ||
          nextGroups.items.find((group) => group.status === 'ACTIVE')?.id ||
          '',
      )
    } catch (caught) {
      setError(groupError(caught))
    } finally {
      setLoading(false)
    }
  }, [groupPage, staffPage])

  const reloadMembers = useCallback(async () => {
    if (!selectedId) {
      setMembers([])
      return
    }
    setMembersLoading(true)
    try {
      const result = await listGroupMembers(
        selectedId,
        memberPage,
        MEMBER_PAGE_SIZE,
      )
      if (
        result.items.length === 0 &&
        memberPage > 0 &&
        memberPage >= result.totalPages
      ) {
        setMemberPage(Math.max(0, result.totalPages - 1))
        return
      }
      setMembers(result.items)
      setMemberTotalCount(result.totalCount)
      setMemberTotalPages(result.totalPages)
    } catch (caught) {
      setError(groupError(caught))
    } finally {
      setMembersLoading(false)
    }
  }, [memberPage, selectedId])

  useEffect(() => void reload(), [reload])
  useEffect(() => void reloadMembers(), [reloadMembers])
  useEffect(() => {
    const selected = groups.find((group) => group.id === selectedId)
    setRenameValue(selected?.name ?? '')
  }, [groups, selectedId])
  useEffect(() => {
    if (error) errorRef.current?.focus()
  }, [error])

  async function act(action: () => Promise<unknown>, refreshMembers = false) {
    setError(null)
    try {
      await action()
      await reload()
      if (refreshMembers) await reloadMembers()
    } catch (caught) {
      setError(groupError(caught))
    }
  }

  function create(event: FormEvent) {
    event.preventDefault()
    void act(async () => {
      const created = await createGroup(newName)
      setNewName('')
      setSelectedId(created.id)
    })
  }

  const selected = groups.find((group) => group.id === selectedId)
  const availableStaff = staff.filter(
    (item) => !members.some((member) => member.staffId === item.id),
  )

  return (
    <section aria-labelledby="groups-admin-title">
      <div className="admin-title-row">
        <div>
          <p className="eyebrow">ORGANIZATION</p>
          <h1 id="groups-admin-title">그룹과 멤버십</h1>
          <p>지원 그룹을 만들고 활성 직원을 멤버로 연결합니다.</p>
        </div>
      </div>
      {error ? (
        <Notification
          tone="danger"
          title={error}
          tabIndex={-1}
          ref={errorRef}
        />
      ) : null}
      <div className="admin-grid groups-grid">
        <section className="admin-panel" aria-labelledby="group-list-title">
          <h2 id="group-list-title">그룹</h2>
          <form className="inline-admin-form" onSubmit={create}>
            <label>
              새 그룹 이름
              <input
                required
                maxLength={100}
                value={newName}
                onChange={(event) => setNewName(event.target.value)}
              />
            </label>
            <button className="button primary small" type="submit">
              그룹 추가
            </button>
          </form>
          {loading ? (
            <ScreenState
              kind="loading"
              compact
              title="그룹 목록을 불러오는 중입니다."
            />
          ) : null}
          {!loading && groups.length === 0 ? (
            <ScreenState kind="empty" compact title="아직 그룹이 없습니다." />
          ) : null}
          <ul className="group-list">
            {groups.map((group) => (
              <li key={group.id}>
                <button
                  className={
                    group.id === selectedId
                      ? 'group-select active'
                      : 'group-select'
                  }
                  type="button"
                  onClick={() => {
                    setSelectedId(group.id)
                    setMemberPage(0)
                  }}
                  disabled={group.status === 'DISABLED'}
                >
                  <span>{group.name}</span>
                  <small>
                    {group.status === 'ACTIVE'
                      ? `활성 · 멤버 ${group.memberCount}명`
                      : '비활성'}
                  </small>
                </button>
              </li>
            ))}
          </ul>
          {!loading && groups.length > 0 ? (
            <nav className="admin-pagination" aria-label="그룹 목록 페이지">
              <button
                className="button secondary small"
                type="button"
                disabled={groupPage === 0}
                onClick={() =>
                  setGroupPage((current) => Math.max(0, current - 1))
                }
              >
                이전
              </button>
              <span>
                전체 {groupTotalCount}개 · {groupPage + 1}/
                {Math.max(groupTotalPages, 1)} 페이지
              </span>
              <button
                className="button secondary small"
                type="button"
                disabled={groupPage + 1 >= groupTotalPages}
                onClick={() => setGroupPage((current) => current + 1)}
              >
                다음
              </button>
            </nav>
          ) : null}
        </section>
        <section
          className="admin-panel admin-list-panel"
          aria-labelledby="membership-title"
        >
          <h2 id="membership-title">
            {selected ? `${selected.name} 멤버십` : '멤버십'}
          </h2>
          {!selected ? (
            <ScreenState
              kind="empty"
              compact
              title="활성 그룹을 선택해 주세요."
            />
          ) : null}
          {selected ? (
            <>
              <form
                className="inline-admin-form"
                onSubmit={(event) => {
                  event.preventDefault()
                  void act(() => renameGroup(selected.id, renameValue))
                }}
              >
                <label>
                  그룹 이름
                  <input
                    required
                    maxLength={100}
                    value={renameValue}
                    onChange={(event) => setRenameValue(event.target.value)}
                  />
                </label>
                <button className="button secondary small" type="submit">
                  이름 변경
                </button>
                <button
                  className="text-button danger"
                  type="button"
                  onClick={() =>
                    void act(async () => {
                      await disableGroup(selected.id)
                      setSelectedId('')
                    })
                  }
                >
                  그룹 비활성화
                </button>
              </form>
              <form
                className="inline-admin-form"
                onSubmit={(event) => {
                  event.preventDefault()
                  if (memberStaffId)
                    void act(async () => {
                      await addGroupMember(selected.id, memberStaffId)
                      setMemberStaffId('')
                    }, true)
                }}
              >
                <label>
                  직원 추가
                  <select
                    required
                    value={memberStaffId}
                    onChange={(event) => setMemberStaffId(event.target.value)}
                  >
                    <option value="">직원을 선택하세요</option>
                    {availableStaff.map((item) => (
                      <option key={item.id} value={item.id}>
                        {item.displayName} · {item.role}
                      </option>
                    ))}
                  </select>
                </label>
                <button
                  className="button primary small"
                  type="submit"
                  disabled={!memberStaffId}
                >
                  멤버 추가
                </button>
              </form>
              <nav className="admin-pagination" aria-label="직원 후보 페이지">
                <button
                  className="button secondary small"
                  type="button"
                  disabled={staffPage === 0}
                  onClick={() =>
                    setStaffPage((current) => Math.max(0, current - 1))
                  }
                >
                  이전 후보
                </button>
                <span>
                  전체 {staffTotalCount}명 · {staffPage + 1}/
                  {Math.max(staffTotalPages, 1)} 페이지
                </span>
                <button
                  className="button secondary small"
                  type="button"
                  disabled={staffPage + 1 >= staffTotalPages}
                  onClick={() => setStaffPage((current) => current + 1)}
                >
                  다음 후보
                </button>
              </nav>
              {membersLoading ? (
                <ScreenState
                  kind="loading"
                  compact
                  title="멤버를 불러오는 중입니다."
                />
              ) : null}
              {!membersLoading && members.length === 0 ? (
                <ScreenState
                  kind="empty"
                  compact
                  title="이 그룹에 활성 멤버가 없습니다."
                />
              ) : null}
              <ul className="membership-list">
                {members.map((member) => (
                  <li key={member.staffId}>
                    <span>
                      <strong>{member.staffDisplayName}</strong>
                      <small>{member.role}</small>
                    </span>
                    <button
                      className="text-button danger"
                      type="button"
                      onClick={() =>
                        void act(
                          () => removeGroupMember(selected.id, member.staffId),
                          true,
                        )
                      }
                    >
                      멤버 제거
                    </button>
                  </li>
                ))}
              </ul>
              {!membersLoading && members.length > 0 ? (
                <nav className="admin-pagination" aria-label="그룹 멤버 페이지">
                  <button
                    className="button secondary small"
                    type="button"
                    disabled={memberPage === 0}
                    onClick={() =>
                      setMemberPage((current) => Math.max(0, current - 1))
                    }
                  >
                    이전
                  </button>
                  <span>
                    전체 {memberTotalCount}명 · {memberPage + 1}/
                    {Math.max(memberTotalPages, 1)} 페이지
                  </span>
                  <button
                    className="button secondary small"
                    type="button"
                    disabled={memberPage + 1 >= memberTotalPages}
                    onClick={() => setMemberPage((current) => current + 1)}
                  >
                    다음
                  </button>
                </nav>
              ) : null}
            </>
          ) : null}
        </section>
      </div>
    </section>
  )
}
