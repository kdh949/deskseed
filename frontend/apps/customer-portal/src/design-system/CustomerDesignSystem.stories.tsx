import type { Meta, StoryObj } from '@storybook/react-vite'
import { CustomerIcon } from './CustomerIcon'
import {
  CustomerBrand,
  DsButton,
  DsStatusIndicator,
  Notification,
  ScreenState,
} from './CustomerPrimitives'

const meta = {
  title: 'Customer Design System/Foundations',
  component: CustomerBrand,
  parameters: {
    docs: {
      description: {
        component:
          '고객 포털 전용 브랜드, 컨트롤, 상태 및 피드백 계약입니다. 상담사 디자인 토큰이나 컴포넌트를 참조하지 않습니다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof CustomerBrand>

export default meta
type Story = StoryObj<typeof meta>

export const Primitives: Story = {
  render: () => (
    <div className="customer-panel">
      <CustomerBrand />
      <div className="customer-success-actions">
        <DsButton icon="plus" tone="primary">
          기본 작업
        </DsButton>
        <DsButton icon="book">보조 작업</DsButton>
        <DsButton icon="reload" tone="ghost">
          텍스트 작업
        </DsButton>
        <DsButton icon="alert" tone="danger">
          위험 작업
        </DsButton>
      </div>
      <div className="customer-success-actions">
        <DsStatusIndicator tone="open">접수됨</DsStatusIndicator>
        <DsStatusIndicator tone="pending">답변 대기</DsStatusIndicator>
        <DsStatusIndicator tone="solved">해결됨</DsStatusIndicator>
      </div>
      <div className="customer-success-actions">
        {(
          ['search', 'book', 'inbox', 'speechBubble', 'user', 'lock'] as const
        ).map((name) => (
          <CustomerIcon key={name} name={name} size="lg" />
        ))}
      </div>
    </div>
  ),
}

export const FeedbackAndStates: Story = {
  render: () => (
    <div className="customer-page">
      <Notification title="문의가 저장되었습니다." tone="success">
        <p>고객에게 공개되는 답변만 표시됩니다.</p>
      </Notification>
      <Notification title="새로고침이 필요합니다." tone="conflict">
        <p>최신 상태를 확인한 뒤 다시 시도해 주세요.</p>
      </Notification>
      <ScreenState
        description="다른 키워드를 검색하거나 고객 지원에 문의해 주세요."
        kind="empty"
        title="표시할 결과가 없습니다."
      />
    </div>
  ),
}
