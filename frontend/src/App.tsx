import { useCallback, useEffect, useState } from 'react'
import { api, type QueryValue } from './api'
import type { Filters as FilterState, Level, Signal } from './types'
import { Filters } from './components/Filters'
import { SignalDetail } from './components/SignalDetail'
import { LevelDetail } from './components/LevelDetail'
import { MarketPanel } from './components/MarketPanel'
import { price, timestamp } from './components/format'
import { allTimeZones, useTimeZone, zoneLabel } from './timezone'

type Tab = 'signals' | 'levels' | 'config'

const EMPTY_FILTERS: FilterState = { symbols: [], timeframes: [], types: [], statuses: [], touches: [] }
const FILTER_KEY = 'crypto-signals.filters'

const SIGNAL_STATUSES = ['ACTIVE', 'SUPERSEDED', 'INVALIDATED', 'EXPIRED']
const LEVEL_STATUSES = ['ACTIVE', 'SUPERSEDED', 'BROKEN', 'EXPIRED']
const SIGNAL_TOUCHES = ['3', '4', '5', '6', '7', '8+']
const LEVEL_TOUCHES = ['1', '2', '3', '4', '5', '6', '7', '8+']

function loadSavedFilters(): FilterState {
  try {
    const raw = localStorage.getItem(FILTER_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<FilterState>
      return {
        symbols: parsed.symbols ?? [], timeframes: parsed.timeframes ?? [], types: parsed.types ?? [],
        statuses: parsed.statuses ?? [], touches: parsed.touches ?? [],
      }
    }
  } catch {
    // Nothing usable saved; start unfiltered.
  }
  return EMPTY_FILTERS
}

/**
 * Turns the filter state into API parameters for one tab. Signals and levels have different
 * status vocabularies, so only the statuses that exist on this tab are sent; an "8+" touch bucket
 * becomes an open-ended lower bound alongside the exact counts.
 */
function toQuery(f: FilterState, tab: Tab): Record<string, QueryValue> {
  const validStatuses = tab === 'signals' ? SIGNAL_STATUSES : LEVEL_STATUSES
  const exact = f.touches.filter((t) => !t.endsWith('+'))
  const bucket = f.touches.find((t) => t.endsWith('+'))
  return {
    symbol: f.symbols,
    timeframe: f.timeframes,
    type: f.types,
    status: f.statuses.filter((s) => validStatuses.includes(s)),
    touches: exact,
    touchesAtLeast: bucket ? Number.parseInt(bucket, 10) : undefined,
    size: 300,
  }
}

export default function App() {
  const { timeZone, setTimeZone } = useTimeZone()
  const [tab, setTab] = useState<Tab>('signals')
  const [filters, setFilters] = useState<FilterState>(loadSavedFilters)
  const [signals, setSignals] = useState<Signal[]>([])
  const [levels, setLevels] = useState<Level[]>([])
  const [selectedSignal, setSelectedSignal] = useState<Signal | null>(null)
  const [selectedLevel, setSelectedLevel] = useState<Level | null>(null)
  const [config, setConfig] = useState<Record<string, unknown> | null>(null)
  const [status, setStatus] = useState<Record<string, unknown> | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [symbols, setSymbols] = useState<string[]>([])
  const [timeframes, setTimeframes] = useState<string[]>([])
  const [totalItems, setTotalItems] = useState(0)

  const updateFilters = (next: FilterState) => {
    setFilters(next)
    try {
      localStorage.setItem(FILTER_KEY, JSON.stringify(next))
    } catch {
      // Not persisted; still applied for this visit.
    }
  }

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      if (tab === 'signals') {
        const page = await api.signals(toQuery(filters, tab))
        setSignals(page.items)
        setTotalItems(page.totalItems)
      } else if (tab === 'levels') {
        const page = await api.levels(toQuery(filters, tab))
        setLevels(page.items)
        setTotalItems(page.totalItems)
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

  // The filters offer only what the server actually watches, so they never suggest a market
  // that has no data.
  useEffect(() => {
    api.config()
      .then((c) => {
        const markets = c.markets as { symbols?: string[]; timeframes?: string[] } | undefined
        setSymbols(markets?.symbols ?? [])
        setTimeframes(markets?.timeframes ?? [])
      })
      .catch(() => {})
  }, [])

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

  const stream = status?.liveStream as { connected?: boolean } | undefined
  const bootstrap = status?.bootstrap as string | undefined
  const tz = zoneLabel(timeZone)

  return (
    <div className="app">
      <header className="header">
        <div className="brand">crypto<span>·</span>signals</div>
        <nav className="tabs" role="tablist">
          {(['signals', 'levels', 'config'] as Tab[]).map((t) => (
            <button key={t} className="tab" role="tab" aria-selected={tab === t} onClick={() => setTab(t)}>
              {t}
            </button>
          ))}
        </nav>
        <div className="header-right">
          <label className="tz-picker" title="Times are shown in this zone. Saved in this browser only.">
            <span className="faint">Time zone</span>
            <select value={timeZone} onChange={(e) => setTimeZone(e.target.value)}>
              {allTimeZones().map((z) => <option key={z} value={z}>{z}</option>)}
            </select>
          </label>
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
          onChange={updateFilters}
          onRefresh={() => void load()}
          symbols={symbols}
          timeframes={timeframes}
          statusOptions={tab === 'signals' ? SIGNAL_STATUSES : LEVEL_STATUSES}
          touchOptions={tab === 'signals' ? SIGNAL_TOUCHES : LEVEL_TOUCHES}
        />
      )}

      {error && <div className="error">{error}</div>}

      {tab === 'signals' && (
        <div className="body">
          <div className="list-pane">
            <div className="list-count faint">{totalItems} signal{totalItems === 1 ? '' : 's'}</div>
            {signals.length === 0 ? (
              <div className="empty">No signals match these filters.</div>
            ) : (
              <table>
                <thead>
                  <tr>
                    <th>Detected ({tz})</th>
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
                    <tr key={s.id} aria-selected={selectedSignal?.id === s.id} onClick={() => setSelectedSignal(s)}>
                      <td className="num">{timestamp(s.detectedAt, timeZone)}</td>
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
            <MarketPanel symbols={symbols} timeframes={timeframes}
                         preferredSymbol={filters.symbols[0]} preferredTimeframe={filters.timeframes[0]} />
          )}
        </div>
      )}

      {tab === 'levels' && (
        <div className="body">
          <div className="list-pane">
            <div className="list-count faint">{totalItems} level{totalItems === 1 ? '' : 's'}</div>
            {levels.length === 0 ? (
              <div className="empty">No levels match these filters.</div>
            ) : (
              <table>
                <thead>
                  <tr>
                    <th>First seen ({tz})</th>
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
                    <tr key={l.id} aria-selected={selectedLevel?.id === l.id} onClick={() => setSelectedLevel(l)}>
                      <td className="num">{timestamp(l.firstSeenAt, timeZone)}</td>
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
            <MarketPanel symbols={symbols} timeframes={timeframes}
                         preferredSymbol={filters.symbols[0]} preferredTimeframe={filters.timeframes[0]} />
          )}
        </div>
      )}

      {tab === 'config' && (
        <div className="full-pane">
          <div className="section-title" style={{ padding: '0 0 8px' }}>
            Effective detection rules — set by the administrator on the server
          </div>
          <pre className="config">{JSON.stringify(config, null, 2)}</pre>
        </div>
      )}
    </div>
  )
}
