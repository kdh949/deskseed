import type { Meta, StoryObj } from '@storybook/react-vite'
import { DeskseedBrandMark } from '../primitives/DeskseedPrimitives'
import { DeskseedIcon, type IconName } from '../primitives/DeskseedIcon'
import './Foundations.stories.css'

const meta = {
  title: '01 Foundations',
  parameters: {
    docs: {
      description: {
        component:
          'Deskseed 제품 UI가 공유하는 foundations를 확인한다. 제품 화면은 semantic color와 public component contract를 사용하고 `--ds-ref-*` 값은 디자인 시스템 구현 내부에서만 사용한다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta

export default meta
type Story = StoryObj<typeof meta>

const semanticColors = [
  ['--ds-background-default', '기본 제품 배경'],
  ['--ds-background-subtle', '구분이 필요한 보조 배경'],
  ['--ds-background-selected', '선택된 행과 탐색 항목'],
  ['--ds-background-internal', 'INTERNAL 메모 배경'],
  ['--ds-background-danger', '오류와 위험 배경'],
  ['--ds-foreground-default', '기본 텍스트'],
  ['--ds-foreground-subtle', '보조 텍스트'],
  ['--ds-foreground-link', '탐색 링크'],
  ['--ds-foreground-primary', '주요 강조 텍스트'],
  ['--ds-foreground-success', '성공 상태'],
  ['--ds-foreground-warning', '경고 상태'],
  ['--ds-foreground-danger', '오류 상태'],
  ['--ds-border-default', '기본 구분선'],
  ['--ds-border-focus', '키보드 포커스'],
  ['--ds-action-primary-default', '현재 화면의 주 행동'],
  ['--ds-background-chrome', 'Agent Workspace chrome'],
] as const

export const Colors: Story = {
  parameters: {
    docs: {
      description: {
        story:
          '화면과 feature CSS는 이 semantic role을 사용한다. raw reference color를 직접 소비하거나 상태를 색상만으로 표현하지 않는다.',
      },
    },
  },
  render: () => (
    <FoundationPage
      description="제품 의미에 따라 선택하는 semantic color입니다."
      title="Colors"
    >
      <div className="ds-foundation-grid">
        {semanticColors.map(([token, usage]) => (
          <article className="ds-foundation-swatch" key={token}>
            <span
              aria-hidden="true"
              className="ds-foundation-swatch-color"
              style={{ background: `var(${token})` }}
            />
            <strong>{usage}</strong>
            <code className="ds-foundation-token-name">{token}</code>
          </article>
        ))}
      </div>
    </FoundationPage>
  ),
}

const typography = [
  ['Metadata', '--ds-ref-font-size-xs', '--ds-ref-line-height-xs'],
  ['Body', '--ds-ref-font-size-sm', '--ds-ref-line-height-sm'],
  ['Emphasis', '--ds-ref-font-size-md', '--ds-ref-line-height-md'],
  ['Ticket subject', '--ds-ref-font-size-lg', '--ds-ref-line-height-md'],
  ['Page title', '--ds-ref-font-size-xl', '--ds-ref-line-height-lg'],
] as const

export const Typography: Story = {
  render: () => (
    <FoundationPage
      description="고밀도 업무 UI의 정보 계층을 유지하는 현재 type scale입니다."
      title="Typography"
    >
      <div className="ds-foundation-type-scale">
        {typography.map(([label, size, lineHeight]) => (
          <article className="ds-foundation-sample" key={label}>
            <span
              style={{
                fontSize: `var(${size})`,
                lineHeight: `var(${lineHeight})`,
              }}
            >
              {label} — 결제 승인 오류를 확인하고 있습니다
            </span>
            <code className="ds-foundation-token-name">
              {size} / {lineHeight}
            </code>
          </article>
        ))}
      </div>
    </FoundationPage>
  ),
}

const spaces = [1, 2, 3, 4, 5, 6, 8, 10, 12, 16] as const

export const Spacing: Story = {
  parameters: {
    docs: {
      description: {
        story:
          'Spacing reference는 디자인 시스템 구현에서 component contract를 구성한다. feature는 임의 값을 만들지 말고 기존 component와 layout pattern을 우선 사용한다.',
      },
    },
  },
  render: () => (
    <FoundationPage
      description="4px 기반의 canonical spacing scale입니다."
      title="Spacing"
    >
      <div className="ds-foundation-measure-list">
        {spaces.map((space) => (
          <div className="ds-foundation-measure-row" key={space}>
            <code className="ds-foundation-token-name">
              --ds-ref-space-{space}
            </code>
            <span
              className="ds-foundation-measure"
              style={{ width: `var(--ds-ref-space-${space})` }}
            />
          </div>
        ))}
      </div>
    </FoundationPage>
  ),
}

const radii = [
  ['Control', '--ds-ref-radius-control'],
  ['Container', '--ds-ref-radius-container'],
  ['Full', '--ds-ref-radius-full'],
] as const

export const Radius: Story = {
  render: () => (
    <FoundationPage
      description="Control, container, avatar 역할에 맞춰 사용하는 compact radius입니다."
      title="Radius"
    >
      <div className="ds-foundation-radius-list">
        {radii.map(([label, token]) => (
          <div className="ds-foundation-radius-row" key={token}>
            <span
              aria-hidden="true"
              className="ds-foundation-radius-shape"
              style={{ borderRadius: `var(${token})` }}
            />
            <span>
              <strong>{label}</strong>
              <br />
              <code className="ds-foundation-token-name">{token}</code>
            </span>
          </div>
        ))}
      </div>
    </FoundationPage>
  ),
}

export const ControlSizes: Story = {
  render: () => (
    <FoundationPage
      description="Dense 업무 화면의 control height입니다. component public API가 크기를 결정합니다."
      title="Control sizes"
    >
      <div className="ds-foundation-control-list">
        {[
          ['Compact', '--ds-ref-control-compact'],
          ['Default', '--ds-ref-control-default'],
        ].map(([label, token]) => (
          <div className="ds-foundation-control-row" key={token}>
            <span
              className="ds-foundation-control-shape"
              style={{ height: `var(${token})` }}
            >
              {label} control
            </span>
            <code className="ds-foundation-token-name">{token}</code>
          </div>
        ))}
      </div>
    </FoundationPage>
  ),
}

export const Icons: Story = {
  parameters: {
    docs: {
      description: {
        story:
          'DeskseedIcon이 지원하는 전체 이름이다. 장식 아이콘은 aria-hidden이며, 단독 interaction에는 DsIconButton의 label을 사용한다.',
      },
    },
  },
  render: () => (
    <FoundationPage
      description="새 SVG나 별도 아이콘 라이브러리를 추가하기 전에 이 목록을 검색합니다."
      title="Icons"
    >
      <div className="ds-foundation-icon-grid">
        {iconNames.map((name) => (
          <article className="ds-foundation-icon-card" key={name}>
            <DeskseedIcon name={name} />
            <code className="ds-foundation-token-name">{name}</code>
          </article>
        ))}
      </div>
    </FoundationPage>
  ),
}

export const Brand: Story = {
  parameters: {
    docs: {
      description: {
        story:
          'Deskseed 제품 chrome에서만 사용하는 독립 브랜드 자산이다. Zendesk 로고, wordmark, screenshot 또는 proprietary asset을 대체재로 사용하지 않는다.',
      },
    },
  },
  render: () => (
    <FoundationPage
      description="Deskseed-owned brand mark의 현재 지원 크기와 transparent variant입니다."
      title="Brand"
    >
      <div className="ds-foundation-brand-list">
        {(['sm', 'md', 'lg'] as const).map((size) => (
          <div className="ds-foundation-brand-row" key={size}>
            <DeskseedBrandMark size={size} />
            <code className="ds-foundation-token-name">size: {size}</code>
          </div>
        ))}
        <div
          className="ds-foundation-brand-row"
          style={{
            background: 'var(--ds-background-chrome)',
            padding: 'var(--ds-ref-space-3)',
          }}
        >
          <DeskseedBrandMark transparent />
          <code
            className="ds-foundation-token-name"
            style={{ color: 'var(--ds-foreground-inverse)' }}
          >
            transparent chrome variant
          </code>
        </div>
      </div>
    </FoundationPage>
  ),
}

function FoundationPage({
  children,
  description,
  title,
}: {
  children: React.ReactNode
  description: string
  title: string
}) {
  return (
    <main className="ds-foundation-page">
      <header>
        <h1>{title}</h1>
        <p>{description}</p>
      </header>
      {children}
    </main>
  )
}

const iconNames: IconName[] = [
  'adjust',
  'alertWarning',
  'arrowLeft',
  'bookClosed',
  'bookmark',
  'calendar',
  'checkCircle',
  'chevronDown',
  'chevronDoubleLeft',
  'circle',
  'clock',
  'download',
  'eye',
  'gear',
  'grid',
  'history',
  'home',
  'inbox',
  'info',
  'link',
  'lock',
  'notification',
  'overflow',
  'paperclip',
  'pause',
  'pencil',
  'plus',
  'reload',
  'search',
  'smiley',
  'sort',
  'speechBubble',
  'star',
  'userGroup',
  'x',
]
