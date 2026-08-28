import type { RouteObject } from 'react-router'
import { describe, expect, it } from 'vitest'
import { appRoutes } from './App'

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

describe('staff production route inventory', () => {
  it('keeps staff routes in the staff router and excludes customer routes', () => {
    expect(routePaths(appRoutes)).toEqual(
      expect.arrayContaining([
        '/agent/login',
        '/agent/views/:viewKey',
        '/agent/tickets/:ticketNumber',
        '/admin/operations/mail',
      ]),
    )
    expect(routePaths(appRoutes)).not.toEqual(
      expect.arrayContaining(['/requests/new', '/customer/sign-in']),
    )
  })

  it('does not expose the legacy frontend fixture route in the production router', () => {
    expect(routePaths(appRoutes)).not.toContain(
      '/__fixtures__/frontend-system/:fixtureName',
    )
  })
})
