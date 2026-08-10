import { useCallback, useEffect, useState } from 'react'

const PROPERTY_MIN = 240
const PROPERTY_MAX = 420
const CONTEXT_MIN = 240
const CONTEXT_MAX = 520

interface PanelPreferences {
  propertyWidth: number
  contextWidth: number
  contextCollapsed: boolean
}

const defaults: PanelPreferences = {
  propertyWidth: 300,
  contextWidth: 320,
  contextCollapsed: false,
}

export function usePanelPreferences(staffId: string) {
  const storageKey = `deskseed:agent:${staffId}:workspace-panels:v1`
  const [preferences, setPreferences] = useState<PanelPreferences>(() =>
    readPreferences(storageKey),
  )

  useEffect(() => {
    setPreferences(readPreferences(storageKey))
  }, [storageKey])

  useEffect(() => {
    localStorage.setItem(storageKey, JSON.stringify(preferences))
  }, [preferences, storageKey])

  const setPropertyWidth = useCallback((width: number) => {
    setPreferences((current) => ({
      ...current,
      propertyWidth: clamp(width, PROPERTY_MIN, PROPERTY_MAX),
    }))
  }, [])

  const setContextWidth = useCallback((width: number) => {
    setPreferences((current) => ({
      ...current,
      contextWidth: clamp(width, CONTEXT_MIN, CONTEXT_MAX),
    }))
  }, [])

  const toggleContext = useCallback(() => {
    setPreferences((current) => ({
      ...current,
      contextCollapsed: !current.contextCollapsed,
    }))
  }, [])

  return { preferences, setPropertyWidth, setContextWidth, toggleContext }
}

function readPreferences(storageKey: string): PanelPreferences {
  try {
    const stored = JSON.parse(
      localStorage.getItem(storageKey) ?? 'null',
    ) as Partial<PanelPreferences> | null
    if (!stored) return defaults
    return {
      propertyWidth: clamp(
        stored.propertyWidth ?? defaults.propertyWidth,
        PROPERTY_MIN,
        PROPERTY_MAX,
      ),
      contextWidth: clamp(
        stored.contextWidth ?? defaults.contextWidth,
        CONTEXT_MIN,
        CONTEXT_MAX,
      ),
      contextCollapsed: stored.contextCollapsed === true,
    }
  } catch {
    return defaults
  }
}

function clamp(value: number, minimum: number, maximum: number) {
  return Math.min(maximum, Math.max(minimum, value))
}
