import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const frontendRoot = fileURLToPath(new URL('..', import.meta.url))
const contractPath = fileURLToPath(
  new URL('../../api/core-api-outline-v1.yaml', import.meta.url),
)
const manifestPath = fileURLToPath(
  new URL('../src/api/p1-contract-manifest.json', import.meta.url),
)

const [contract, manifestSource] = await Promise.all([
  readFile(contractPath, 'utf8'),
  readFile(manifestPath, 'utf8'),
])
const manifest = JSON.parse(manifestSource)

for (const entry of manifest) {
  const pathMarker = `  ${entry.path}:\n`
  const pathStart = contract.indexOf(pathMarker)
  assert(pathStart >= 0, `OpenAPI path missing: ${entry.path}`)
  const nextPath = contract.indexOf('\n  /api/', pathStart + pathMarker.length)
  const pathBlock = contract.slice(
    pathStart,
    nextPath < 0 ? undefined : nextPath,
  )
  const methodMarker = `    ${entry.method}:\n`
  const methodStart = pathBlock.indexOf(methodMarker)
  assert(
    methodStart >= 0,
    `OpenAPI method missing: ${entry.method.toUpperCase()} ${entry.path}`,
  )
  const nextMethod = findNextMethod(
    pathBlock,
    methodStart + methodMarker.length,
  )
  const operationBlock = pathBlock.slice(
    methodStart,
    nextMethod < 0 ? undefined : nextMethod,
  )
  assert(
    operationBlock.includes(`operationId: ${entry.operationId}`),
    `operationId mismatch for ${entry.method.toUpperCase()} ${entry.path}`,
  )
  assert(
    operationBlock.includes('x-deskseed-contract-status: FROZEN'),
    `P1 operation is not FROZEN: ${entry.operationId}`,
  )
}

const duplicateOperationIds = manifest
  .map((entry) => entry.operationId)
  .filter((operationId, index, all) => all.indexOf(operationId) !== index)
assert(
  duplicateOperationIds.length === 0,
  `Duplicate operationIds in manifest: ${duplicateOperationIds.join(', ')}`,
)

console.log(
  `P1 OpenAPI contract check passed (${manifest.length} FROZEN operations, root ${frontendRoot}).`,
)

function findNextMethod(block, from) {
  const candidates = ['get', 'post', 'put', 'patch', 'delete']
    .map((method) => block.indexOf(`\n    ${method}:\n`, from))
    .filter((index) => index >= 0)
  return candidates.length ? Math.min(...candidates) : -1
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}
