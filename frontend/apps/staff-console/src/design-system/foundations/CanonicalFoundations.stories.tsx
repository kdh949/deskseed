import type { Meta, StoryObj } from '@storybook/react-vite'
import './canonical-stories.css'

function CanonicalFoundations() {
  const palette = [
    ['Canvas', 'var(--ds-palette-canvas)'],
    ['Paper', 'var(--ds-palette-paper)'],
    ['Chrome', 'var(--ds-palette-chrome)'],
    ['Brand', 'var(--ds-palette-brand)'],
    ['Positive', 'var(--ds-palette-positive)'],
    ['Warning', 'var(--ds-palette-warning)'],
    ['Danger', 'var(--ds-palette-danger)'],
  ]
  return (
    <main className="seed-foundations-catalog">
      <header>
        <h1>Deskseed 상담사 파운데이션</h1>
        <p>
          고밀도 업무 화면을 위한 색상, 타이포그래피, 간격, 크기, radius,
          border, elevation, motion 기준입니다.
        </p>
      </header>
      <section>
        <h2>Color</h2>
        <div className="seed-foundations-catalog__palette">
          {palette.map(([label, color]) => (
            <article key={label}>
              <span style={{ background: color }} />
              <strong>{label}</strong>
              <code>{color}</code>
            </article>
          ))}
        </div>
      </section>
      <section>
        <h2>Typography</h2>
        <div className="seed-foundations-catalog__type">
          <h1>티켓 워크스페이스</h1>
          <h2>고객이 로그인할 수 없습니다</h2>
          <p>상태, 담당자, 대화와 고객 문맥을 같은 화면에서 확인합니다.</p>
          <small>업데이트 2분 전 · 최초 답변 SLA 2시간 14분</small>
        </div>
      </section>
      <section>
        <h2>Spacing · radius · elevation · motion</h2>
        <div className="seed-foundations-catalog__metrics">
          {['050', '100', '150', '200', '300', '400'].map((step) => (
            <span key={step} style={{ width: `var(--ds-space-${step})` }}>
              {step}
            </span>
          ))}
          <button type="button">Focus and motion sample</button>
        </div>
      </section>
    </main>
  )
}

const meta = {
  title: '01 Foundations/Canonical',
  component: CanonicalFoundations,
  parameters: { layout: 'fullscreen' },
  tags: ['autodocs'],
} satisfies Meta<typeof CanonicalFoundations>

export default meta
type Story = StoryObj<typeof meta>

export const Overview: Story = {}
