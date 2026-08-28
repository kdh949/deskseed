import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ExtensionSlot } from './ExtensionSlot'
import {
  ExtensionContractError,
  FrontendExtensionCatalog,
  allowsContribution,
} from './catalog'
import type { FeatureContributionModule, FrontendContribution } from './types'

const agentAccess = { role: 'AGENT', capabilities: ['KNOWLEDGE_READ'] }

function catalogFor(contributions: readonly FrontendContribution[]) {
  return FrontendExtensionCatalog.fromModules({
    '../extensions/example/feature-contribution.tsx': {
      contribution: contributions,
    },
  })
}

describe('FrontendExtensionCatalog', () => {
  it('discovers contributions in stable order and applies both role and capability policy', () => {
    const catalog = FrontendExtensionCatalog.fromModules({
      '../extensions/z/feature-contribution.tsx': {
        contribution: {
          id: 'knowledge.search',
          kind: 'shell-navigation',
          label: 'Knowledge',
          order: 20,
          requiredCapabilities: ['KNOWLEDGE_READ'],
          requiredRoles: ['AGENT'],
          surface: 'agent',
          to: '/agent/knowledge',
        },
      },
      '../extensions/a/feature-contribution.tsx': {
        contribution: {
          id: 'ticket.config',
          kind: 'shell-navigation',
          label: 'Configuration',
          order: 10,
          requiredRoles: ['ADMIN'],
          surface: 'agent',
          to: '/agent/configuration',
        },
      },
    } satisfies Record<string, FeatureContributionModule>)

    expect(catalog.agentNavigationFor(agentAccess).map(({ id }) => id)).toEqual(
      ['knowledge.search'],
    )
    expect(
      allowsContribution(
        {
          requiredCapabilities: ['KNOWLEDGE_READ'],
          requiredRoles: ['AGENT'],
        },
        agentAccess,
      ),
    ).toBe(true)
    expect(
      allowsContribution(
        { requiredCapabilities: ['KNOWLEDGE_WRITE'] },
        agentAccess,
      ),
    ).toBe(false)
  })

  it('rejects duplicate IDs, ambiguous slot order, and unsafe route metadata', () => {
    expect(() =>
      catalogFor([
        {
          id: 'knowledge.article',
          kind: 'route',
          surface: 'agent',
          path: 'knowledge',
          title: 'Knowledge',
          order: 0,
          element: <p>Knowledge</p>,
        },
        {
          id: 'knowledge.article',
          kind: 'workspace-slot',
          slot: 'ticket-composer.toolbar',
          order: 0,
          render: () => <p>Toolbar</p>,
        },
      ]),
    ).toThrow(ExtensionContractError)

    expect(() =>
      catalogFor([
        {
          id: 'knowledge.first',
          kind: 'workspace-slot',
          slot: 'ticket-composer.toolbar',
          order: 0,
          render: () => <p>First</p>,
        },
        {
          id: 'knowledge.second',
          kind: 'workspace-slot',
          slot: 'ticket-composer.toolbar',
          order: 0,
          render: () => <p>Second</p>,
        },
      ]),
    ).toThrow('Ambiguous frontend extension order')

    expect(() =>
      catalogFor([
        {
          id: 'knowledge.customer',
          kind: 'route',
          surface: 'customer',
          path: 'knowledge',
          title: 'Knowledge',
          order: 0,
          requiredRoles: ['AGENT'],
          element: <p>Knowledge</p>,
        },
      ]),
    ).toThrow('Customer extension routes cannot declare staff access')
  })

  it('isolates a failed optional slot without hiding its healthy siblings', () => {
    const consoleError = vi
      .spyOn(console, 'error')
      .mockImplementation(() => undefined)
    const preventExpectedError = (event: ErrorEvent) => event.preventDefault()
    window.addEventListener('error', preventExpectedError)
    const catalog = catalogFor([
      {
        id: 'knowledge.broken',
        kind: 'workspace-slot',
        slot: 'ticket-composer.status',
        order: 0,
        render: () => {
          throw new Error('expected extension failure')
        },
      },
      {
        id: 'knowledge.healthy',
        kind: 'workspace-slot',
        slot: 'ticket-composer.status',
        order: 1,
        render: ({ ticketNumber }) => <p>Ticket {ticketNumber} is healthy</p>,
      },
    ])

    try {
      render(
        <ExtensionSlot
          access={agentAccess}
          catalog={catalog}
          context={{ ticketNumber: '1042', composerMode: 'public' }}
          slot="ticket-composer.status"
        />,
      )

      expect(screen.getByText('Ticket 1042 is healthy')).toBeVisible()
    } finally {
      window.removeEventListener('error', preventExpectedError)
      consoleError.mockRestore()
    }
  })
})

afterEach(() => {
  vi.restoreAllMocks()
})
