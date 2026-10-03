import { expect, it } from 'vitest'
import {
  passwordValidationMessage,
  profileValidationMessage,
} from './customerRegistrationValidation'
import { decodeConsentBlocks } from './api/consentDocument'
it('uses server code-point bounds for password and profile values', () => {
  expect(passwordValidationMessage('abcdefghij🙂')).not.toBeNull()
  expect(passwordValidationMessage('🙂'.repeat(12))).toBeNull()
  expect(passwordValidationMessage('🙂'.repeat(128))).toBeNull()
  expect(passwordValidationMessage('a'.repeat(129))).not.toBeNull()
  expect(profileValidationMessage('displayName', '🙂'.repeat(100))).toBeNull()
  expect(
    profileValidationMessage('displayName', 'a'.repeat(101)),
  ).not.toBeNull()
  expect(profileValidationMessage('companyName', 'a'.repeat(160))).toBeNull()
  expect(
    profileValidationMessage('companyName', 'a'.repeat(161)),
  ).not.toBeNull()
})
it.each([
  'javascript:alert(1)',
  'http://example.test',
  'https://user:password@example.test',
])('rejects unsafe consent links: %s', (url) => {
  expect(() =>
    decodeConsentBlocks([{ type: 'link', text: '약관', url }]),
  ).toThrow()
})
it('rejects unsupported headings and list shapes', () => {
  expect(() =>
    decodeConsentBlocks([{ type: 'heading', level: 1, text: '약관' }]),
  ).toThrow()
  expect(() =>
    decodeConsentBlocks([{ type: 'list', items: ['약관'] }]),
  ).toThrow()
})
