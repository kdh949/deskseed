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
}

if (failures.length) {
  console.error(failures.join('\n'))
  process.exit(1)
}

console.log('Customer and staff design-system boundaries verified.')
