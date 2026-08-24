const STORAGE_PREFIX = "teacup.share-login-fragment:";
const PUBLIC_ID_PATTERN = /^[A-Za-z0-9_-]+$/;
const SECRET_FRAGMENT_PATTERN = /^#[A-Za-z0-9_-]+$/;
const SHARE_PATH_PATTERN = /^\/shares\/([A-Za-z0-9_-]+)$/;

export function rememberShareLoginContinuation(publicId: string, fragment: string): void {
  if (!PUBLIC_ID_PATTERN.test(publicId) || !SECRET_FRAGMENT_PATTERN.test(fragment)) return;
  try {
    window.sessionStorage.setItem(`${STORAGE_PREFIX}${publicId}`, fragment);
  } catch {
    // The share remains viewable; only login continuation is unavailable.
  }
}

export function restoreShareLoginContinuation(returnTo: string): string {
  const match = SHARE_PATH_PATTERN.exec(returnTo);
  if (!match) return returnTo;
  try {
    const fragment = window.sessionStorage.getItem(`${STORAGE_PREFIX}${match[1]}`);
    return fragment && SECRET_FRAGMENT_PATTERN.test(fragment) ? `${returnTo}${fragment}` : returnTo;
  } catch {
    return returnTo;
  }
}

export function clearShareLoginContinuation(publicId: string): void {
  if (!PUBLIC_ID_PATTERN.test(publicId)) return;
  try {
    window.sessionStorage.removeItem(`${STORAGE_PREFIX}${publicId}`);
  } catch {
    // Storage may be unavailable in hardened browser contexts.
  }
}
