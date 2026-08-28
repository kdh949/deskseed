import { spawn } from 'node:child_process'
import { resolve } from 'node:path'
import process from 'node:process'

const root = resolve(import.meta.dirname, '..')
const vite = resolve(root, 'node_modules/vite/bin/vite.js')
const forwarded = process.argv.slice(2)
const children = [
  spawn(
    process.execPath,
    [
      vite,
      '--config',
      'apps/staff-console/vite.config.ts',
      '--host',
      '127.0.0.1',
      '--port',
      '45174',
      '--strictPort',
    ],
    { cwd: root, stdio: 'inherit' },
  ),
  spawn(
    process.execPath,
    [vite, '--config', 'apps/customer-portal/vite.config.ts', ...forwarded],
    { cwd: root, stdio: 'inherit' },
  ),
]

let exiting = false
function stop(signal = 'SIGTERM') {
  if (exiting) return
  exiting = true
  children.forEach((child) => child.kill(signal))
}

process.on('SIGINT', () => stop('SIGINT'))
process.on('SIGTERM', () => stop('SIGTERM'))

children.forEach((child) => {
  child.on('exit', (code, signal) => {
    if (!exiting) {
      stop()
      process.exitCode = code ?? (signal ? 1 : 0)
    }
  })
})
