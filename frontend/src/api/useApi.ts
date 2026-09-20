import { useCallback, useEffect, useRef, useState } from 'react'

interface State<T> {
  data: T | null
  error: unknown
  loading: boolean
}

/**
 * Runs `load` when the component mounts and exposes {data, loading, error}.
 * `load` must be a stable function (for example `api.getToday`), not one created on every render.
 * `setData` lets a page replace the data after an action without fetching again.
 */
export function useApi<T>(load: () => Promise<T>) {
  const [state, setState] = useState<State<T>>({ data: null, error: null, loading: true })
  const [attempt, setAttempt] = useState(0)
  // A request counter. Every load (and every setData) takes a new number; an answer is only used
  // while it still holds the newest number. So a slow, older answer can never overwrite newer data.
  const latest = useRef(0)

  useEffect(() => {
    // `cancelled` drops the answer of a request whose component already unmounted or re-ran the effect
    // (React <StrictMode> runs every effect twice in development).
    let cancelled = false
    const mine = ++latest.current
    setState((current) => ({ ...current, error: null, loading: true }))
    load().then(
      (data) => {
        if (!cancelled && mine === latest.current) setState({ data, error: null, loading: false })
      },
      (error: unknown) => {
        if (!cancelled && mine === latest.current) setState({ data: null, error, loading: false })
      },
    )
    return () => {
      cancelled = true
    }
  }, [load, attempt])

  const refetch = useCallback(() => setAttempt((n) => n + 1), [])
  const setData = useCallback((data: T) => {
    latest.current++ // any load still running is now older than this data
    setState({ data, error: null, loading: false })
  }, [])

  return { ...state, refetch, setData }
}
