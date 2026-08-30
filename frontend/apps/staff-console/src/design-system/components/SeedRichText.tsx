import { Node } from '@tiptap/core'
import { EditorContent, useEditor } from '@tiptap/react'
import StarterKit from '@tiptap/starter-kit'
import TextAlign from '@tiptap/extension-text-align'
import {
  forwardRef,
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
} from 'react'
import type {
  RichTextDocumentV1,
  RichTextMarkV1,
  RichTextNodeV1,
} from '../../api/types'
import { SeedButton, SeedIcon, type SeedIconName } from '../primitives/SeedCore'
import { isAllowedEditorLink } from './richTextLink'
import './seed-rich-text.css'

const EMPTY_DOCUMENT: RichTextDocumentV1 = {
  type: 'doc',
  content: [{ type: 'paragraph' }],
}

const AttachmentImage = Node.create<{
  resolvePreviewUrl: (attachmentId: string) => string | null
}>({
  name: 'attachmentImage',
  group: 'block',
  atom: true,
  selectable: true,
  addOptions() {
    return { resolvePreviewUrl: () => null }
  },
  addAttributes() {
    return {
      attachmentId: { default: null },
      alt: { default: '' },
    }
  },
  parseHTML() {
    return [{ tag: 'figure[data-seed-attachment-image]' }]
  },
  renderHTML({ node }) {
    const attachmentId = String(node.attrs.attachmentId ?? '')
    const alt = String(node.attrs.alt ?? '')
    const previewUrl = this.options.resolvePreviewUrl(attachmentId)
    return previewUrl
      ? [
          'figure',
          { 'data-seed-attachment-image': attachmentId },
          ['img', { src: previewUrl, alt }],
          ['figcaption', {}, alt],
        ]
      : [
          'figure',
          { 'data-seed-attachment-image': attachmentId },
          ['span', {}, `첨부 이미지: ${alt}`],
        ]
  },
  renderText({ node }) {
    return String(node.attrs.alt ?? '')
  },
})

export interface SeedRichTextEditorProps {
  ariaLabel: string
  value: RichTextDocumentV1
  onChange: (document: RichTextDocumentV1, plainText: string) => void
  disabled?: boolean
  error?: string
  onUploadImage?: (
    file: File,
  ) => Promise<{ attachmentId: string; alt?: string; previewUrl?: string }>
}

export function SeedRichTextEditor({
  ariaLabel,
  value,
  onChange,
  disabled = false,
  error,
  onUploadImage,
}: SeedRichTextEditorProps) {
  const changeRef = useRef(onChange)
  const previewsRef = useRef(new Map<string, string>())
  const lastEmittedRef = useRef('')
  const fileInputRef = useRef<HTMLInputElement>(null)
  const linkButtonRef = useRef<HTMLButtonElement | null>(null)
  const linkInputRef = useRef<HTMLInputElement>(null)
  const toolbarRefs = useRef<Array<HTMLElement | null>>([])
  const [toolbarIndex, setToolbarIndex] = useState(0)
  const [linkOpen, setLinkOpen] = useState(false)
  const [emojiOpen, setEmojiOpen] = useState(false)
  const [linkValue, setLinkValue] = useState('https://')
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [, setRevision] = useState(0)
  changeRef.current = onChange

  const extensions = useMemo(
    () => [
      StarterKit.configure({
        heading: { levels: [1, 2, 3] },
        link: {
          autolink: false,
          linkOnPaste: true,
          openOnClick: false,
          protocols: ['http', 'https', 'mailto'],
        },
      }),
      TextAlign.configure({
        types: ['heading', 'paragraph'],
        alignments: ['left', 'center', 'right'],
      }),
      AttachmentImage.configure({
        resolvePreviewUrl: (attachmentId) =>
          previewsRef.current.get(attachmentId) ?? null,
      }),
    ],
    [],
  )

  const editor = useEditor({
    extensions,
    content: value,
    editable: !disabled,
    editorProps: {
      attributes: {
        'aria-label': ariaLabel,
        class: 'seed-rich-editor__surface',
        role: 'textbox',
        'aria-multiline': 'true',
      },
    },
    onSelectionUpdate: () => setRevision((current) => current + 1),
    onUpdate: ({ editor: currentEditor }) => {
      const document = canonicalizeEditorDocument(currentEditor.getJSON())
      lastEmittedRef.current = JSON.stringify(document)
      changeRef.current(
        document,
        currentEditor.getText({ blockSeparator: '\n' }).trimEnd(),
      )
      setRevision((current) => current + 1)
    },
  })

  useEffect(() => {
    if (!editor || editor.isDestroyed) return
    editor.setEditable(!disabled)
  }, [disabled, editor])

  useEffect(() => {
    if (!editor || editor.isDestroyed) return
    const serialized = JSON.stringify(value)
    if (
      serialized !== lastEmittedRef.current &&
      serialized !== JSON.stringify(editor.getJSON())
    ) {
      editor.commands.setContent(value, { emitUpdate: false })
    }
  }, [editor, value])

  useEffect(() => {
    if (linkOpen) linkInputRef.current?.focus()
  }, [linkOpen])

  useEffect(
    () => () => {
      for (const url of previewsRef.current.values()) {
        if (url.startsWith('blob:')) URL.revokeObjectURL(url)
      }
    },
    [],
  )

  if (!editor)
    return <div className="seed-rich-editor seed-rich-editor--loading" />

  const registerToolbarItem =
    (index: number) => (element: HTMLElement | null) => {
      toolbarRefs.current[index] = element
    }
  const toolbarTabIndex = (index: number) => (toolbarIndex === index ? 0 : -1)
  const handleToolbarKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    const items = toolbarRefs.current.filter(Boolean) as HTMLElement[]
    if (!items.length) return
    const current = items.indexOf(document.activeElement as HTMLElement)
    const next =
      event.key === 'ArrowRight'
        ? (current + 1) % items.length
        : event.key === 'ArrowLeft'
          ? (current - 1 + items.length) % items.length
          : event.key === 'Home'
            ? 0
            : event.key === 'End'
              ? items.length - 1
              : null
    if (next === null) return
    event.preventDefault()
    setToolbarIndex(next)
    items[next]?.focus()
  }
  const closeLink = () => {
    setLinkOpen(false)
    linkButtonRef.current?.focus()
  }
  const applyLink = () => {
    const href = linkValue.trim()
    if (!isAllowedEditorLink(href)) {
      setUploadError(
        '링크는 http, https 또는 mailto 주소만 사용할 수 있습니다.',
      )
      return
    }
    editor.chain().focus().extendMarkRange('link').setLink({ href }).run()
    setUploadError(null)
    closeLink()
  }

  return (
    <div
      className={`seed-rich-editor${error ? ' seed-rich-editor--invalid' : ''}`}
    >
      <div
        aria-label="서식 도구"
        className="seed-rich-editor__toolbar"
        onKeyDown={handleToolbarKeyDown}
        role="toolbar"
      >
        <select
          aria-label="문단 스타일"
          className="seed-rich-editor__block-select"
          disabled={disabled}
          onChange={(event) => {
            const next = event.target.value
            if (next === 'paragraph')
              editor.chain().focus().setParagraph().run()
            else
              editor
                .chain()
                .focus()
                .toggleHeading({ level: Number(next) as 1 | 2 | 3 })
                .run()
          }}
          onFocus={() => setToolbarIndex(0)}
          ref={registerToolbarItem(0)}
          tabIndex={toolbarTabIndex(0)}
          value={
            editor.isActive('heading', { level: 1 })
              ? '1'
              : editor.isActive('heading', { level: 2 })
                ? '2'
                : editor.isActive('heading', { level: 3 })
                  ? '3'
                  : 'paragraph'
          }
        >
          <option value="paragraph">Paragraph</option>
          <option value="1">Heading 1</option>
          <option value="2">Heading 2</option>
          <option value="3">Heading 3</option>
        </select>
        <span aria-hidden="true" className="seed-rich-editor__separator" />
        <EditorTool
          active={editor.isActive('bold')}
          disabled={disabled}
          icon="bold"
          index={1}
          label="굵게 (⌘B)"
          onActivate={() => editor.chain().focus().toggleBold().run()}
          onFocus={setToolbarIndex}
          ref={registerToolbarItem(1)}
          tabIndex={toolbarTabIndex(1)}
        />
        <EditorTool
          active={editor.isActive('italic')}
          disabled={disabled}
          icon="italic"
          index={2}
          label="기울임 (⌘I)"
          onActivate={() => editor.chain().focus().toggleItalic().run()}
          onFocus={setToolbarIndex}
          ref={registerToolbarItem(2)}
          tabIndex={toolbarTabIndex(2)}
        />
        <EditorTool
          active={editor.isActive('underline')}
          disabled={disabled}
          icon="underline"
          index={3}
          label="밑줄 (⌘U)"
          onActivate={() => editor.chain().focus().toggleUnderline().run()}
          onFocus={setToolbarIndex}
          ref={registerToolbarItem(3)}
          tabIndex={toolbarTabIndex(3)}
        />
        <span aria-hidden="true" className="seed-rich-editor__separator" />
        <EditorTool
          active={editor.isActive('bulletList')}
          disabled={disabled}
          icon="list-bullet"
          index={4}
          label="글머리 기호 목록"
          onActivate={() => editor.chain().focus().toggleBulletList().run()}
          onFocus={setToolbarIndex}
          ref={registerToolbarItem(4)}
          tabIndex={toolbarTabIndex(4)}
        />
        <EditorTool
          active={editor.isActive('orderedList')}
          disabled={disabled}
          icon="list-number"
          index={5}
          label="번호 매기기 목록"
          onActivate={() => editor.chain().focus().toggleOrderedList().run()}
          onFocus={setToolbarIndex}
          ref={registerToolbarItem(5)}
          tabIndex={toolbarTabIndex(5)}
        />
        <EditorTool
          active={editor.isActive({ textAlign: 'left' })}
          disabled={disabled}
          icon="align-left"
          index={6}
          label="왼쪽 정렬"
          onActivate={() => editor.chain().focus().setTextAlign('left').run()}
          onFocus={setToolbarIndex}
          ref={registerToolbarItem(6)}
          tabIndex={toolbarTabIndex(6)}
        />
        <EditorTool
          active={editor.isActive({ textAlign: 'center' })}
          disabled={disabled}
          icon="align-center"
          index={7}
          label="가운데 정렬"
          onActivate={() => editor.chain().focus().setTextAlign('center').run()}
          onFocus={setToolbarIndex}
          ref={registerToolbarItem(7)}
          tabIndex={toolbarTabIndex(7)}
        />
        <EditorTool
          active={editor.isActive({ textAlign: 'right' })}
          disabled={disabled}
          icon="align-right"
          index={8}
          label="오른쪽 정렬"
          onActivate={() => editor.chain().focus().setTextAlign('right').run()}
          onFocus={setToolbarIndex}
          ref={registerToolbarItem(8)}
          tabIndex={toolbarTabIndex(8)}
        />
        <span aria-hidden="true" className="seed-rich-editor__separator" />
        <span className="seed-rich-editor__popover-anchor">
          <EditorTool
            active={editor.isActive('link') || linkOpen}
            disabled={disabled}
            icon="external"
            index={9}
            label="링크 삽입"
            onActivate={() => setLinkOpen((open) => !open)}
            onFocus={setToolbarIndex}
            ref={(element) => {
              registerToolbarItem(9)(element)
              linkButtonRef.current = element
            }}
            tabIndex={toolbarTabIndex(9)}
          />
          {linkOpen && (
            <div
              aria-label="링크 삽입"
              className="seed-rich-editor__popover"
              role="dialog"
            >
              <label>
                <span>링크 주소</span>
                <input
                  ref={linkInputRef}
                  value={linkValue}
                  onChange={(event) => setLinkValue(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === 'Escape') closeLink()
                    if (event.key === 'Enter') {
                      event.preventDefault()
                      applyLink()
                    }
                  }}
                />
              </label>
              <div>
                <SeedButton size="compact" onClick={closeLink}>
                  취소
                </SeedButton>
                <SeedButton
                  size="compact"
                  variant="primary"
                  onClick={applyLink}
                >
                  적용
                </SeedButton>
              </div>
            </div>
          )}
        </span>
        <EditorTool
          active={false}
          disabled={disabled || !onUploadImage}
          icon="image"
          index={10}
          label="첨부 이미지 삽입"
          onActivate={() => fileInputRef.current?.click()}
          onFocus={setToolbarIndex}
          ref={registerToolbarItem(10)}
          tabIndex={toolbarTabIndex(10)}
        />
        <span className="seed-rich-editor__popover-anchor">
          <EditorTool
            active={emojiOpen}
            disabled={disabled}
            icon="smile"
            index={11}
            label="이모지 삽입"
            onActivate={() => setEmojiOpen((open) => !open)}
            onFocus={setToolbarIndex}
            ref={registerToolbarItem(11)}
            tabIndex={toolbarTabIndex(11)}
          />
          {emojiOpen && (
            <div
              aria-label="이모지 선택"
              className="seed-rich-editor__emoji-menu"
              role="menu"
            >
              {['🙂', '👍', '✅', '🎉', '🙏', '💡'].map((emoji) => (
                <button
                  key={emoji}
                  onClick={() => {
                    editor.chain().focus().insertContent(emoji).run()
                    setEmojiOpen(false)
                  }}
                  role="menuitem"
                  type="button"
                >
                  {emoji}
                </button>
              ))}
            </div>
          )}
        </span>
        <EditorTool
          active={editor.isActive('code')}
          disabled={disabled}
          icon="text"
          index={12}
          label="인라인 코드"
          onActivate={() => editor.chain().focus().toggleCode().run()}
          onFocus={setToolbarIndex}
          ref={registerToolbarItem(12)}
          tabIndex={toolbarTabIndex(12)}
        />
        <EditorTool
          active={editor.isActive('blockquote')}
          disabled={disabled}
          icon="quote"
          index={13}
          label="인용"
          onActivate={() => editor.chain().focus().toggleBlockquote().run()}
          onFocus={setToolbarIndex}
          ref={registerToolbarItem(13)}
          tabIndex={toolbarTabIndex(13)}
        />
        <EditorTool
          active={editor.isActive('codeBlock')}
          disabled={disabled}
          icon="more"
          index={14}
          label="코드 블록"
          onActivate={() => editor.chain().focus().toggleCodeBlock().run()}
          onFocus={setToolbarIndex}
          ref={registerToolbarItem(14)}
          tabIndex={toolbarTabIndex(14)}
        />
      </div>
      <EditorContent editor={editor} />
      <input
        accept="image/png,image/jpeg,image/gif,image/webp"
        aria-label="첨부 이미지 파일"
        className="seed-rich-editor__file-input"
        onChange={async (event) => {
          const file = event.target.files?.[0]
          event.target.value = ''
          if (!file || !onUploadImage) return
          try {
            const upload = await onUploadImage(file)
            const previewUrl = upload.previewUrl ?? URL.createObjectURL(file)
            previewsRef.current.set(upload.attachmentId, previewUrl)
            editor.commands.insertContent({
              type: 'attachmentImage',
              attrs: {
                attachmentId: upload.attachmentId,
                alt: upload.alt ?? file.name,
              },
            })
            setUploadError(null)
          } catch {
            setUploadError(
              '이미지를 첨부하지 못했습니다. 파일을 확인하고 다시 시도해 주세요.',
            )
          }
        }}
        ref={fileInputRef}
        tabIndex={-1}
        type="file"
      />
      {(error || uploadError) && (
        <p className="seed-rich-editor__error" role="alert">
          {error ?? uploadError}
        </p>
      )}
    </div>
  )
}

function canonicalizeEditorDocument(value: unknown): RichTextDocumentV1 {
  const source = value as { content?: unknown[] }
  return {
    type: 'doc',
    content: (source.content ?? []).map(canonicalizeEditorNode),
  }
}

function canonicalizeEditorNode(value: unknown): RichTextNodeV1 {
  const source = value as {
    type?: RichTextNodeV1['type']
    attrs?: Record<string, unknown>
    content?: unknown[]
    text?: unknown
    marks?: Array<{
      type?: RichTextMarkV1['type']
      attrs?: Record<string, unknown>
    }>
  }
  const type = source.type ?? 'paragraph'
  const attrs: NonNullable<RichTextNodeV1['attrs']> = {}
  if (type === 'heading' && [1, 2, 3].includes(Number(source.attrs?.level))) {
    attrs.level = Number(source.attrs?.level) as 1 | 2 | 3
  }
  if (
    (type === 'paragraph' || type === 'heading') &&
    ['left', 'center', 'right'].includes(String(source.attrs?.textAlign))
  ) {
    attrs.textAlign = source.attrs?.textAlign as 'left' | 'center' | 'right'
  }
  if (type === 'attachmentImage') {
    attrs.attachmentId = String(source.attrs?.attachmentId ?? '')
    attrs.alt = String(source.attrs?.alt ?? '')
  }
  const marks = source.marks?.flatMap((mark): RichTextMarkV1[] => {
    if (mark.type === 'link') {
      const href = String(mark.attrs?.href ?? '')
      return href ? [{ type: 'link', attrs: { href } }] : []
    }
    if (
      mark.type &&
      ['bold', 'italic', 'underline', 'code'].includes(mark.type)
    ) {
      return [{ type: mark.type as 'bold' | 'italic' | 'underline' | 'code' }]
    }
    return []
  })
  return {
    type,
    ...(Object.keys(attrs).length ? { attrs } : {}),
    ...(Array.isArray(source.content)
      ? { content: source.content.map(canonicalizeEditorNode) }
      : {}),
    ...(typeof source.text === 'string' ? { text: source.text } : {}),
    ...(marks?.length ? { marks } : {}),
  }
}

const EditorTool = forwardRef<
  HTMLButtonElement,
  {
    active: boolean
    disabled: boolean
    icon: SeedIconName
    index: number
    label: string
    onActivate: () => void
    onFocus: (index: number) => void
    tabIndex: number
  }
>(function EditorTool(
  {
    active,
    disabled,
    icon,
    index,
    label,
    onActivate,
    onFocus,
    tabIndex,
  }: {
    active: boolean
    disabled: boolean
    icon: SeedIconName
    index: number
    label: string
    onActivate: () => void
    onFocus: (index: number) => void
    tabIndex: number
  },
  ref,
) {
  return (
    <button
      aria-label={label}
      aria-pressed={active}
      className="seed-rich-editor__tool"
      disabled={disabled}
      onClick={onActivate}
      onFocus={() => onFocus(index)}
      ref={ref}
      tabIndex={tabIndex}
      title={label}
      type="button"
    >
      <SeedIcon name={icon} size="small" />
    </button>
  )
})

export { EMPTY_DOCUMENT as seedEmptyRichTextDocument }
