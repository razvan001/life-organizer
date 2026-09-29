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
  const { data: notes, refetch } = useApi(api.listNotes)
  const [draft, setDraft] = useState<Draft>(EMPTY)
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

  function save() {
    return run(async () => {
      const request = { title: draft.title, body: draft.body }
      const saved = draft.id === null ? await api.createNote(request) : await api.updateNote(draft.id, request)
      setDraft(toDraft(saved))
      refetch()
    })
  }

  function remove() {
    if (draft.id === null || !window.confirm('Delete this note?')) return
    const id = draft.id
    return run(async () => {
      await api.deleteNote(id)
      setDraft(EMPTY)
      refetch()
    })
  }

  function open(id: string) {
    const note = notes?.find((candidate) => String(candidate.id) === id)
    if (note) setDraft(toDraft(note))
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
                <button type="button" onClick={() => open(result.sourceId)}>
                  Open
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      <h3>Your notes</h3>
      {notes && notes.length === 0 && <p className="muted">No notes yet.</p>}
      {notes && notes.length > 0 && (
        <ul className="note-list">
          {notes.map((note) => (
            <li key={note.id}>
              <button type="button" onClick={() => setDraft(toDraft(note))}>
                {note.title}
              </button>{' '}
              <span className="muted">{note.updatedAt.slice(0, 10)}</span>
            </li>
          ))}
        </ul>
      )}
      <button type="button" disabled={busy} onClick={() => setDraft(EMPTY)}>
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
