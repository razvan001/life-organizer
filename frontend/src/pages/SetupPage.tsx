import { useEffect, useRef, useState } from 'react'
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
  // True when a text box was edited after the last Parse: the preview no longer matches the text.
  const [stale, setStale] = useState(false)
  // True when the boxes were emptied because a saved setup exists (see the effect below).
  const [savedHint, setSavedHint] = useState(false)
  // Counts edits of either text box. A ref, not state: changing it must not re-render the page.
  const textEdits = useRef(0)

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
        // The boxes still hold the example, which is not what is saved. Leaving it there would invite
        // "Parse, Save" and silently replace the saved setup with the example. Empty them instead,
        // unless the user already started typing.
        if (textEdits.current === 0) {
          setGoalsText('')
          setConstraintsText('')
          setSavedHint(true)
        }
      },
      () => {},
    )
    return () => {
      cancelled = true
    }
  }, [])

  function editGoals(text: string) {
    textEdits.current++
    setGoalsText(text)
    if (preview) setStale(true)
  }

  function editConstraints(text: string) {
    textEdits.current++
    setConstraintsText(text)
    if (preview) setStale(true)
  }

  async function parse() {
    const editsAtStart = textEdits.current
    setBusy(true)
    setError(null)
    try {
      setPreview(await api.parseSetup({ goalsText, constraintsText }))
      setPreviewTitle('Parsed setup (not saved yet)')
      // If the user kept typing while the request ran, the preview belongs to older text.
      setStale(textEdits.current !== editsAtStart)
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

      {savedHint && (
        <p className="muted">
          Your saved setup is shown below. To replace it, paste new goals and constraints here and press Parse.
        </p>
      )}
      <div className="field">
        <label htmlFor="goals-text">Goals</label>
        <textarea id="goals-text" rows={16} value={goalsText} onChange={(event) => editGoals(event.target.value)} />
      </div>
      <div className="field">
        <label htmlFor="constraints-text">Constraints (one per line)</label>
        <textarea
          id="constraints-text"
          rows={7}
          value={constraintsText}
          onChange={(event) => editConstraints(event.target.value)}
        />
      </div>

      <button type="button" disabled={busy || goalsText.trim() === ''} onClick={parse}>
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
          {stale && (
            <p className="muted">The text changed since the last Parse. Press Parse to apply your edits.</p>
          )}
          <button type="button" disabled={busy || stale || preview.goals.length === 0} onClick={save}>
            Save
          </button>
        </>
      )}
    </section>
  )
}
