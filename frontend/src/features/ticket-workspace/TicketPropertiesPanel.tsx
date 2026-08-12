import { DeskseedIcon } from '../../design-system/primitives/DeskseedIcon'
import {
  DsAvatar,
  DsIconButton,
} from '../../design-system/primitives/DeskseedPrimitives'
import {
  DsPropertyField,
  DsSelect,
  DsTagInput,
} from '../../design-system/primitives/DeskseedControls'
import agentAvatar from '../../assets/deskseed/agent-mina-park-v1.png'
import type { WorkspaceTicket } from './ticketWorkspaceFixture'

type TicketPropertiesPanelProps = {
  collapsed: boolean
  onCollapse: () => void
  onResolveConflict: () => void
  readOnly?: boolean
  showConflict: boolean
  ticket: WorkspaceTicket
}

function PropertySelect({
  label,
  value,
  options = [value],
  disabled = false,
}: {
  disabled?: boolean
  label: string
  options?: string[]
  value: string
}) {
  return (
    <DsPropertyField label={label}>
      <DsSelect aria-label={label} defaultValue={value} disabled={disabled}>
        {options.map((option) => (
          <option key={option}>{option}</option>
        ))}
      </DsSelect>
    </DsPropertyField>
  )
}

export function TicketPropertiesPanel({
  collapsed,
  onCollapse,
  onResolveConflict,
  readOnly = false,
  showConflict,
  ticket,
}: TicketPropertiesPanelProps) {
  if (collapsed) {
    return (
      <aside
        className="ticket-properties ticket-properties--collapsed"
        aria-label="티켓 속성"
      >
        <DsIconButton
          icon="chevronDoubleLeft"
          label="티켓 속성 펼치기"
          onClick={onCollapse}
        />
      </aside>
    )
  }

  return (
    <aside className="ticket-properties" aria-label="티켓 속성">
      <div className="ticket-panel-heading">
        <h2>Ticket properties</h2>
        <DsIconButton
          icon="chevronDoubleLeft"
          label="티켓 속성 접기"
          onClick={onCollapse}
        />
      </div>
      {showConflict ? (
        <section
          className="ticket-conflict-banner"
          aria-label="담당자 저장 충돌"
        >
          <DeskseedIcon name="alertWarning" />
          <div>
            <strong>담당자 저장 충돌</strong>
            <p>
              다른 상담사가 Assignee를 변경했습니다. 현재 작성 내용은
              보존됩니다.
            </p>
          </div>
          <button
            className="ticket-inline-button"
            onClick={onResolveConflict}
            type="button"
          >
            서버 값 적용
          </button>
        </section>
      ) : null}
      <div className="ticket-properties-form">
        <PropertySelect
          disabled={readOnly}
          label="Status"
          options={['New', 'Open', 'Pending', 'Solved']}
          value={ticket.status}
        />
        <DsPropertyField label="Priority">
          <span className="ticket-select-shell ticket-select-shell--priority">
            <DeskseedIcon name="arrowLeft" />
            <DsSelect
              aria-label="Priority"
              defaultValue={ticket.priority}
              disabled={readOnly}
            >
              <option>Low</option>
              <option>Normal</option>
              <option>High</option>
              <option>Urgent</option>
            </DsSelect>
          </span>
        </DsPropertyField>
        <PropertySelect
          disabled={readOnly}
          label="Group"
          options={['Billing', 'Support', 'Technical']}
          value={ticket.group}
        />
        <DsPropertyField label="Assignee">
          <span className="ticket-select-with-avatar">
            <DsAvatar name={ticket.assignee} size="sm" src={agentAvatar} />
            <DsSelect
              aria-label="Assignee"
              defaultValue={ticket.assignee}
              disabled={readOnly}
            >
              <option>{ticket.assignee}</option>
              <option>Jae Lee</option>
            </DsSelect>
            <button
              aria-label="담당자 선택 해제"
              disabled={readOnly}
              type="button"
            >
              <DeskseedIcon name="x" size="sm" />
            </button>
          </span>
        </DsPropertyField>
        <PropertySelect
          disabled={readOnly}
          label="Requester"
          value={ticket.requester}
        />
        {ticket.organization ? (
          <PropertySelect
            disabled={readOnly}
            label="Organization"
            value={ticket.organization}
          />
        ) : null}
        {ticket.tags?.length ? (
          <DsPropertyField label="Tags">
            <DsTagInput label="Tags" tags={ticket.tags} />
          </DsPropertyField>
        ) : null}
        {ticket.productArea ? (
          <PropertySelect
            disabled={readOnly}
            label="Product area"
            value={ticket.productArea}
          />
        ) : null}
        {ticket.channel ? (
          <PropertySelect
            disabled={readOnly}
            label="Channel"
            value={ticket.channel.replace('via ', '')}
          />
        ) : null}
        {ticket.language ? (
          <PropertySelect
            disabled={readOnly}
            label="Language"
            value={ticket.language}
          />
        ) : null}
      </div>
      <div className="ticket-properties-footer">
        <button
          className="ticket-collapse-button"
          onClick={onCollapse}
          type="button"
        >
          <DeskseedIcon name="chevronDoubleLeft" />
          Collapse properties
        </button>
      </div>
    </aside>
  )
}
