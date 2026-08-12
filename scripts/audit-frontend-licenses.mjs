#!/usr/bin/env node

import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const lockfilePath = resolve(repositoryRoot, 'frontend/package-lock.json')
const lockfile = JSON.parse(readFileSync(lockfilePath, 'utf8'))
const packages = lockfile.packages ?? {}
const installed = Object.entries(packages).filter(([location]) => location !== '')
const missingLicenseLocations = []
const licenseCounts = new Map()

for (const [location, metadata] of installed) {
  const license =
    typeof metadata.license === 'string' && metadata.license.trim().length > 0
      ? metadata.license.trim()
      : null
  if (license === null) {
    missingLicenseLocations.push(location)
    continue
  }
  licenseCounts.set(license, (licenseCounts.get(license) ?? 0) + 1)
}

const directRuntime = Object.keys(packages['']?.dependencies ?? {})
  .sort()
  .map((name) => {
    const metadata = packages[`node_modules/${name}`]
    if (!metadata) throw new Error(`Missing lockfile metadata for ${name}`)
    return { name, version: metadata.version, license: metadata.license ?? null }
  })

const report = {
  lockfile: 'frontend/package-lock.json',
  installedPackageLocations: installed.length,
  missingLicenseLocations,
  licenseCounts: Object.fromEntries(
    [...licenseCounts.entries()].sort(([left], [right]) =>
      left.localeCompare(right),
    ),
  ),
  directRuntime,
}

process.stdout.write(`${JSON.stringify(report, null, 2)}\n`)
if (missingLicenseLocations.length > 0) process.exitCode = 1
