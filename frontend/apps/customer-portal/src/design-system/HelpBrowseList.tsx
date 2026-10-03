import { Link } from 'react-router'

/** A semantic list of Help Center destinations. The caller owns data and state. */
export function HelpBrowseList({
  items,
  ariaLabel,
}: {
  items: Array<{ to: string; title: string; description?: string }>
  ariaLabel: string
}) {
  return (
    <ul className="customer-browse-list" aria-label={ariaLabel}>
      {items.map((item) => (
        <li key={item.to}>
          <Link to={item.to} aria-label={item.title}>
            <strong>{item.title}</strong>
            {item.description && <p>{item.description}</p>}
          </Link>
        </li>
      ))}
    </ul>
  )
}
