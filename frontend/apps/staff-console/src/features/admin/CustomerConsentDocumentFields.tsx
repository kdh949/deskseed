import { DsButton } from '../../design-system'
import { consentPlainText, type ConsentBlock } from './customerConsentApi'

const blockNames = {
  paragraph: '문단',
  heading: '제목',
  list: '목록',
  callout: '안내',
  quote: '인용',
  divider: '구분선',
  link: '링크',
} as const

export function ConsentDocumentView({ blocks }: { blocks: ConsentBlock[] }) {
  return (
    <div>
      {blocks.map((block, i) =>
        block.type === 'divider' ? (
          <hr key={i} />
        ) : block.type === 'list' ? (
          block.ordered ? (
            <ol key={i}>
              {block.items.map((text, j) => (
                <li key={j}>{text}</li>
              ))}
            </ol>
          ) : (
            <ul key={i}>
              {block.items.map((text, j) => (
                <li key={j}>{text}</li>
              ))}
            </ul>
          )
        ) : block.type === 'link' ? (
          <p key={i}>
            <a href={block.url} rel="noreferrer noopener" target="_blank">
              {block.text}
            </a>
          </p>
        ) : block.type === 'heading' ? (
          block.level === 2 ? (
            <h4 key={i}>{block.text}</h4>
          ) : (
            <h5 key={i}>{block.text}</h5>
          )
        ) : block.type === 'quote' ? (
          <blockquote key={i}>{block.text}</blockquote>
        ) : block.type === 'callout' ? (
          <aside key={i}>{block.text}</aside>
        ) : (
          <p key={i}>{block.text}</p>
        ),
      )}
    </div>
  )
}
export function ConsentDocumentEditor({
  blocks,
  change,
}: {
  blocks: ConsentBlock[]
  change: (blocks: ConsentBlock[]) => void
}) {
  const update = (index: number, block: ConsentBlock) =>
    change(blocks.map((value, i) => (i === index ? block : value)))
  return (
    <div>
      {blocks.map((block, index) => (
        <fieldset className="admin-form" key={index}>
          <legend>문서 항목 {index + 1}</legend>
          <label className="admin-field">
            <span>항목 {index + 1} 종류</span>
            <select
              value={block.type}
              onChange={(event) => {
                const type = event.target.value as ConsentBlock['type']
                update(
                  index,
                  type === 'divider'
                    ? { type }
                    : type === 'list'
                      ? { type, ordered: false, items: [''] }
                      : type === 'heading'
                        ? { type, level: 2, text: '' }
                        : type === 'link'
                          ? { type, text: '', url: '' }
                          : { type, text: '' },
                )
              }}
            >
              {Object.entries(blockNames).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          {block.type === 'list' ? (
            <>
              <label className="admin-field">
                <span>항목 {index + 1} 목록 내용 (줄마다 한 항목)</span>
                <textarea
                  required
                  rows={4}
                  value={block.items.join('\n')}
                  onChange={(event) =>
                    update(index, {
                      ...block,
                      items: event.target.value.split('\n'),
                    })
                  }
                />
              </label>
              <label>
                <input
                  type="checkbox"
                  checked={block.ordered}
                  onChange={(event) =>
                    update(index, { ...block, ordered: event.target.checked })
                  }
                />
                번호 목록
              </label>
            </>
          ) : block.type !== 'divider' ? (
            <label className="admin-field">
              <span>항목 {index + 1} 내용</span>
              <input
                required
                maxLength={10000}
                value={block.text}
                onChange={(event) =>
                  update(index, { ...block, text: event.target.value })
                }
              />
            </label>
          ) : null}
          {block.type === 'heading' && (
            <label className="admin-field">
              <span>제목 단계</span>
              <select
                value={block.level}
                onChange={(event) =>
                  update(index, {
                    ...block,
                    level: Number(event.target.value) as 2 | 3,
                  })
                }
              >
                <option value={2}>큰 제목</option>
                <option value={3}>작은 제목</option>
              </select>
            </label>
          )}
          {block.type === 'link' && (
            <label className="admin-field">
              <span>항목 {index + 1} 링크 주소</span>
              <input
                required
                type="url"
                maxLength={2048}
                pattern="https://.*"
                value={block.url}
                onChange={(event) =>
                  update(index, { ...block, url: event.target.value })
                }
              />
            </label>
          )}
          <DsButton
            disabled={blocks.length === 1}
            onClick={() => change(blocks.filter((_, i) => i !== index))}
          >
            항목 {index + 1} 삭제
          </DsButton>
        </fieldset>
      ))}
      <DsButton
        disabled={blocks.length >= 100}
        onClick={() => change([...blocks, { type: 'paragraph', text: '' }])}
      >
        문서 항목 추가
      </DsButton>
      <p>
        {Array.from(
          consentPlainText({ schemaVersion: 1, blocks }),
        ).length.toLocaleString()}{' '}
        / 50,000자 · 최대 100개 항목. 문단은 비우지 않고 꺾쇠 괄호 없이 작성해
        주세요.
      </p>
    </div>
  )
}
