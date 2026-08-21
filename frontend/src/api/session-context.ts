import { useSyncExternalStore } from "react";

export const SESSION_CONTEXT_HEADER = "X-Teacup-Session-Context";
export const SESSION_CONTEXT_QUERY = "sessionContext";

const STORAGE_KEY = "teacup.session-context";
const CONTEXT_PATTERN = /^[A-Za-z0-9._:-]{8,128}$/;
let memoryContext: string | undefined;
const subscribeToContext = () => () => undefined;

function createContext(): string {
  const randomUuid = globalThis.crypto?.randomUUID?.();
  if (randomUuid) return randomUuid;
  return `tab-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * Returns an opaque per-tab selector, not an authentication credential.
 * The server-side login session remains in the HttpOnly Cookie.
 */
export function getSessionContext(): string {
  if (typeof window === "undefined") {
    return memoryContext ?? (memoryContext = createContext());
  }

  try {
    const stored = window.sessionStorage.getItem(STORAGE_KEY);
    if (stored && CONTEXT_PATTERN.test(stored)) return stored;
    const context = createContext();
    window.sessionStorage.setItem(STORAGE_KEY, context);
    return context;
  } catch {
    return memoryContext ?? (memoryContext = createContext());
  }
}

/** Hydration-safe tab context for components that render browser-native URLs. */
export function useSessionContext(): string | null {
  return useSyncExternalStore(subscribeToContext, getSessionContext, () => null);
}

function isBackendResource(url: string): boolean {
  try {
    const parsed = new URL(url, window.location.origin);
    const apiBase = new URL(
      process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://127.0.0.1:8123/api/v1",
      window.location.origin,
    );
    return parsed.origin === apiBase.origin && parsed.pathname.startsWith(`${apiBase.pathname}/`);
  } catch {
    return false;
  }
}

/** Add the tab selector to browser-native private asset requests. */
export function withSessionContext(url: string, context?: string | null): string {
  if (typeof window === "undefined" || !isBackendResource(url) || context === null) return url;
  try {
    const parsed = new URL(url, window.location.origin);
    if (parsed.pathname.includes("/public/")) return url;
    parsed.searchParams.set(SESSION_CONTEXT_QUERY, context ?? getSessionContext());
    return parsed.toString();
  } catch {
    return url;
  }
}
