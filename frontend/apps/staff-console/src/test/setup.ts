import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

afterEach(() => {
  cleanup()
})

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  }),
})

const storage = new Map<string, string>()
const localStorageMock: Storage = {
  get length() {
    return storage.size
  },
  clear: () => storage.clear(),
  getItem: (key) => storage.get(key) ?? null,
  key: (index) => [...storage.keys()][index] ?? null,
  removeItem: (key) => {
    storage.delete(key)
  },
  setItem: (key, value) => {
    storage.set(key, String(value))
  },
}
Object.defineProperty(window, 'localStorage', {
  configurable: true,
  value: localStorageMock,
})
Object.defineProperty(globalThis, 'localStorage', {
  configurable: true,
  value: localStorageMock,
})

// ProseMirror asks the browser for selection geometry while applying keyboard
// input. JSDOM does not implement Range geometry, so keep editor unit tests on
// the same event path without coupling them to a layout engine.
if (!Range.prototype.getBoundingClientRect) {
  Range.prototype.getBoundingClientRect = () => new DOMRect()
}
if (!Range.prototype.getClientRects) {
  Range.prototype.getClientRects = () =>
    ({
      item: () => null,
      length: 0,
      [Symbol.iterator]: function* () {},
    }) as DOMRectList
}
if (!document.elementFromPoint) {
  document.elementFromPoint = () => document.body
}
