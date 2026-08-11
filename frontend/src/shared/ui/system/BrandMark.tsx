interface BrandMarkProps {
  compact?: boolean
}

/** Independent Deskseed mark. It intentionally contains no Zendesk artwork. */
export function BrandMark({ compact = false }: BrandMarkProps) {
  return (
    <span
      className={`deskseed-brand-mark${compact ? ' is-compact' : ''}`}
      aria-hidden="true"
    >
      D
    </span>
  )
}
