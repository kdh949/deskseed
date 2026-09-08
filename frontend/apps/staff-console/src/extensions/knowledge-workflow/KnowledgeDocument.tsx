import {
  SeedButton,
  SeedSelectField,
  SeedTextAreaField,
  SeedTextField,
} from '../../design-system/canonical'
import { safeLink, type Block, type Document } from './api'
const TYPES = {
  paragraph: '문단',
  heading: '제목',
  list: '목록',
  code: '코드',
  callout: '안내',
  quote: '인용',
  divider: '구분선',
  link: '링크',
  attachment: '첨부파일',
}
export function KnowledgeDocument({ document }: { document: Document }) {
  return (
    <div className="knowledge-document">
      {document.blocks.map((b, i) => {
        switch (b.type) {
          case 'paragraph':
            return <p key={i}>{b.text}</p>
          case 'heading':
            return b.level === 3 ? (
              <h3 key={i}>{b.text}</h3>
            ) : (
              <h2 key={i}>{b.text}</h2>
            )
          case 'list':
            return b.ordered ? (
              <ol key={i}>
                {b.items?.map((s, j) => (
                  <li key={j}>{s}</li>
                ))}
              </ol>
            ) : (
              <ul key={i}>
                {b.items?.map((s, j) => (
                  <li key={j}>{s}</li>
                ))}
              </ul>
            )
          case 'code':
            return (
              <pre key={i}>
                <code>{b.text}</code>
              </pre>
            )
          case 'callout':
          case 'quote':
            return <blockquote key={i}>{b.text}</blockquote>
          case 'divider':
            return <hr key={i} />
          case 'link':
            return safeLink(b.url ?? '') ? (
              <p key={i}>
                <a href={b.url} target="_blank" rel="noopener noreferrer">
                  {b.text}
                </a>
              </p>
            ) : (
              <p key={i}>{b.text}</p>
            )
          case 'attachment':
            return <p key={i}>첨부파일 문서 참조</p>
        }
      })}
    </div>
  )
}
export function KnowledgeBlockEditor({
  document,
  onChange,
  disabled,
}: {
  document: Document
  onChange: (next: Document) => void
  disabled?: boolean
}) {
  const set = (blocks: Block[]) => onChange({ schemaVersion: 1, blocks })
  const change = (index: number, value: Block) =>
    set(document.blocks.map((b, i) => (i === index ? value : b)))
  return (
    <fieldset disabled={disabled} className="knowledge-blocks">
      <legend>문서 내용</legend>
      {document.blocks.map((b, i) => (
        <fieldset key={i}>
          <legend>블록 {i + 1}</legend>
          <SeedSelectField
            label={`블록 ${i + 1} 종류`}
            value={b.type}
            disabled={b.type === 'attachment'}
            onChange={(e) => {
              const type = e.target.value as Block['type']
              change(
                i,
                type === 'list'
                  ? { type, ordered: false, items: [b.text ?? ''] }
                  : type === 'divider'
                    ? { type }
                    : type === 'heading'
                      ? { type, level: 2, text: b.text ?? '' }
                      : type === 'link'
                        ? { type, text: b.text ?? '', url: '' }
                        : { type, text: b.text ?? '' },
              )
            }}
          >
            {Object.entries(TYPES)
              .filter(
                ([type]) => type !== 'attachment' || b.type === 'attachment',
              )
              .map(([type, label]) => (
                <option key={type} value={type}>
                  {label}
                </option>
              ))}
          </SeedSelectField>
          {b.type === 'heading' && (
            <SeedSelectField
              label={`블록 ${i + 1} 제목 크기`}
              value={b.level}
              onChange={(e) =>
                change(i, { ...b, level: Number(e.target.value) })
              }
            >
              <option value={2}>큰 제목</option>
              <option value={3}>작은 제목</option>
            </SeedSelectField>
          )}
          {b.type === 'list' ? (
            <>
              <SeedSelectField
                label={`블록 ${i + 1} 목록 형식`}
                value={b.ordered ? 'ordered' : 'bullet'}
                onChange={(e) =>
                  change(i, { ...b, ordered: e.target.value === 'ordered' })
                }
              >
                <option value="bullet">글머리표</option>
                <option value="ordered">번호</option>
              </SeedSelectField>
              <SeedTextAreaField
                label={`블록 ${i + 1} 항목 (한 줄에 하나)`}
                value={b.items?.join('\n') ?? ''}
                onChange={(e) =>
                  change(i, { ...b, items: e.target.value.split('\n') })
                }
              />
            </>
          ) : (
            b.type !== 'divider' &&
            b.type !== 'attachment' && (
              <SeedTextAreaField
                label={`블록 ${i + 1} 내용`}
                required
                maxLength={10000}
                value={b.text ?? ''}
                onChange={(e) =>
                  change(i, {
                    ...b,
                    text: e.target.value.replace(/[\r\n]/g, ' '),
                  })
                }
              />
            )
          )}
          {b.type === 'link' && (
            <SeedTextField
              label={`블록 ${i + 1} 링크 주소`}
              type="url"
              required
              value={b.url ?? ''}
              onChange={(e) => change(i, { ...b, url: e.target.value })}
            />
          )}
          {b.type === 'attachment' && <p>기존 첨부파일 참조를 유지합니다.</p>}
          <div className="knowledge-actions">
            <SeedButton
              disabled={i === 0}
              onClick={() => {
                const blocks = [...document.blocks]
                ;[blocks[i - 1], blocks[i]] = [blocks[i]!, blocks[i - 1]!]
                set(blocks)
              }}
            >
              위로: 블록 {i + 1}
            </SeedButton>
            <SeedButton
              disabled={document.blocks.length === 1}
              onClick={() => set(document.blocks.filter((_, j) => j !== i))}
            >
              삭제: 블록 {i + 1}
            </SeedButton>
          </div>
        </fieldset>
      ))}
      <SeedButton
        disabled={document.blocks.length >= 200}
        onClick={() =>
          set([...document.blocks, { type: 'paragraph', text: '' }])
        }
      >
        문단 추가
      </SeedButton>
    </fieldset>
  )
}
