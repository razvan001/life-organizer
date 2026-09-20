import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import type { SetupParseResponse } from '../api/types'
import { SetupPreview } from '../components/SetupPreview'
import { EXAMPLE_CONSTRAINTS, EXAMPLE_GOALS } from '../setup/example'

export function SetupPage() {
  const navigate = useNavigate()
  const [goalsText, setGoalsText] = useState(EXAMPLE_GOALS)
  const [constraintsText, setConstraintsText] = useState(EXAMPLE_CONSTRAINTS)
  const [preview, setPreview] = useState<SetupParseResponse | null>(null)
  const [previewTitle, setPreviewTitle] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // On a return visit, show what is already saved. A failure here is not worth an error message:
  // the Parse button reports an unreachable backend the moment it is used.
  useEffect(() => {
    let cancelled = false
    api.getSetup().then(
      (saved) => {
        if (cancelled || saved.goals.length === 0) return
        // do not overwrite a parse the user already started
        setPreview((current) => current ?? { ...saved, warnings: [] })
        setPreviewTitle((current) => current || 'Saved setup')
      },
      () => {},
    )
    return () => {
      cancelled = true
    }
  }, [])

  async function parse() {
    setBusy(true)
    setError(null)
    try {
      setPreview(await api.parseSetup({ goalsText, constraintsText }))
      setPreviewTitle('Parsed setup (not saved yet)')
    } catch (failure) {
      setError(errorMessage(failure))
    } finally {
      setBusy(false)
    }
  }

  async function save() {
    if (!preview) return
    setBusy(true)
    setError(null)
    try {
      await api.saveSetup({
        categories: preview.categories,
        goals: preview.goals,
        constraints: preview.constraints,
      })
      navigate('/')
    } catch (failure) {
      setError(errorMessage(failure))
      setBusy(false)
    }
  }

  return (
    <section>
      <h2>Setup</h2>

      <div className="field">
        <label htmlFor="goals-text">Goals</label>
        <textarea id="goals-text" rows={16} value={goalsText} onChange={(event) => setGoalsText(event.target.value)} />
      </div>
      <div className="field">
        <label htmlFor="constraints-text">Constraints (one per line)</label>
        <textarea
          id="constraints-text"
          rows={7}
          value={constraintsText}
          onChange={(event) => setConstraintsText(event.target.value)}
        />
      </div>

      <button type="button" disabled={busy} onClick={parse}>
        Parse
      </button>
      {error && <p role="alert">{error}</p>}

      {preview && (
        <>
          <h3>{previewTitle}</h3>
          <SetupPreview value={preview} onChange={setPreview} />
          <p className="muted">
            Days that are already planned keep their plan. Days that are not planned yet use this setup.
          </p>
          <button type="button" disabled={busy || preview.goals.length === 0} onClick={save}>
            Save
          </button>
        </>
      )}
    </section>
  )
}
