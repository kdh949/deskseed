import type { RouteObject } from 'react-router'
import { describe, expect, it } from 'vitest'
import { customerRoutes } from './App'

function routePaths(routes: RouteObject[], base = ''): string[] {
  return routes.flatMap((route) => {
    const current = route.index
      ? base || '/'
      : route.path
        ? route.path.startsWith('/')
          ? route.path
          : `${base}/${route.path}`.replace(/\/+/g, '/')
        : base
    return [
      ...(route.path || route.index ? [current || '/'] : []),
      ...routePaths(route.children ?? [], current),
    ]
  })
}

describe('customer production route inventory', () => {
  it('keeps the customer journey in the customer router', () => {
    expect(routePaths(customerRoutes)).toEqual(
      expect.arrayContaining([
        '/',
        '/search',
        '/articles/:articleSlug',
        '/requests/new',
        '/requests/submitted/:ticketNumber',
        '/customer/sign-in',
        '/customer/sign-in/check-email',
        '/customer/register',
        '/account/requests',
      ]),
    )
    expect(routePaths(customerRoutes)).not.toContain('/account/settings')
  })

  it('does not expose staff routes from the customer router', () => {
    expect(routePaths(customerRoutes)).not.toEqual(
      expect.arrayContaining([
        '/agent/login',
        '/agent/tickets/:ticketNumber',
        '/admin/operations/mail',
      ]),
    )
  })
})
