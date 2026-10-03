export type ConsentBlock =
  | { type: 'paragraph' | 'quote' | 'callout'; text: string }
  | { type: 'heading'; level: 2 | 3; text: string }
  | { type: 'list'; ordered: boolean; items: string[] }
  | { type: 'divider' }
  | { type: 'link'; text: string; url: string }

/** Read-only, validated consent blocks. Never accepts HTML or fetches link targets. */
export function ConsentDocument({ blocks }: { blocks: ConsentBlock[] }) {
  return (
    <div className="customer-article-body">
      {blocks.map((block, index) => {
        switch (block.type) {
          case 'heading':
            return block.level === 2 ? (
              <h2 key={index}>{block.text}</h2>
            ) : (
              <h3 key={index}>{block.text}</h3>
            )
          case 'list': {
            const List = block.ordered ? 'ol' : 'ul'
            return (
              <List key={index}>
                {block.items.map((item, i) => (
                  <li key={i}>{item}</li>
                ))}
              </List>
            )
          }
          case 'divider':
            return <hr key={index} />
          case 'quote':
            return <blockquote key={index}>{block.text}</blockquote>
          case 'callout':
            return (
              <aside key={index} aria-label="안내">
                {block.text}
              </aside>
            )
          case 'link':
            return (
              <p key={index}>
                <a
                  href={block.url}
                  rel="noopener noreferrer"
                  referrerPolicy="no-referrer"
                >
                  {block.text}
                </a>
              </p>
            )
          case 'paragraph':
            return <p key={index}>{block.text}</p>
        }
      })}
    </div>
  )
}
