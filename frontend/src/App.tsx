import { useCallback, useEffect, useState } from 'react'
import { api } from './api'
import type { Filters as FilterState, Level, Signal } from './types'
import { Filters } from './components/Filters'
import { SignalDetail } from './components/SignalDetail'
import { LevelDetail } from './components/LevelDetail'
import { price, timestamp } from './components/format'

type Tab = 'signals' | 'levels' | 'config'

const EMPTY_FILTERS: FilterState = { symbol: '', timeframe: '', type: '', status: '' }

export default function App() {
  const [tab, setTab] = useState<Tab>('signals')
  const [filters, setFilters] = useState<FilterState>(EMPTY_FILTERS)
  const [signals, setSignals] = useState<Signal[]>([])
  const [levels, setLevels] = useState<Level[]>([])
  const [selectedSignal, setSelectedSignal] = useState<Signal | null>(null)
  const [selectedLevel, setSelectedLevel] = useState<Level | null>(null)
  const [config, setConfig] = useState<Record<string, unknown> | null>(null)
  const [status, setStatus] = useState<Record<string, unknown> | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      if (tab === 'signals') {
        const page = await api.signals({ ...filters, size: 200 })
        setSignals(page.items)
      } else if (tab === 'levels') {
        const page = await api.levels({ ...filters, size: 200 })
        setLevels(page.items)
      } else {
        setConfig(await api.config())
      }
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setLoading(false)
    }
  }, [tab, filters])

  useEffect(() => {
    void load()
  }, [load])

  // Poll the pipeline status so the header reflects reality without a manual refresh.
  useEffect(() => {
    let cancelled = false
    const tick = () => api.status().then((s) => !cancelled && setStatus(s)).catch(() => {})
    void tick()
    const handle = setInterval(tick, 15_000)
    return () => {
      cancelled = true
      clearInterval(handle)
    }
  }, [])

  const stream = status?.liveStream as { connected?: boolean; reconnects?: number } | undefined
  const bootstrap = status?.bootstrap as string | undefined

  return (
    <div className="app">
      <header className="header">
        <div className="brand">crypto<span>·</span>signals</div>
        <nav className="tabs" role="tablist">
          {(['signals', 'levels', 'config'] as Tab[]).map((t) => (
            <button
              key={t}
              className="tab"
              role="tab"
              aria-selected={tab === t}
              onClick={() => setTab(t)}
            >
              {t}
            </button>
          ))}
        </nav>
        <div className="header-right">
          {bootstrap && <span className="pill">{bootstrap.toLowerCase()}</span>}
          <span className={`pill ${stream?.connected ? 'live' : 'down'}`}>
            {stream?.connected ? 'stream live' : 'stream down'}
          </span>
          {loading && <span className="pill">loading…</span>}
        </div>
      </header>

      {tab !== 'config' && (
        <Filters
          value={filters}
          onChange={setFilters}
          onRefresh={() => void load()}
          statusOptions={
            tab === 'signals'
              ? ['ACTIVE', 'INVALIDATED', 'EXPIRED']
              : ['ACTIVE', 'BROKEN', 'EXPIRED']
          }
        />
      )}

      {error && <div className="error">{error}</div>}

      {tab === 'signals' && (
        <div className="body">
          <div className="list-pane">
            {signals.length === 0 ? (
              <div className="empty">
                No signals yet. The engine emits one when a level reaches its third valid touch.
              </div>
            ) : (
              <table>
                <thead>
                  <tr>
                    <th>Detected (UTC)</th>
                    <th>Symbol</th>
                    <th>TF</th>
                    <th>Type</th>
                    <th>Level</th>
                    <th>Touches</th>
                    <th>Strength</th>
                    <th>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {signals.map((s) => (
                    <tr
                      key={s.id}
                      aria-selected={selectedSignal?.id === s.id}
                      onClick={() => setSelectedSignal(s)}
                    >
                      <td className="num">{timestamp(s.detectedAt)}</td>
                      <td>{s.symbol}</td>
                      <td className="num faint">{s.timeframe}</td>
                      <td className={`type-${s.type}`}>{s.type === 'RESISTANCE' ? 'RES' : 'SUP'}</td>
                      <td className="num">{price(s.price)}</td>
                      <td className="num dim">{s.touchCount}</td>
                      <td className="num dim">{s.strength.toFixed(2)}</td>
                      <td><span className={`badge ${s.status}`}>{s.status.toLowerCase()}</span></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
          {selectedSignal ? (
            <SignalDetail signal={selectedSignal} />
          ) : (
            <div className="detail-pane">
              <div className="empty">Select a signal to inspect its touches and chart.</div>
            </div>
          )}
        </div>
      )}

      {tab === 'levels' && (
        <div className="body">
          <div className="list-pane">
            {levels.length === 0 ? (
              <div className="empty">No levels discovered yet.</div>
            ) : (
              <table>
                <thead>
                  <tr>
                    <th>First seen (UTC)</th>
                    <th>Symbol</th>
                    <th>TF</th>
                    <th>Type</th>
                    <th>Price</th>
                    <th>Touches</th>
                    <th>Strength</th>
                    <th>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {levels.map((l) => (
                    <tr
                      key={l.id}
                      aria-selected={selectedLevel?.id === l.id}
                      onClick={() => setSelectedLevel(l)}
                    >
                      <td className="num">{timestamp(l.firstSeenAt)}</td>
                      <td>{l.symbol}</td>
                      <td className="num faint">{l.timeframe}</td>
                      <td className={`type-${l.type}`}>{l.type === 'RESISTANCE' ? 'RES' : 'SUP'}</td>
                      <td className="num">{price(l.price)}</td>
                      <td className="num dim">{l.touchCount}</td>
                      <td className="num dim">{l.strength.toFixed(2)}</td>
                      <td><span className={`badge ${l.status}`}>{l.status.toLowerCase()}</span></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
          {selectedLevel ? (
            <LevelDetail level={selectedLevel} />
          ) : (
            <div className="detail-pane">
              <div className="empty">Select a level to inspect its touches and chart.</div>
            </div>
          )}
        </div>
      )}

      {tab === 'config' && (
        <div className="full-pane">
          <div className="section-title" style={{ padding: '0 0 8px' }}>
            Effective detection rules — edit these in application.yml or via environment variables
          </div>
          <pre className="config">{JSON.stringify(config, null, 2)}</pre>
        </div>
      )}
    </div>
  )
}
