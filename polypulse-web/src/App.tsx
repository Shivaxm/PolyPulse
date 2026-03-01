import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom';
import Dashboard from './pages/Dashboard';
import MarketDetail from './pages/MarketDetail';
import Correlations from './pages/Correlations';

export default function App() {
  return (
    <BrowserRouter>
      <div className="min-h-screen bg-gray-900 text-gray-100">
        <header className="border-b border-gray-800 bg-gray-900 sticky top-0 z-50">
          <div className="max-w-7xl mx-auto px-4 py-3 flex items-center gap-6">
            <NavLink to="/" className="text-xl font-bold text-white tracking-tight">
              PolyPulse
            </NavLink>
            <nav className="flex gap-4 text-sm">
              <NavLink
                to="/"
                end
                className={({ isActive }) =>
                  isActive ? 'text-indigo-400' : 'text-gray-400 hover:text-white'
                }
              >
                Dashboard
              </NavLink>
              <NavLink
                to="/correlations"
                className={({ isActive }) =>
                  isActive ? 'text-indigo-400' : 'text-gray-400 hover:text-white'
                }
              >
                Correlations
              </NavLink>
            </nav>
          </div>
        </header>

        <main className="max-w-7xl mx-auto px-4 py-6">
          <Routes>
            <Route path="/" element={<Dashboard />} />
            <Route path="/market/:id" element={<MarketDetail />} />
            <Route path="/correlations" element={<Correlations />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  );
}
