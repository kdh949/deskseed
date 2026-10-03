import { useRef } from 'react'

/** Controlled horizontal width; direction describes which side owns the panel. */
export function SeedPanelResizeHandle({
  label,
  value,
  min,
  max,
  direction = 'left',
  onChange,
}: {
  label: string
  value: number
  min: number
  max: number
  direction?: 'left' | 'right'
  onChange: (width: number) => void
}) {
  const drag = useRef<{ x: number; width: number } | null>(null)
  const sign = direction === 'left' ? 1 : -1
  const change = (width: number) =>
    onChange(Math.max(min, Math.min(max, Math.round(width))))
  return (
    <div
      aria-label={label}
      aria-orientation="vertical"
      aria-valuemax={max}
      aria-valuemin={min}
      aria-valuenow={value}
      aria-valuetext={`${value}픽셀`}
      className={`seed-panel-resize seed-panel-resize--${direction}`}
      onKeyDown={(event) => {
        const step = event.shiftKey ? 1 : 10
        const next =
          event.key === 'Home'
            ? min
            : event.key === 'End'
              ? max
              : event.key === 'ArrowLeft'
                ? value - step * sign
                : event.key === 'ArrowRight'
                  ? value + step * sign
                  : null
        if (next === null) return
        event.preventDefault()
        change(next)
      }}
      onPointerDown={(event) => {
        if (event.button !== 0) return
        event.preventDefault()
        event.currentTarget.focus()
        event.currentTarget.setPointerCapture(event.pointerId)
        drag.current = { x: event.clientX, width: value }
      }}
      onPointerMove={(event) => {
        if (!drag.current) return
        change(drag.current.width + (event.clientX - drag.current.x) * sign)
      }}
      onPointerUp={(event) => {
        drag.current = null
        if (event.currentTarget.hasPointerCapture(event.pointerId))
          event.currentTarget.releasePointerCapture(event.pointerId)
      }}
      onPointerCancel={() => {
        drag.current = null
      }}
      onLostPointerCapture={() => {
        drag.current = null
      }}
      role="separator"
      tabIndex={0}
      title={`${label}: 방향키 또는 끌어서 조절`}
    />
  )
}
