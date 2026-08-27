export type ComposerMode = 'public' | 'internal'
export type WorkspaceState =
  'ready' | 'loading' | 'empty' | 'error' | 'denied' | 'conflict'

export type ConversationEntry =
  | {
      kind: 'message'
      visibility: 'public' | 'internal'
      author: 'customer' | 'agent'
      name: string
      timestamp: string
      body: string[]
      attachment?: { name: string; size: string }
    }
  | {
      kind: 'system'
      timestamp: string
      body: string
    }

export type WorkspaceTicket = {
  number: string
  subject: string
  createdAt: string
  channel?: string
  status: 'New' | 'Open' | 'Pending' | 'Solved'
  priority: 'Low' | 'Normal' | 'High' | 'Urgent'
  group: string
  assignee: string
  requester: string
  organization?: string
  tags?: string[]
  productArea?: string
  language?: string
  conversation: ConversationEntry[]
}

const paymentConversation: ConversationEntry[] = [
  {
    kind: 'message',
    visibility: 'public',
    author: 'customer',
    name: '김지연',
    timestamp: 'Aug 11, 2026 9:14 AM',
    body: [
      '안녕하세요. 오늘(8/11) 오전에 Pro 플랜을 결제하려고 했는데, 결제 버튼을 누르면 “일시적인 오류가 발생했습니다. 다시 시도해주세요.”라는 메시지가 계속 뜹니다.',
      '다른 카드로 시도했지만 동일한 현상이에요.',
      '사용 중인 카드: 삼성카드 (****-****-****-4532)',
      '브라우저: Chrome 127.0.6533.120 / Windows 11',
      '도와주세요.',
    ],
  },
  {
    kind: 'message',
    visibility: 'public',
    author: 'agent',
    name: 'Mina Park',
    timestamp: 'Aug 11, 2026 9:28 AM',
    body: [
      '안녕하세요, 지연님. 이용에 불편을 드려 죄송합니다.',
      '문제 확인을 위해 결제 시도하신 시간대와 오류 화면 캡처를 첨부해 주실 수 있을까요?',
    ],
  },
  {
    kind: 'system',
    timestamp: 'Aug 11, 2026 9:29 AM',
    body: 'Mina Park님이 우선순위를 Normal에서 High로 변경했습니다.',
  },
  {
    kind: 'message',
    visibility: 'public',
    author: 'customer',
    name: '김지연',
    timestamp: 'Aug 11, 2026 9:33 AM',
    body: ['요청하신 스크린샷과 시도 시간을 첨부합니다.'],
    attachment: { name: '결제오류_스크린샷.png', size: '512 KB' },
  },
]

export const ticketFixtures: WorkspaceTicket[] = [
  {
    number: '1042',
    subject: '결제 버튼을 누르면 오류가 납니다',
    createdAt: 'Created Aug 11, 2026 9:14 AM',
    channel: 'via Website',
    status: 'Open',
    priority: 'High',
    group: 'Billing',
    assignee: 'Mina Park',
    requester: '김지연 (jiyeon.kim@example.com)',
    organization: '코어넷 주식회사',
    tags: ['결제 오류', '카드 결제'],
    productArea: '결제 및 구독',
    language: '한국어',
    conversation: paymentConversation,
  },
  {
    number: '1038',
    subject: '환불 처리 문의',
    createdAt: 'Created Aug 10, 2026 4:41 PM',
    channel: 'via Email',
    status: 'Pending',
    priority: 'Normal',
    group: 'Billing',
    assignee: 'Mina Park',
    requester: '서민서 (minseo.seo@example.com)',
    organization: '코어넷 주식회사',
    tags: ['환불', '연간 요금제'],
    productArea: '결제 및 구독',
    language: '한국어',
    conversation: [
      {
        kind: 'message',
        visibility: 'public',
        author: 'customer',
        name: '서민서',
        timestamp: 'Aug 10, 2026 4:41 PM',
        body: [
          '연간 요금제 결제 후 플랜 변경이 필요해 환불 가능 여부를 문의드립니다.',
        ],
      },
      {
        kind: 'system',
        timestamp: 'Aug 10, 2026 5:02 PM',
        body: '티켓 상태가 Open에서 Pending으로 변경되었습니다.',
      },
    ],
  },
]
