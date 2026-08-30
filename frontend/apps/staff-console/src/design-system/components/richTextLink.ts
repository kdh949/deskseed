export function isAllowedEditorLink(value: string) {
  try {
    return ['http:', 'https:', 'mailto:'].includes(
      new URL(value, 'https://deskseed.invalid').protocol,
    )
  } catch {
    return false
  }
}
