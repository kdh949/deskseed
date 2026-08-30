import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  dirname,
  extname,
  isAbsolute,
  join,
  relative,
  resolve,
  sep,
} from 'node:path'
import process from 'node:process'
import ts from 'typescript'

const root = resolve(import.meta.dirname, '..')
const customerRoot = join(root, 'apps', 'customer-portal', 'src')
const staffRoot = join(root, 'apps', 'staff-console', 'src')
const canonicalStaffSurfaceFiles = [
  join(root, 'apps', 'staff-console', '.storybook', 'preview.tsx'),
  join(staffRoot, 'design-system', 'canonical.ts'),
  join(staffRoot, 'design-system', 'canonical-index.css'),
  join(staffRoot, 'design-system', 'foundations', 'canonical.css'),
  join(staffRoot, 'design-system', 'foundations', 'canonical-stories.css'),
  join(staffRoot, 'design-system', 'foundations', 'seed-story-helpers.css'),
  join(
    staffRoot,
    'design-system',
    'foundations',
    'CanonicalFoundations.stories.tsx',
  ),
  join(staffRoot, 'design-system', 'primitives', 'SeedCore.tsx'),
  join(staffRoot, 'design-system', 'primitives', 'SeedCore.stories.tsx'),
  join(staffRoot, 'design-system', 'primitives', 'seed-core.css'),
  join(staffRoot, 'design-system', 'components', 'SeedSurfaces.tsx'),
  join(staffRoot, 'design-system', 'components', 'SeedSurfaces.stories.tsx'),
  join(staffRoot, 'design-system', 'components', 'seed-surfaces.css'),
  join(staffRoot, 'design-system', 'patterns', 'SeedWorkspace.tsx'),
  join(staffRoot, 'design-system', 'patterns', 'SeedWorkspace.stories.tsx'),
  join(staffRoot, 'design-system', 'patterns', 'seed-workspace.css'),
  join(staffRoot, 'App.tsx'),
  join(staffRoot, 'main.tsx'),
  join(staffRoot, 'pages', 'StaffLoginPage.tsx'),
  join(staffRoot, 'pages', 'StaffLoginPage.stories.tsx'),
  join(staffRoot, 'features', 'agent-shell', 'AgentShellLayout.tsx'),
  join(staffRoot, 'features', 'staff-auth', 'StaffRoute.tsx'),
  join(staffRoot, 'features', 'ticket-search', 'AgentSearchPage.tsx'),
  join(staffRoot, 'features', 'ticket-search', 'AgentSearchPage.stories.tsx'),
  join(staffRoot, 'features', 'ticket-create', 'CreateAgentTicketPage.tsx'),
  join(staffRoot, 'features', 'ticket-create', 'CreateAgentTicketForm.tsx'),
  join(
    staffRoot,
    'features',
    'ticket-create',
    'CreateAgentTicketForm.stories.tsx',
  ),
  join(staffRoot, 'features', 'ticket-create', 'RequesterSearchField.tsx'),
  join(staffRoot, 'features', 'ticket-views', 'AgentViewsPage.tsx'),
  join(staffRoot, 'features', 'ticket-views', 'AgentViewsPage.stories.tsx'),
  join(staffRoot, 'features', 'ticket-views', 'BulkTicketActionPanel.tsx'),
  join(staffRoot, 'features', 'ticket-views', 'ViewConfigurationDrawer.tsx'),
  join(
    staffRoot,
    'features',
    'ticket-workspace',
    'AgentTicketEditorWorkspace.tsx',
  ),
  join(
    staffRoot,
    'features',
    'ticket-workspace',
    'AgentTicketEditorWorkspace.stories.tsx',
  ),
  join(
    staffRoot,
    'features',
    'ticket-workspace',
    'AgentTicketWorkspacePage.tsx',
  ),
  join(
    staffRoot,
    'features',
    'ticket-workspace',
    'AgentTicketWorkspacePage.stories.tsx',
  ),
  join(staffRoot, 'features', 'attachments', 'AttachmentList.tsx'),
  join(staffRoot, 'features', 'attachments', 'AttachmentUploadField.tsx'),
  join(
    staffRoot,
    'extensions',
    'drafts-presence',
    'DraftsPresenceContribution.tsx',
  ),
  join(staffRoot, 'extensions', 'drafts-presence', 'feature-contribution.tsx'),
]
const legacyStaffPresentationReferences = [
  [
    'legacy design-system entrypoint',
    /from\s+['"][^'"]*design-system(?:\/index)?['"]/,
  ],
  [
    'legacy presentation symbol',
    /\b(?:DsButton|DsSelect|ScreenState|Notification|DeskseedThemeProvider)\b/,
  ],
  [
    'retired staff presentation symbol',
    /\b(?:QueueTicketTable|ViewNavigation|AgentShell|WorkspaceNavigationRail|TicketWorkspace|TicketContextPanel|TicketPropertiesPanel|ReplyComposer|ConversationTimeline|FrontendSystemFixturePage)\b/,
  ],
  [
    'legacy presentation class',
    /className\s*=\s*['"`][^'"`]*(?:\bds-|\bagent-|\bstaff-gate|\bticket-collaboration-|\bbulk-ticket-panel)/,
  ],
  [
    'legacy presentation stylesheet',
    /import\s+['"][^'"]*(?:designSystem|tokens|primitives|application|ticket-queue|ticket-workspace|ticket-create|draftsPresence)\.css['"]/,
  ],
  [
    'reference screenshot import',
    /(?:image-[1-5]\.png|fdd3fbd0-70dd-45ff-a285-785ee9d011a2)/,
  ],
]
const forbiddenRoots = [
  join(customerRoot, 'shared'),
  join(staffRoot, 'shared', 'ui'),
  join(staffRoot, 'styles', 'tokens.css'),
]
const forbiddenReferences = [
  ['removed shared UI root', /shared\/ui/],
  ['removed preview shell', /DesignPreviewAgentShell/],
  ['removed preview flag', /VITE_DESIGN_PREVIEW/],
  ['removed token entry', /styles\/tokens\.css/],
  [
    'compatibility export',
    /export\s+const\s+(IconButton|Avatar|StatusIndicator)\s*=/,
  ],
]
const forbiddenCustomerTypographyReferences = [
  [
    'ornamental customer micro-heading',
    /\b(?:eyebrow|kicker|overline|supertitle|pretitle|pre-heading|micro-heading)\b/i,
  ],
  ['forced uppercase customer text', /text-transform\s*:\s*uppercase\b/i],
]
const forbiddenCustomerCopyReferences = [
  ['customer-visible PUBLIC term', /\bPUBLIC\b/],
  ['customer-visible INTERNAL term', /\bINTERNAL\b/],
  ['customer-visible access explanation', /공개\s*(?:대화|답변|문의)/],
  ['customer-visible projection term', /\bprojection\b/i],
  ['customer-visible fragment term', /\bfragment\b/i],
  ['customer-visible implementation surface', /고객\s*(?:API|세션)/],
  ['customer-visible command identity', /명령 식별자|새 명령/],
  ['customer-visible attachment state', /CLEAN 상태/],
  ['customer-visible intake configuration', /접수 설정/],
  ['customer-visible access token', /접근 토큰/],
  ['customer-visible security defense', /보안을 위해|이 브라우저에서/],
  ['customer-visible access denial', /접근이 허용되지/],
]

function filesUnder(directory) {
  return readdirSync(directory).flatMap((entry) => {
    const path = join(directory, entry)
    return statSync(path).isDirectory() ? filesUnder(path) : [path]
  })
}

function importedSpecifiers(importer, source) {
  if (extname(importer) === '.css') {
    const specifiers = []
    const pattern = /@import\s+(?:url\(\s*)?(['"])([^'"]+)\1\s*\)?/g
    for (const match of source.matchAll(pattern)) specifiers.push(match[2])
    return specifiers
  }

  return ts
    .preProcessFile(source, true, true)
    .importedFiles.map(({ fileName }) => fileName)
}

function isWithin(candidate, ownerRoot) {
  const ownerRelative = relative(ownerRoot, candidate)
  return (
    ownerRelative === '' ||
    (ownerRelative !== '..' &&
      !ownerRelative.startsWith(`..${sep}`) &&
      !isAbsolute(ownerRelative))
  )
}

export function findCrossAppImports({
  importer,
  oppositeAliases,
  oppositeRoot,
  source,
}) {
  return importedSpecifiers(importer, source).filter((specifier) => {
    if (
      oppositeAliases.some(
        (alias) => specifier === alias || specifier.startsWith(`${alias}/`),
      )
    ) {
      return true
    }
    if (!specifier.startsWith('.') && !isAbsolute(specifier)) return false
    return isWithin(resolve(dirname(importer), specifier), oppositeRoot)
  })
}

export function runBoundaryCheck() {
  const failures = []
  for (const path of forbiddenRoots) {
    if (existsSync(path))
      failures.push(`forbidden path exists: ${relative(root, path)}`)
  }

  for (const path of filesUnder(staffRoot)) {
    if (!/\.(css|ts|tsx)$/.test(path)) continue
    const source = readFileSync(path, 'utf8')
    for (const [label, pattern] of forbiddenReferences) {
      if (pattern.test(source)) {
        failures.push(`${label}: ${relative(root, path)}`)
      }
    }
    if (
      !path.includes(`${join('src', 'design-system')}`) &&
      source.includes('@zendeskgarden/')
    ) {
      failures.push(
        `Garden import outside design-system: ${relative(root, path)}`,
      )
    }
    if (source.includes('--customer-')) {
      failures.push(`customer token in staff app: ${relative(root, path)}`)
    }
    for (const specifier of findCrossAppImports({
      importer: path,
      oppositeAliases: ['apps/customer-portal', '@deskseed/customer-portal'],
      oppositeRoot: customerRoot,
      source,
    })) {
      failures.push(
        `customer app import in staff app: ${relative(root, path)} (${specifier})`,
      )
    }
  }

  for (const path of canonicalStaffSurfaceFiles) {
    if (!existsSync(path)) {
      failures.push(`canonical staff surface missing: ${relative(root, path)}`)
      continue
    }
    const source = readFileSync(path, 'utf8')
    for (const [label, pattern] of legacyStaffPresentationReferences) {
      if (pattern.test(source)) {
        failures.push(`${label}: ${relative(root, path)}`)
      }
    }
  }

  for (const path of filesUnder(customerRoot)) {
    if (!/\.(css|ts|tsx)$/.test(path)) continue
    const source = readFileSync(path, 'utf8')
    if (source.includes('--ds-')) {
      failures.push(`staff token in customer app: ${relative(root, path)}`)
    }
    for (const specifier of findCrossAppImports({
      importer: path,
      oppositeAliases: ['apps/staff-console', '@deskseed/staff-console'],
      oppositeRoot: staffRoot,
      source,
    })) {
      failures.push(
        `staff app import in customer app: ${relative(root, path)} (${specifier})`,
      )
    }
    if (
      !path.includes(`${join('src', 'design-system')}`) &&
      source.includes('@zendeskgarden/')
    ) {
      failures.push(
        `Garden import outside customer design-system: ${relative(root, path)}`,
      )
    }
    for (const [label, pattern] of forbiddenCustomerTypographyReferences) {
      if (pattern.test(source)) {
        failures.push(`${label}: ${relative(root, path)}`)
      }
    }
    const isRenderedCustomerSource =
      path.endsWith('.tsx') &&
      !/\.(?:stories|test)\.tsx$/.test(path) &&
      !path.includes(`${join('src', 'api')}`) &&
      !path.includes(`${join('features', 'customer-auth', 'api')}`) &&
      !path.includes(`${join('features', 'customer-portal', 'api')}`)
    if (isRenderedCustomerSource) {
      for (const [label, pattern] of forbiddenCustomerCopyReferences) {
        if (pattern.test(source)) {
          failures.push(`${label}: ${relative(root, path)}`)
        }
      }
    }
  }

  return failures
}

const isMain =
  process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)

const failures = isMain ? runBoundaryCheck() : []
if (failures.length) {
  console.error(failures.join('\n'))
  process.exit(1)
}

if (isMain) console.log('Customer and staff design-system boundaries verified.')
