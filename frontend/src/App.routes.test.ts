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

describe('customer production route inventory', () => {
  it('keeps every P0 customer route in the production router', () => {
    expect(routePaths(appRoutes)).toEqual(
      expect.arrayContaining([
        '/',
        '/requests/new',
        '/requests/lookup',
        '/requests/:ticketNumber',
        '/customer/sign-in',
        '/customer/sign-in/consume',
        '/account/requests',
        '/account/requests/:ticketNumber',
      ]),
    )
  })

  it('does not expose the legacy frontend fixture route in the production router', () => {
    expect(routePaths(appRoutes)).not.toContain(
      '/__fixtures__/frontend-system/:fixtureName',
    )
  })
})
