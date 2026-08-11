import type {
  ConversationTimelineItem,
  PropertyPanelItem,
  TicketTableItem,
} from '../../shared/ui/system'

export const fixtureTickets: TicketTableItem[] = [
  {
    ticketNumber: 1042,
    subject: '결제 승인 오류 — 카드 인증 후 주문이 생성되지 않음',
    status: 'OPEN',
    priority: 'URGENT',
    requester: '김민수',
    group: '결제 지원',
    assignee: '박서연',
    updatedLabel: '08. 11. 오후 12:30',
  },
  {
    ticketNumber: 1041,
    subject: '환불 처리 상태를 확인하고 싶습니다',
    status: 'PENDING',
    priority: 'NORMAL',
    requester: '이수진',
    group: '결제 지원',
    assignee: '미배정',
    updatedLabel: '08. 11. 오후 12:12',
  },
  {
    ticketNumber: 1039,
    subject: '법인 카드 영수증 재발급 요청',
    status: 'OPEN',
    priority: 'HIGH',
    requester: '오지훈',
    group: '청구 지원',
    assignee: '정유나',
    updatedLabel: '08. 11. 오전 11:48',
  },
  {
    ticketNumber: 1036,
    subject: '정기 결제 해지 이후에도 알림이 도착합니다',
    status: 'NEW',
    priority: 'LOW',
    requester: 'Alexandra Montgomery',
    group: '고객 지원',
    assignee: '미배정',
    updatedLabel: '08. 11. 오전 11:31',
  },
]

export const fixtureProperties: PropertyPanelItem[] = [
  { label: '상태', value: '처리 중' },
  { label: '우선순위', value: 'URGENT · 긴급' },
  { label: '요청자', value: '김민수' },
  { label: '그룹', value: '결제 지원' },
  { label: '담당자', value: '박서연' },
  { label: '업데이트', value: '2026. 8. 11. 오후 12:30' },
  { label: '티켓 유형', value: '고객 문의' },
]

export const fixtureConversation: ConversationTimelineItem[] = [
  {
    id: 'fixture-public-1',
    visibility: 'PUBLIC',
    author: '김민수',
    createdAt: '2026-08-11T02:00:00Z',
    body: '결제 인증은 완료됐는데 주문 내역이 만들어지지 않았습니다. 같은 카드로 두 번 시도했고 결과는 동일했습니다.',
  },
  {
    id: 'fixture-internal-1',
    visibility: 'INTERNAL',
    author: '박서연',
    createdAt: '2026-08-11T02:28:00Z',
    body: '승인 번호 확인됨. 주문 생성 로그와 멱등키 처리 여부를 결제 플랫폼 담당자에게 확인 요청함.',
  },
  {
    id: 'fixture-public-2',
    visibility: 'PUBLIC',
    author: '박서연',
    createdAt: '2026-08-11T02:44:00Z',
    body: '확인 중이며 중복 결제는 발생하지 않았습니다. 처리 결과를 이 티켓으로 안내드리겠습니다.',
  },
]
