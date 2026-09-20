import { NavLink, Route, Routes } from 'react-router-dom'
import { SetupPage } from './pages/SetupPage'
import { TodayPage } from './pages/TodayPage'

export default function App() {
  return (
    <div className="app">
      <header>
        <h1>Life Organizer</h1>
        <nav>
          <NavLink to="/" end>
            Today
          </NavLink>
          <NavLink to="/setup">Setup</NavLink>
        </nav>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<TodayPage />} />
          <Route path="/setup" element={<SetupPage />} />
        </Routes>
      </main>
    </div>
  )
}
