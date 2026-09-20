import { useCallback, useState } from 'react'
import { Navigate } from 'react-router-dom'
import { api, errorMessage, isSetupRequired } from '../api/client'
import type { PlanEventRequest } from '../api/types'
import { useApi } from '../api/useApi'
import { ChangeToast } from '../components/ChangeToast'
import { Timeline } from '../components/Timeline'

export function TodayPage() {
  const { data, loading, error, setData } = useApi(api.getToday)
  const [changes, setChanges] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const dismissToast = useCallback(() => setChanges([]), [])

  async function act(event: PlanEventRequest) {
    setBusy(true)
    setActionError(null)
    try {
      const result = await api.applyEvent(event)
      setData({ date: result.date, blocks: result.blocks, warnings: result.warnings })
      setChanges(result.changes)
    } catch (failure) {
      setActionError(errorMessage(failure))
    } finally {
      setBusy(false)
    }
  }

  if (isSetupRequired(error)) {
    return <Navigate to="/setup" replace />
  }
  if (loading && !data) {
    return <p>Loading today's plan…</p>
  }
  if (error || !data) {
    return <p role="alert">{errorMessage(error)}</p>
  }

  return (
    <section>
      <h2>Today's plan</h2>
      <p className="muted">{data.date}</p>

      {data.warnings.length > 0 && (
        <ul className="warnings">
          {data.warnings.map((warning) => (
            <li key={warning}>{warning}</li>
          ))}
        </ul>
      )}
      {actionError && <p role="alert">{actionError}</p>}

      <Timeline blocks={data.blocks} busy={busy} onAction={act} />
      <ChangeToast changes={changes} onDismiss={dismissToast} />
    </section>
  )
}
