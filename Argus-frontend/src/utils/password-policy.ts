const MIN_PASSWORD_LENGTH = 8
const MAX_PASSWORD_LENGTH = 256
const BCRYPT_MAX_PASSWORD_BYTES = 72

export const PASSWORD_POLICY_HINT = '至少 8 位，且同时包含字母和数字'
export const PASSWORD_POLICY_PLACEHOLDER = `输入新密码（${PASSWORD_POLICY_HINT}）`

const encoder = new TextEncoder()

function hasLetterAndDigit(password: string): boolean {
  let hasLetter = false
  let hasDigit = false

  for (const char of password) {
    if (/\p{L}/u.test(char)) {
      hasLetter = true
    }
    if (/\p{N}/u.test(char)) {
      hasDigit = true
    }
    if (hasLetter && hasDigit) {
      return true
    }
  }

  return false
}

export function validatePasswordPolicy(password: string, label = '密码'): string | null {
  if (!password || password.length < MIN_PASSWORD_LENGTH) {
    return `${label}需${PASSWORD_POLICY_HINT}`
  }
  if (password.length > MAX_PASSWORD_LENGTH) {
    return `${label}长度不能超过 ${MAX_PASSWORD_LENGTH} 位`
  }
  if (encoder.encode(password).length > BCRYPT_MAX_PASSWORD_BYTES) {
    return `${label}长度超过安全上限，请控制在 ${BCRYPT_MAX_PASSWORD_BYTES} 字节以内`
  }
  if (!hasLetterAndDigit(password)) {
    return `${label}需${PASSWORD_POLICY_HINT}`
  }
  return null
}

export function validateChangePasswordForm(input: {
  currentPassword: string
  newPassword: string
  confirmPassword: string
}): string | null {
  if (!input.currentPassword.trim() || !input.newPassword.trim() || !input.confirmPassword.trim()) {
    return '请填写所有密码字段'
  }

  const passwordPolicyError = validatePasswordPolicy(input.newPassword, '新密码')
  if (passwordPolicyError) {
    return passwordPolicyError
  }

  if (input.newPassword !== input.confirmPassword) {
    return '两次输入的新密码不一致'
  }

  if (input.currentPassword === input.newPassword) {
    return '新密码不能与当前密码相同'
  }

  return null
}