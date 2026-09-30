import { useState } from 'react'
import { api, errorMessage } from '../api/client'
import type { MemoryResult, NoteDto } from '../api/types'
import { useApi } from '../api/useApi'

interface Draft {
  id: number | null
  title: string
  body: string
}

const EMPTY: Draft = { id: null, title: '', body: '' }

function toDraft(note: NoteDto): Draft {
  return { id: note.id, title: note.title, body: note.body }
}

/** Notes you write yourself, plus semantic search over them and over past days. */
export function NotesPage() {
  const { data: notes, error: loadError, refetch } = useApi(api.listNotes)
  const [draft, setDraft] = useState<Draft>(EMPTY)
  const [saved, setSaved] = useState<Draft>(EMPTY)
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<MemoryResult[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run(action: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (failure) {
      setError(errorMessage(failure))
    } finally {
      setBusy(false)
    }
  }

  function search() {
    return run(async () => setResults(await api.searchMemory(query.trim())))
  }

  function isDirty() {
    return draft.title !== saved.title || draft.body !== saved.body
  }

  function replaceDraft(next: Draft) {
    if (isDirty() && !window.confirm('Discard unsaved changes?')) return
    setDraft(next)
    setSaved(next)
  }

  function save() {
    return run(async () => {
      const request = { title: draft.title, body: draft.body }
      const creating = draft.id === null
      const savedNote = creating ? await api.createNote(request) : await api.updateNote(draft.id!, request)
      // A new note goes to the list and the form is cleared for the next one, so a second Save never
      // silently overwrites the first. An edited note stays open.
      const next = creating ? EMPTY : toDraft(savedNote)
      setDraft(next)
      setSaved(next)
      refetch()
    })
  }

  function remove() {
    if (draft.id === null || !window.confirm('Delete this note?')) return
    const id = draft.id
    return run(async () => {
      await api.deleteNote(id)
      setDraft(EMPTY)
      setSaved(EMPTY)
      refetch()
    })
  }

  function open(id: string) {
    const note = notes?.find((candidate) => String(candidate.id) === id)
    if (note) {
      replaceDraft(toDraft(note))
    } else {
      setError('That note no longer exists.')
    }
  }

  return (
    <section>
      <h2>Notes</h2>

      <form
        onSubmit={(event) => {
          event.preventDefault()
          search()
        }}
      >
        <div className="field">
          <label htmlFor="memory-query">Search your notes and past days</label>
          <input id="memory-query" type="search" maxLength={500} value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
        <button type="submit" disabled={busy || query.trim() === ''}>
          Search
        </button>
      </form>
      {error && <p role="alert">{error}</p>}

      {results && results.length === 0 && <p className="muted">Nothing found.</p>}
      {results && results.length > 0 && (
        <ul className="memory-results" aria-label="Search results">
          {results.map((result) => (
            <li key={`${result.sourceType}-${result.sourceId}-${result.snippet}`}>
              <span className="badge">{result.sourceType === 'NOTE' ? 'Note' : 'Day'}</span>{' '}
              <strong>{result.title}</strong> <span className="muted">{result.score.toFixed(2)}</span>
              <p>{result.snippet}</p>
              {result.sourceType === 'NOTE' && (
                <button type="button" disabled={busy} onClick={() => open(result.sourceId)}>
                  Open
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      <h3>Your notes</h3>
      {loadError != null && notes === null && <p role="alert">{errorMessage(loadError)}</p>}
      {notes && notes.length === 0 && <p className="muted">No notes yet.</p>}
      {notes && notes.length > 0 && (
        <ul className="note-list">
          {notes.map((note) => (
            <li key={note.id} className={note.id === draft.id ? 'selected' : undefined}>
              <button
                type="button"
                disabled={busy}
                aria-current={note.id === draft.id ? 'true' : undefined}
                onClick={() => replaceDraft(toDraft(note))}
              >
                {note.title}
              </button>{' '}
              <span className="muted">{new Date(note.updatedAt).toLocaleDateString()}</span>
            </li>
          ))}
        </ul>
      )}
      <button type="button" disabled={busy} onClick={() => replaceDraft(EMPTY)}>
        New note
      </button>

      <h3>{draft.id === null ? 'New note' : 'Edit note'}</h3>
      <div className="field">
        <label htmlFor="note-title">Title</label>
        <input
          id="note-title"
          type="text"
          maxLength={255}
          value={draft.title}
          disabled={busy}
          onChange={(e) => setDraft({ ...draft, title: e.target.value })}
        />
      </div>
      <div className="field">
        <label htmlFor="note-body">Body</label>
        <textarea
          id="note-body"
          rows={10}
          maxLength={20000}
          value={draft.body}
          disabled={busy}
          onChange={(e) => setDraft({ ...draft, body: e.target.value })}
        />
      </div>
      <button type="button" disabled={busy || draft.title.trim() === ''} onClick={save}>
        Save note
      </button>{' '}
      {draft.id !== null && (
        <button type="button" disabled={busy} onClick={remove}>
          Delete note
        </button>
      )}
    </section>
  )
}
