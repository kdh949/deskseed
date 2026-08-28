import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'
import process from 'node:process'

const root = resolve(import.meta.dirname, '..')
const customerRoot = join(root, 'apps', 'customer-portal', 'src')
const staffRoot = join(root, 'apps', 'staff-console', 'src')
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
  if (/apps\/customer-portal|@deskseed\/customer-portal/.test(source)) {
    failures.push(`customer app import in staff app: ${relative(root, path)}`)
  }
}

for (const path of filesUnder(customerRoot)) {
  if (!/\.(css|ts|tsx)$/.test(path)) continue
  const source = readFileSync(path, 'utf8')
  if (source.includes('--ds-')) {
    failures.push(`staff token in customer app: ${relative(root, path)}`)
  }
  if (/apps\/staff-console|@deskseed\/staff-console/.test(source)) {
    failures.push(`staff app import in customer app: ${relative(root, path)}`)
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

if (failures.length) {
  console.error(failures.join('\n'))
  process.exit(1)
}

console.log('Customer and staff design-system boundaries verified.')
