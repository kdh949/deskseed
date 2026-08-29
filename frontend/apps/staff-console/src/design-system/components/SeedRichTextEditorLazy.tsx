import { lazy, Suspense } from 'react'
import type { SeedRichTextEditorProps } from './SeedRichText'

const RichTextEditorImplementation = lazy(() =>
  import('./SeedRichText').then((module) => ({
    default: module.SeedRichTextEditor,
  })),
)

export function SeedRichTextEditor(props: SeedRichTextEditorProps) {
  return (
    <Suspense
      fallback={
        <div
          aria-label={`${props.ariaLabel} 불러오는 중`}
          className="seed-rich-editor seed-rich-editor--loading"
          role="status"
        />
      }
    >
      <RichTextEditorImplementation {...props} />
    </Suspense>
  )
}

export type { SeedRichTextEditorProps }
