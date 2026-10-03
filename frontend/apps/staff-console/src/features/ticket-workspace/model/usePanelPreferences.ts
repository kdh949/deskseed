import { useEffect, useMemo, useState } from 'react'

export type PanelWidths = { properties: number; context: number }
const defaults: PanelWidths = { properties: 320, context: 320 }
const storageKey = (staffId: string) => `deskseed:panel-widths:v1:${staffId}`
const bounded = (value: unknown, max: number) =>
  typeof value === 'number' && Number.isFinite(value)
    ? Math.max(240, Math.min(max, Math.round(value)))
    : 320

export function readPanelPreferences(staffId: string): PanelWidths {
  try {
    const stored: unknown = JSON.parse(
      localStorage.getItem(storageKey(staffId)) ?? 'null',
    )
    if (!stored || typeof stored !== 'object') return defaults
    const values = stored as Record<string, unknown>
    return {
      properties: bounded(values.properties, 420),
      context: bounded(values.context, 520),
    }
  } catch {
    return defaults
  }
}

export function usePanelPreferences(staffId: string) {
  const initial = useMemo(() => readPanelPreferences(staffId), [staffId])
  const [preference, setPreference] = useState({ staffId, widths: initial })
  const widths = preference.staffId === staffId ? preference.widths : initial
  useEffect(() => {
    try {
      localStorage.setItem(storageKey(staffId), JSON.stringify(widths))
    } catch {
      /* Widths remain usable when browser preferences cannot be stored. */
    }
  }, [staffId, widths])
  const resize = (panel: keyof PanelWidths, width: number) =>
    setPreference((current) => ({
      staffId,
      widths: {
        ...(current.staffId === staffId ? current.widths : initial),
        [panel]: bounded(width, panel === 'properties' ? 420 : 520),
      },
    }))
  return { widths, resize }
}
