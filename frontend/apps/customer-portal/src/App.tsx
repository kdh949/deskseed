import { Link, Navigate, useRoutes, type RouteObject } from 'react-router'
import { ScreenState } from './design-system'
import { CustomerAccountRoute } from './features/customer-auth/CustomerAccountRoute'
import { CustomerCheckEmailPage } from './features/customer-auth/CustomerCheckEmailPage'
import { CustomerMagicLinkConsumePage } from './features/customer-auth/CustomerMagicLinkConsumePage'
import { CustomerRegisterPage } from './features/customer-auth/CustomerRegisterPage'
import { CustomerRouteLayout } from './features/customer-auth/CustomerRouteLayout'
import { CustomerSignInPage } from './features/customer-auth/CustomerSignInPage'
import { CustomerProfilePage } from './features/customer-portal/CustomerProfilePage'
import { CustomerRequestDetailPage } from './features/customer-portal/CustomerRequestDetailPage'
import { CustomerRequestListPage } from './features/customer-portal/CustomerRequestListPage'
import { AnonymousRequestDetailPage } from './features/customer-requests/AnonymousRequestDetailPage'
import { CustomerRequestCreatePage } from './features/customer-requests/CustomerRequestCreatePage'
import { CustomerRequestLookupPage } from './features/customer-requests/CustomerRequestLookupPage'
import { CustomerRequestSuccessPage } from './features/customer-requests/CustomerRequestSuccessPage'
import {
  HelpArticlePage,
  HelpCenterHomePage,
  HelpSearchPage,
} from './features/help-center/HelpCenterPages'

export const customerRoutes: RouteObject[] = [
  {
    element: <CustomerRouteLayout />,
    children: [
      { index: true, element: <HelpCenterHomePage /> },
      { path: 'search', element: <HelpSearchPage /> },
      { path: 'articles/:articleSlug', element: <HelpArticlePage /> },
      { path: 'requests/new', element: <CustomerRequestCreatePage /> },
      { path: 'requests/lookup', element: <CustomerRequestLookupPage /> },
      {
        path: 'requests/submitted/:ticketNumber',
        element: <CustomerRequestSuccessPage />,
      },
      {
        path: 'requests/:ticketNumber',
        element: <AnonymousRequestDetailPage />,
      },
      { path: 'customer/sign-in', element: <CustomerSignInPage /> },
      {
        path: 'customer/sign-in/check-email',
        element: <CustomerCheckEmailPage />,
      },
      {
        path: 'customer/sign-in/consume',
        element: <CustomerMagicLinkConsumePage />,
      },
      { path: 'customer/register', element: <CustomerRegisterPage /> },
      {
        path: 'account',
        element: <CustomerAccountRoute />,
        children: [
          { index: true, element: <Navigate to="requests" replace /> },
          { path: 'requests', element: <CustomerRequestListPage /> },
          {
            path: 'requests/:ticketNumber',
            element: <CustomerRequestDetailPage />,
          },
          { path: 'settings', element: <CustomerProfilePage /> },
        ],
      },
      {
        path: '*',
        element: (
          <div className="customer-page">
            <ScreenState
              action={<Link to="/">고객 지원 홈</Link>}
              description="요청한 고객 화면을 찾을 수 없습니다."
              kind="not-found"
              title="페이지를 찾을 수 없습니다."
            />
          </div>
        ),
      },
    ],
  },
]

export default function App() {
  return useRoutes(customerRoutes)
}
