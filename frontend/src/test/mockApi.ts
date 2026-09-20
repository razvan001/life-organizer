import { vi } from 'vitest'

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** An RFC 7807 error answer, the shape the backend's GlobalExceptionHandler produces. */
export function problemResponse(status: number, title: string, detail: string): Response {
  return new Response(JSON.stringify({ status, title, detail }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })
}

type Handler = (init: RequestInit | undefined) => Response | Promise<Response>

/**
 * Replaces the global fetch. `routes` maps "METHOD /path" to a function that builds the answer
 * (a function, because a Response body can only be read once). Any other request fails the test.
 * Returns the mock so a test can inspect the calls. Call `vi.unstubAllGlobals()` afterwards.
 */
export function mockApi(routes: Record<string, Handler>) {
  const fetchMock = vi.fn(async (path: string, init?: RequestInit) => {
    const key = `${init?.method ?? 'GET'} ${path}`
    const handler = routes[key]
    if (!handler) {
      throw new Error(`Unexpected request: ${key}`)
    }
    return handler(init)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}
