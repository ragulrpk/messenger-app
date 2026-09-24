// 2026-09-23: Mirror the backend policy; preserve spaces and count BCrypt's UTF-8 byte limit.
export function validatePasswordChange(values) {
  const errors = {};
  for (const field of ["currentPassword", "newPassword"]) {
    const value = values[field];
    if (!value.trim()) errors[field] = "Enter a password.";
    else if (new TextEncoder().encode(value).length > 72)
      errors[field] = "Password must be 72 UTF-8 bytes or fewer.";
  }
  if (!errors.newPassword && values.newPassword.length < 12)
    errors.newPassword = "Use at least 12 characters.";
  if (!errors.newPassword && values.newPassword === values.currentPassword)
    errors.newPassword = "Choose a different new password.";
  if (!values.confirmPassword || values.newPassword !== values.confirmPassword)
    errors.confirmPassword = "Passwords do not match.";
  return errors;
}
