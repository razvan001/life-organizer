import { Navigate, NavLink, Route, Routes } from 'react-router-dom'
import { NotesPage } from './pages/NotesPage'
import { SetupPage } from './pages/SetupPage'
import { TodayPage } from './pages/TodayPage'

export default function App() {
  return (
    <div className="app">
      <header>
        <h1 className="brand">
          {/* the same mark as the browser tab icon (public/favicon.svg) */}
          <img src="/favicon.svg" alt="" width="24" height="24" />
          Life Organizer
        </h1>
        <nav>
          <NavLink to="/" end>
            Today
          </NavLink>
          <NavLink to="/setup">Setup</NavLink>
          <NavLink to="/notes">Notes</NavLink>
        </nav>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<TodayPage />} />
          <Route path="/setup" element={<SetupPage />} />
          <Route path="/notes" element={<NotesPage />} />
          {/* any other address goes to Today */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  )
}
