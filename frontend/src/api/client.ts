import type {
  ApplyEventResponse,
  ApplyEventsRequest,
  ApplyEventsResponse,
  CommandRequest,
  CommandResponse,
  ConfigResponse,
  PlanEventRequest,
  SetupExtractionRequest,
  SetupParseRequest,
  SetupParseResponse,
  SetupPayload,
  TodayPlanResponse,
} from './types'

/** A non-2xx answer from the backend, read from its RFC 7807 problem body. */
export class ApiError extends Error {
  readonly status: number
  readonly title: string
  readonly detail: string

  constructor(status: number, title: string, detail: string) {
    super(detail || title)
    this.name = 'ApiError'
    this.status = status
    this.title = title
    this.detail = detail
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const problem = (await response.json()) as { title?: string; detail?: string }
    return new ApiError(response.status, problem.title ?? response.statusText, problem.detail ?? '')
  } catch {
    // the body was not JSON (for example a proxy error page)
    return new ApiError(response.status, response.statusText || 'Request failed', '')
  }
}

async function send<T>(method: string, path: string, body?: unknown): Promise<T> {
  const init: RequestInit = { method }
  if (body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' }
    init.body = JSON.stringify(body)
  }
  const response = await fetch(path, init)
  if (!response.ok) {
    throw await toApiError(response)
  }
  return (await response.json()) as T
}

/** The one place that knows the URLs. Paths are relative: Vite proxies /api to the backend. */
export const api = {
  parseSetup: (request: SetupParseRequest) => send<SetupParseResponse>('POST', '/api/setup/parse', request),
  extractSetup: (request: SetupExtractionRequest) => send<SetupParseResponse>('POST', '/api/setup/extract', request),
  getSetup: () => send<SetupPayload>('GET', '/api/setup'),
  saveSetup: (setup: SetupPayload) => send<SetupPayload>('PUT', '/api/setup', setup),
  getToday: () => send<TodayPlanResponse>('GET', '/api/plan/today'),
  applyEvent: (event: PlanEventRequest) => send<ApplyEventResponse>('POST', '/api/plan/today/events', event),
  getConfig: () => send<ConfigResponse>('GET', '/api/config'),
  proposeCommand: (request: CommandRequest) => send<CommandResponse>('POST', '/api/assistant/command', request),
  applyEvents: (request: ApplyEventsRequest) => send<ApplyEventsResponse>('POST', '/api/plan/today/events/batch', request),
}

/** True when the backend says no setup is saved yet: the UI then sends the user to /setup. */
export function isSetupRequired(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404 && error.title === 'Setup required'
}

export function errorMessage(error: unknown): string {
  // A 5xx without a problem+json body did not come from our backend: it is the Vite dev proxy
  // answering (with an empty 500) for a backend that is not running.
  if (error instanceof ApiError && (error.status < 500 || error.detail !== '')) {
    return error.message
  }
  return 'Could not reach the server. Is the backend running on port 8080?'
}
