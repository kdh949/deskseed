import {
  useRef,
  type CSSProperties,
  type KeyboardEvent,
  type PointerEvent,
  type ReactNode,
} from 'react'

interface SplitPanelProps {
  propertyPanel: ReactNode
  conversationPanel: ReactNode
  contextPanel?: ReactNode
  propertyWidth: number
  contextWidth: number
  onPropertyWidthChange: (value: number) => void
  onContextWidthChange: (value: number) => void
}

export function SplitPanel({
  propertyPanel,
  conversationPanel,
  contextPanel,
  propertyWidth,
  contextWidth,
  onPropertyWidthChange,
  onContextWidthChange,
}: SplitPanelProps) {
  const style = {
    '--property-panel-width': `${propertyWidth}px`,
    '--context-panel-width': `${contextWidth}px`,
  } as CSSProperties

  return (
    <div
      className={`ticket-workspace-grid${contextPanel ? '' : ' context-collapsed'}`}
      style={style}
    >
      {propertyPanel}
      <ResizeHandle
        label="속성 패널 너비 조절"
        value={propertyWidth}
        minimum={240}
        maximum={420}
        direction={1}
        onChange={onPropertyWidthChange}
      />
      {conversationPanel}
      {contextPanel ? (
        <>
          <ResizeHandle
            label="컨텍스트 패널 너비 조절"
            value={contextWidth}
            minimum={240}
            maximum={520}
            direction={-1}
            onChange={onContextWidthChange}
          />
          {contextPanel}
        </>
      ) : null}
    </div>
  )
}

interface ResizeHandleProps {
  label: string
  value: number
  minimum: number
  maximum: number
  direction: 1 | -1
  onChange: (value: number) => void
}

function ResizeHandle({
  label,
  value,
  minimum,
  maximum,
  direction,
  onChange,
}: ResizeHandleProps) {
  const dragStart = useRef<{ x: number; width: number } | null>(null)

  const keyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
    event.preventDefault()
    const delta = event.key === 'ArrowRight' ? 16 : -16
    onChange(value + delta * direction)
  }

  const pointerDown = (event: PointerEvent<HTMLDivElement>) => {
    dragStart.current = { x: event.clientX, width: value }
    event.currentTarget.setPointerCapture(event.pointerId)
  }

  const pointerMove = (event: PointerEvent<HTMLDivElement>) => {
    if (
      !dragStart.current ||
      !event.currentTarget.hasPointerCapture(event.pointerId)
    )
      return
    onChange(
      dragStart.current.width +
        (event.clientX - dragStart.current.x) * direction,
    )
  }

  const pointerUp = (event: PointerEvent<HTMLDivElement>) => {
    dragStart.current = null
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId)
    }
  }

  return (
    <div
      className="workspace-resizer"
      role="separator"
      aria-label={label}
      aria-orientation="vertical"
      aria-valuemin={minimum}
      aria-valuemax={maximum}
      aria-valuenow={value}
      tabIndex={0}
      onKeyDown={keyDown}
      onPointerDown={pointerDown}
      onPointerMove={pointerMove}
      onPointerUp={pointerUp}
    />
  )
}
