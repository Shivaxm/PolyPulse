import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom';
import Dashboard from './pages/Dashboard';
import MarketDetail from './pages/MarketDetail';
import Correlations from './pages/Correlations';

export default function App() {
  const navLinkClass = ({ isActive }: { isActive: boolean }) =>
    `relative px-3 py-1.5 text-sm font-medium transition-colors duration-200 rounded-md ${
      isActive
        ? 'text-white bg-white/[0.08]'
        : 'text-[var(--text-muted)] hover:text-[var(--text-secondary)] hover:bg-white/[0.04]'
    }`;

  return (
    <BrowserRouter>
      <div style={{ minHeight: '100vh', background: 'var(--bg-base)', color: 'var(--text-primary)' }}>
        {/* Header */}
        <header style={{
          position: 'sticky', top: 0, zIndex: 50,
          borderBottom: '1px solid var(--border-subtle)',
          background: 'rgba(10, 14, 23, 0.85)',
          backdropFilter: 'blur(12px)',
          WebkitBackdropFilter: 'blur(12px)',
        }}>
          <div style={{ maxWidth: '80rem', margin: '0 auto', padding: '0 1.5rem', display: 'flex', alignItems: 'center', height: '3.5rem', gap: '2rem' }}>
            <NavLink to="/" style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', textDecoration: 'none' }}>
              <div style={{
                width: '1.75rem', height: '1.75rem', borderRadius: '0.5rem',
                background: 'linear-gradient(135deg, #3b82f6, #8b5cf6)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: '0.75rem', fontWeight: 700, color: 'white',
                fontFamily: 'var(--font-mono)',
              }}>
                P
              </div>
              <span style={{ fontSize: '1.125rem', fontWeight: 700, color: 'white', letterSpacing: '-0.025em', fontFamily: 'var(--font-sans)' }}>
                PolyPulse
              </span>
            </NavLink>
            <nav style={{ display: 'flex', gap: '0.25rem' }}>
              <NavLink to="/" end className={navLinkClass}>Markets</NavLink>
              <NavLink to="/correlations" className={navLinkClass}>Correlations</NavLink>
            </nav>
          </div>
        </header>

        {/* Main */}
        <main style={{ maxWidth: '80rem', margin: '0 auto', padding: '1.5rem' }}>
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
