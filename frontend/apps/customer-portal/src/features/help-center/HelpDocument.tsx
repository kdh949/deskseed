import type { HelpBlock } from './helpDocumentCodec'
export function HelpDocument({ blocks }: { blocks: HelpBlock[] }) {
  return (
    <section aria-label="문서 본문" className="customer-article-body">
      {blocks.map((block, index) => {
        switch (block.type) {
          case 'heading':
            return block.level === 2 ? (
              <h2 key={index}>{block.text}</h2>
            ) : (
              <h3 key={index}>{block.text}</h3>
            )
          case 'list':
            return block.ordered ? (
              <ol key={index}>
                {block.items.map((text, i) => (
                  <li key={i}>{text}</li>
                ))}
              </ol>
            ) : (
              <ul key={index}>
                {block.items.map((text, i) => (
                  <li key={i}>{text}</li>
                ))}
              </ul>
            )
          case 'code':
            return (
              <pre key={index}>
                <code>{block.text}</code>
              </pre>
            )
          case 'quote':
            return <blockquote key={index}>{block.text}</blockquote>
          case 'callout':
            return (
              <aside key={index} aria-label="안내">
                {block.text}
              </aside>
            )
          case 'divider':
            return <hr key={index} />
          case 'link':
            return (
              <p key={index}>
                <a
                  href={block.url}
                  referrerPolicy="no-referrer"
                  rel="noopener noreferrer"
                >
                  {block.text}
                </a>
              </p>
            )
          case 'attachment':
            return (
              <p key={index}>
                이 첨부파일은 고객 도움말에서 내려받을 수 없습니다. 지원팀에
                문의해 주세요.
              </p>
            )
          default:
            return <p key={index}>{block.text}</p>
        }
      })}
    </section>
  )
}
