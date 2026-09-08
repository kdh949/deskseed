import {
  decodeAgentMacroDefinition,
  requestStaffResource,
} from '../../api/client'
import type { AgentMacroDefinition } from '../../api/types'
export type MacroScope = 'PERSONAL' | 'SHARED'
export type MacroDraft = { name: string; actions: Record<string, unknown>[] }
export type MacroHistory = {
  versions: {
    version: number
    name: string
    createdByDisplay: string
    createdAt: string
  }[]
  activations: {
    version: number
    state: 'ACTIVE' | 'INACTIVE'
    actorDisplay: string
    occurredAt: string
  }[]
}
const base = (scope: MacroScope) =>
  scope === 'PERSONAL'
    ? ('/api/v1/agent/personal-macros' as const)
    : ('/api/v1/admin/shared-macros' as const)
const record = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v)
export function decodeHistory(v: unknown): MacroHistory | undefined {
  if (!record(v) || !Array.isArray(v.versions) || !Array.isArray(v.activations))
    return undefined
  if (
    !v.versions.every(
      (x) =>
        record(x) &&
        Number.isSafeInteger(x.version) &&
        Number(x.version) > 0 &&
        ['name', 'createdByDisplay', 'createdAt'].every(
          (k) => typeof x[k] === 'string',
        ),
    )
  )
    return undefined
  if (
    !v.activations.every(
      (x) =>
        record(x) &&
        Number.isSafeInteger(x.version) &&
        Number(x.version) > 0 &&
        ['ACTIVE', 'INACTIVE'].includes(String(x.state)) &&
        typeof x.actorDisplay === 'string' &&
        typeof x.occurredAt === 'string',
    )
  )
    return undefined
  return v as MacroHistory
}
export const listManagedMacros = (scope: MacroScope) =>
  requestStaffResource(base(scope), (body) => {
    if (!Array.isArray(body)) return undefined
    const values = body.map(decodeAgentMacroDefinition)
    return values.some((v) => !v)
      ? undefined
      : (values as AgentMacroDefinition[])
  })
export const saveMacro = (
  scope: MacroScope,
  draft: MacroDraft,
  existing?: AgentMacroDefinition,
) =>
  requestStaffResource(
    existing ? `${base(scope)}/${existing.id}/versions` : base(scope),
    decodeAgentMacroDefinition,
    { method: 'POST', body: draft, version: existing?.aggregateVersion },
  )
export const activateMacro = (
  scope: MacroScope,
  macro: AgentMacroDefinition,
  active: boolean,
) =>
  requestStaffResource(
    `${base(scope)}/${macro.id}/activation`,
    decodeAgentMacroDefinition,
    {
      method: active ? 'PUT' : 'DELETE',
      body: active ? { version: macro.currentVersion } : undefined,
      version: macro.aggregateVersion,
    },
  )
export const getMacroHistory = (scope: MacroScope, id: string) =>
  requestStaffResource(`${base(scope)}/${id}/history`, decodeHistory)
export function editableDraft(macro?: AgentMacroDefinition) {
  const actions = (macro?.actions ?? []).filter(record)
  const comment = actions.find((a) => a.type === 'COMMENT')
  return {
    name: macro?.name ?? '',
    template: String(comment?.template ?? ''),
    visibility: comment?.visibility === 'INTERNAL' ? 'INTERNAL' : 'PUBLIC',
    status: String(actions.find((a) => a.type === 'STATUS')?.status ?? ''),
    priority: String(
      actions.find((a) => a.type === 'PRIORITY')?.priority ?? '',
    ),
    originalActions: actions,
    preserved: actions.filter(
      (a) => !['COMMENT', 'STATUS', 'PRIORITY'].includes(String(a.type)),
    ),
  }
}
export function toMacroDraft(
  draft: ReturnType<typeof editableDraft>,
): MacroDraft {
  const replacements: Record<string, Record<string, unknown> | undefined> = {
    STATUS: draft.status ? { type: 'STATUS', status: draft.status } : undefined,
    PRIORITY: draft.priority
      ? { type: 'PRIORITY', priority: draft.priority }
      : undefined,
    COMMENT: draft.template.trim()
      ? {
          type: 'COMMENT',
          visibility: draft.visibility,
          template: draft.template,
        }
      : undefined,
  }
  const actions: Record<string, unknown>[] = []
  for (const action of draft.originalActions) {
    const key = String(action.type)
    if (key in replacements) {
      if (replacements[key]) actions.push(replacements[key]!)
      delete replacements[key]
    } else actions.push(action)
  }
  actions.push(
    ...Object.values(replacements).filter(
      (a): a is Record<string, unknown> => !!a,
    ),
  )
  return { name: draft.name, actions }
}
