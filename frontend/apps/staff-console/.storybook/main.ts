import type { StorybookConfig } from '@storybook/react-vite'

const config: StorybookConfig = {
  stories: [
    '../src/design-system/foundations/CanonicalFoundations.stories.tsx',
    '../src/design-system/primitives/SeedCore.stories.tsx',
    '../src/design-system/components/SeedSurfaces.stories.tsx',
    '../src/design-system/components/SeedRichText.stories.tsx',
    '../src/design-system/components/SeedWorkspaceControls.stories.tsx',
    '../src/design-system/patterns/SeedWorkspace.stories.tsx',
    '../src/pages/StaffLoginPage.stories.tsx',
    '../src/features/ticket-views/AgentViewsPage.stories.tsx',
    '../src/features/ticket-search/AgentSearchPage.stories.tsx',
    '../src/features/ticket-create/CreateAgentTicketForm.stories.tsx',
    '../src/features/ticket-workspace/AgentTicketEditorWorkspace.stories.tsx',
    '../src/features/ticket-workspace/AgentTicketWorkspacePage.stories.tsx',
  ],
  addons: [
    '@chromatic-com/storybook',
    '@storybook/addon-vitest',
    '@storybook/addon-a11y',
    '@storybook/addon-docs',
    '@storybook/addon-mcp',
    'msw-storybook-addon',
  ],
  framework: '@storybook/react-vite',
  staticDirs: ['../public'],
}
export default config
