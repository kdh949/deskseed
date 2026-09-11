export const registrationFieldLimits = {
  email: 254,
  password: 128,
  displayName: 100,
  companyName: 160,
} as const

export function passwordValidationMessage(password: string): string | null {
  const length = [...password].length
  if (length < 12 || length > registrationFieldLimits.password)
    return '비밀번호는 12~128자로 입력해 주세요.'
  if (/\p{Cc}/u.test(password))
    return '비밀번호에 제어 문자를 사용할 수 없습니다.'
  return null
}

export function profileValidationMessage(
  field: 'displayName' | 'companyName',
  value: string,
): string | null {
  const label = field === 'displayName' ? '이름' : '회사명'
  if (
    !value.trim() ||
    [...value.trim()].length > registrationFieldLimits[field]
  )
    return `${label}은 1~${registrationFieldLimits[field]}자로 입력해 주세요.`
  if (/[<>\p{Cc}]/u.test(value))
    return `${label}에 사용할 수 없는 문자가 있습니다.`
  return null
}
