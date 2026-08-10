import { useRef, type KeyboardEvent, type PointerEvent } from 'react'

interface PanelResizerProps {
  label: string
  value: number
  minimum: number
  maximum: number
  direction: 1 | -1
  onChange: (value: number) => void
}

export function PanelResizer({
  label,
  value,
  minimum,
  maximum,
  direction,
  onChange,
}: PanelResizerProps) {
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
