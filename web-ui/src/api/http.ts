/**
 * Shared fetch wrapper for Streammux web-ui API calls.
 *
 * Behind OneLab Traefik + Authelia forward-auth, an expired session typically
 * yields HTTP 401 (often with a Location to auth.onelab) or a followed redirect
 * that lands on the Authelia HTML login page. Surface that as a browser redirect
 * instead of a generic API error banner.
 */

const AUTHELIA_HOST = "auth.onelab.alticeusa.net";

const AUTHELIA_MARKERS = [
  "authelia",
  "auth.onelab.alticeusa.net",
  "name=\"username\"",
  "id=\"username-textfield\"",
];

export class AuthRedirectError extends Error {
  constructor(message = "Redirecting to login…") {
    super(message);
    this.name = "AuthRedirectError";
  }
}

export function isAutheliaUrl(url: string): boolean {
  try {
    const parsed = new URL(url, typeof window !== "undefined" ? window.location.origin : "http://localhost");
    return parsed.hostname === AUTHELIA_HOST || parsed.hostname.endsWith(`.${AUTHELIA_HOST}`);
  } catch {
    return url.includes(AUTHELIA_HOST);
  }
}

export function looksLikeAutheliaLoginHtml(body: string): boolean {
  if (!body) return false;
  const lower = body.toLowerCase();
  if (!lower.includes("<html") && !lower.includes("<!doctype")) return false;
  return AUTHELIA_MARKERS.some((m) => lower.includes(m));
}

function currentReturnUrl(): string {
  if (typeof window === "undefined") return "/";
  return window.location.href;
}

/** Build Authelia login URL with rd= back to the current UI page (incl. hash). */
export function buildAutheliaLoginUrl(rd: string = currentReturnUrl()): string {
  return `https://${AUTHELIA_HOST}/?rd=${encodeURIComponent(rd)}`;
}

/**
 * Navigate the browser to Authelia. Prefer an absolute Location from the
 * response when it already points at the auth portal (includes a proper rd).
 */
export function redirectToAutheliaLogin(locationHeader?: string | null): never {
  let target = buildAutheliaLoginUrl();
  if (locationHeader) {
    try {
      const absolute = new URL(
        locationHeader,
        typeof window !== "undefined" ? window.location.origin : `https://${AUTHELIA_HOST}`,
      ).toString();
      if (isAutheliaUrl(absolute)) target = absolute;
    } catch {
      /* keep constructed URL */
    }
  }
  if (typeof window !== "undefined") {
    window.location.assign(target);
  }
  throw new AuthRedirectError();
}

function headerGet(response: Response, name: string): string | null {
  try {
    return response.headers?.get(name) ?? null;
  } catch {
    return null;
  }
}

async function responseLooksLikeLogin(response: Response): Promise<boolean> {
  if (response.type === "opaqueredirect") return true;

  if (typeof response.url === "string" && response.url && isAutheliaUrl(response.url)) {
    return true;
  }

  const location = headerGet(response, "Location") ?? headerGet(response, "location");
  if (location && isAutheliaUrl(location)) return true;

  if (response.status === 401) return true;

  const contentType = headerGet(response, "content-type") ?? "";
  const isHtml = contentType.includes("text/html") || contentType.includes("application/xhtml");
  // Only inspect bodies that are HTML or non-JSON error statuses that Authelia
  // sometimes returns. Never treat application/json API errors as login pages.
  if (!isHtml && response.status !== 403) return false;
  if (contentType.includes("application/json") || contentType.includes("+json")) return false;

  try {
    const text = await response.clone().text();
    if (looksLikeAutheliaLoginHtml(text)) return true;
    // HTML response on an API call almost always means the edge served a login page.
    if (isHtml && (text.includes("<html") || text.includes("<!DOCTYPE") || text.includes("<!doctype"))) {
      return true;
    }
  } catch {
    /* ignore body read failures */
  }

  return false;
}

/**
 * If the response indicates an Authelia / edge unauthenticated state, redirect
 * the browser to the login portal. Otherwise return without side effects.
 */
export async function redirectIfUnauthenticated(response: Response): Promise<void> {
  if (!(await responseLooksLikeLogin(response))) return;
  const location = headerGet(response, "Location") ?? headerGet(response, "location");
  redirectToAutheliaLogin(location);
}

function csrfToken(): string | undefined {
  if (typeof document === "undefined") return undefined;
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : undefined;
}

/** fetch() that redirects to Authelia when the session has expired. */
export async function apiFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const headers = new Headers(init?.headers);
  const method = (init?.method ?? "GET").toUpperCase();
  if (method !== "GET" && method !== "HEAD" && method !== "OPTIONS") {
    const token = csrfToken();
    if (token && !headers.has("X-XSRF-TOKEN")) {
      headers.set("X-XSRF-TOKEN", token);
    }
  }
  const response = await fetch(input, {
    ...init,
    headers,
    credentials: init?.credentials ?? "same-origin",
  });
  await redirectIfUnauthenticated(response);
  return response;
}
