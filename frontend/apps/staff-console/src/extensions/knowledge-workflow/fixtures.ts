import type { Article, Category, Revision, Section } from './api'
export const category: Category = {
  id: '11111111-1111-4111-8111-111111111111',
  slug: 'billing',
  title: '결제',
  description: '결제와 환불',
  active: true,
  displayOrder: 0,
  version: 0,
}
export const section: Section = {
  ...category,
  id: '22222222-2222-4222-8222-222222222222',
  categoryId: category.id,
  slug: 'refunds',
  title: '환불',
}
export const revision: Revision = {
  id: '44444444-4444-4444-8444-444444444444',
  revisionNumber: 1,
  title: '환불 처리 안내',
  summary: '주문 환불 절차를 안내합니다.',
  document: {
    schemaVersion: 1,
    blocks: [
      { type: 'heading', level: 2, text: '환불 요청' },
      { type: 'paragraph', text: '주문번호를 확인한 뒤 환불을 요청하세요.' },
      {
        type: 'list',
        ordered: true,
        items: ['주문 내역 열기', '환불 요청 선택'],
      },
    ],
  },
  createdAt: '2026-09-08T01:00:00Z',
}
export const article: Article = {
  id: '33333333-3333-4333-8333-333333333333',
  sectionId: section.id,
  slug: 'refund-guide',
  lifecycle: 'DRAFT',
  audience: { type: 'PUBLIC', groupIds: [] },
  version: 0,
  currentPublishedRevision: null,
}
