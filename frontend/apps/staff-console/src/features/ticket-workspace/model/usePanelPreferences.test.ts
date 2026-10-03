import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  readPanelPreferences,
  usePanelPreferences,
} from './usePanelPreferences'

describe('staff panel width preferences', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => vi.restoreAllMocks())
  it('bounds malformed and legacy values without storing additional fields', () => {
    localStorage.setItem(
      'deskseed:panel-widths:v1:a',
      JSON.stringify({
        properties: 999,
        context: -4,
        title: 'not a preference',
      }),
    )
    expect(readPanelPreferences('a')).toEqual({ properties: 420, context: 240 })
    localStorage.setItem('deskseed:panel-widths:v1:a', '{broken')
    expect(readPanelPreferences('a')).toEqual({ properties: 320, context: 320 })
  })
  it('persists bounded values and does not copy one staff preference to another', () => {
    const hook = renderHook(({ staffId }) => usePanelPreferences(staffId), {
      initialProps: { staffId: 'a' },
    })
    act(() => hook.result.current.resize('properties', 900))
    expect(readPanelPreferences('a').properties).toBe(420)
    hook.rerender({ staffId: 'b' })
    expect(hook.result.current.widths).toEqual({
      properties: 320,
      context: 320,
    })
    act(() => hook.result.current.resize('context', 460))
    expect(readPanelPreferences('b')).toEqual({ properties: 320, context: 460 })
    expect(readPanelPreferences('a')).toEqual({ properties: 420, context: 320 })
  })
  it('still resizes when storage writes are unavailable', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota')
    })
    const hook = renderHook(() => usePanelPreferences('a'))
    act(() => hook.result.current.resize('context', 900))
    expect(hook.result.current.widths.context).toBe(520)
  })
})
