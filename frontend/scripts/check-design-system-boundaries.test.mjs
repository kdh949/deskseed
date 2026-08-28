import assert from 'node:assert/strict'
import { join } from 'node:path'
import test from 'node:test'
import { findCrossAppImports } from './check-design-system-boundaries.mjs'

const frontendRoot = '/workspace/frontend'
const customerRoot = join(frontendRoot, 'apps', 'customer-portal', 'src')
const staffRoot = join(frontendRoot, 'apps', 'staff-console', 'src')

test('rejects a relative customer import that resolves into the staff app', () => {
  const importer = join(customerRoot, 'features', 'Example.tsx')
  const source =
    "import { DsButton } from '../../../staff-console/src/design-system'"

  assert.deepEqual(
    findCrossAppImports({
      importer,
      oppositeAliases: ['apps/staff-console', '@deskseed/staff-console'],
      oppositeRoot: staffRoot,
      source,
    }),
    ['../../../staff-console/src/design-system'],
  )
})

test('rejects a relative staff import that resolves into the customer app', () => {
  const importer = join(staffRoot, 'features', 'Example.tsx')
  const source =
    "export { CustomerBrand } from '../../../customer-portal/src/design-system'"

  assert.deepEqual(
    findCrossAppImports({
      importer,
      oppositeAliases: ['apps/customer-portal', '@deskseed/customer-portal'],
      oppositeRoot: customerRoot,
      source,
    }),
    ['../../../customer-portal/src/design-system'],
  )
})

test('rejects a relative stylesheet import that resolves into the other app', () => {
  const importer = join(customerRoot, 'design-system', 'example.css')
  const source = "@import '../../../staff-console/src/design-system/index.css';"

  assert.deepEqual(
    findCrossAppImports({
      importer,
      oppositeAliases: ['apps/staff-console', '@deskseed/staff-console'],
      oppositeRoot: staffRoot,
      source,
    }),
    ['../../../staff-console/src/design-system/index.css'],
  )
})

test('allows imports that remain inside the owning app', () => {
  const importer = join(customerRoot, 'features', 'Example.tsx')
  const source = "import { DsButton } from '../design-system'"

  assert.deepEqual(
    findCrossAppImports({
      importer,
      oppositeAliases: ['apps/staff-console', '@deskseed/staff-console'],
      oppositeRoot: staffRoot,
      source,
    }),
    [],
  )
})
