import { useCallback, useEffect, useRef, useState } from 'react'
import { api, type QueryValue } from './api'
import type { Filters as FilterState, Level, NextPotentialRule, Signal, WatchList } from './types'
import { Filters } from './components/Filters'
import { SignalDetail } from './components/SignalDetail'
import { NextPotentialCell } from './components/NextPotential'
import { LevelDetail } from './components/LevelDetail'
import { MarketPanel } from './components/MarketPanel'
import { WatchListView } from './components/WatchListView'
import { knownAt, price } from './components/format'
import { useTimeZone, zoneLabel, zoneOptions } from './timezone'
import { ChartFeedContext, feedFor, saveVenue, savedVenue, shortName, spellingOn, type Venue } from './venue'

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
function toQuery(f: FilterState, tab: Tab, exchange: string): Record<string, QueryValue> {
  const validStatuses = tab === 'signals' ? SIGNAL_STATUSES : LEVEL_STATUSES
  const exact = f.touches.filter((t) => !t.endsWith('+'))
  const bucket = f.touches.find((t) => t.endsWith('+'))
  return {
    exchange,
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
  const [watchList, setWatchList] = useState<WatchList | null>(null)
  const [nextPotentialRule, setNextPotentialRule] = useState<NextPotentialRule | null>(null)
  const [status, setStatus] = useState<Record<string, unknown> | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [symbols, setSymbols] = useState<string[]>([])
  const [timeframes, setTimeframes] = useState<string[]>([])
  // The market shown: undefined until the server has said which ones it runs.
  const [exchange, setExchange] = useState<string | undefined>()
  const [exchanges, setExchanges] = useState<Venue[]>([])
  const [totalItems, setTotalItems] = useState(0)
  // Set on a switch of market: the coin filter is carried over once the new market's coins are known.
  const carryCoinFilter = useRef(false)
  // Only the latest list request may fill the list: an older one, for another market or other
  // filters, can answer after it.
  const latestLoad = useRef(0)

  const persistFilters = (next: FilterState) => {
    try {
      localStorage.setItem(FILTER_KEY, JSON.stringify(next))
    } catch {
      // Not persisted; still applied for this visit.
    }
  }

  const updateFilters = (next: FilterState) => {
    setFilters(next)
    persistFilters(next)
  }

  /** Shows another market: everything on the page is that market's from here on. */
  const selectExchange = (name: string) => {
    if (name === exchange) return
    saveVenue(name)
    setSelectedSignal(null)
    setSelectedLevel(null)
    setSignals([])
    setLevels([])
    setTotalItems(0)
    setWatchList(null)
    setConfig(null)
    setStatus(null)
    setSymbols([])
    carryCoinFilter.current = true
    setExchange(name)
  }

  const load = useCallback(async () => {
    if (!exchange) return
    const request = ++latestLoad.current
    const current = () => request === latestLoad.current
    setLoading(true)
    setError(null)
    try {
      if (tab === 'signals') {
        const page = await api.signals(toQuery(filters, tab, exchange))
        if (!current()) return
        setSignals(page.items)
        setTotalItems(page.totalItems)
      } else if (tab === 'levels') {
        const page = await api.levels(toQuery(filters, tab, exchange))
        if (!current()) return
        setLevels(page.items)
        setTotalItems(page.totalItems)
      } else {
        const [c, w] = await Promise.all([api.config(exchange), api.watchList(exchange)])
        if (!current()) return
        setConfig(c)
        setWatchList(w)
      }
    } catch (e) {
      if (current()) setError((e as Error).message)
    } finally {
      if (current()) setLoading(false)
    }
  }, [tab, filters, exchange])

  useEffect(() => {
    void load()
  }, [load])

  // Which markets the server runs, and for the one shown, what it watches: the filters offer only
  // that, so they never suggest a market that has no data. Re-read now and then: the server
  // adjusts its coin lists by trading volume. Until the markets are known, retry soon.
  useEffect(() => {
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    // Every 10 minutes once this market's coins are in; every 15 seconds until then.
    const again = (loaded: boolean) => {
      if (!cancelled) timer = setTimeout(read, loaded ? 10 * 60_000 : 15_000)
    }
    const read = (): Promise<void> =>
      api.config(exchange)
        .then((c) => {
          if (cancelled) return
          const own = c.exchange as Venue | undefined
          const running = (c.exchanges as Venue[] | undefined) ?? (own ? [own] : [])
          setExchanges(running)
          if (!exchange || !running.some((v) => v.name === exchange)) {
            // First answer, or the market shown is no longer run: open the one this browser
            // looked at last, else the server's default. The effect then runs again for it.
            const saved = savedVenue()
            const pick = running.find((v) => v.name === saved)?.name ?? running[0]?.name
            setExchange(pick)
            if (!pick) again(false)
            return
          }
          const markets = c.markets as { symbols?: string[]; timeframes?: string[] } | undefined
          const watched = markets?.symbols ?? []
          if (carryCoinFilter.current) {
            // The coins picked on the other market, as this one spells them; ones it does not
            // watch are dropped rather than leave an empty list.
            carryCoinFilter.current = false
            setFilters((current) => {
              const carried = current.symbols
                .map((s) => spellingOn(s, watched))
                .filter((s, i, all): s is string => s !== null && all.indexOf(s) === i)
              if (carried.length === current.symbols.length && carried.every((s, i) => s === current.symbols[i])) {
                return current
              }
              const next = { ...current, symbols: carried }
              persistFilters(next)
              return next
            })
          }
          setSymbols(watched)
          setTimeframes(markets?.timeframes ?? [])
          setNextPotentialRule((c.nextPotential as NextPotentialRule | undefined) ?? null)
          again(true)
        })
        .catch(() => again(false))
    void read()
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [exchange])

  // Poll the pipeline status so the header reflects reality without a manual refresh.
  useEffect(() => {
    if (!exchange) return
    let cancelled = false
    const tick = () => api.status(exchange).then((s) => !cancelled && setStatus(s)).catch(() => {})
    void tick()
    const handle = setInterval(tick, 15_000)
    return () => {
      cancelled = true
      clearInterval(handle)
    }
  }, [exchange])

  const stream = status?.liveStream as { connected?: boolean } | undefined
  const bootstrap = status?.bootstrap as string | undefined
  const venueStates = (status?.exchanges as { exchange: string; streamConnected?: boolean }[] | undefined) ?? []
  const tz = zoneLabel(timeZone)

  return (
    <ChartFeedContext.Provider value={feedFor(exchange)}>
    <div className="app">
      <header className="header">
        <div className="brand">crypto<span>·</span>signals</div>
        {exchanges.length > 1 ? (
          <div className="venue-switch" role="radiogroup" aria-label="Market">
            {exchanges.map((v) => {
              const live = venueStates.find((s) => s.exchange === v.name)?.streamConnected
              return (
                <button key={v.name} type="button" role="radio" aria-checked={v.name === exchange}
                        className="venue" title={`${v.label}${live === false ? ' — live stream down' : ''}`}
                        onClick={() => selectExchange(v.name)}>
                  <span className={`venue-dot ${live ? 'live' : live === false ? 'down' : ''}`} aria-hidden />
                  {shortName(v)}
                </button>
              )
            })}
          </div>
        ) : exchanges.length === 1 ? (
          <span className="venue-only faint" title={exchanges[0].label}>{shortName(exchanges[0])}</span>
        ) : null}
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
              {zoneOptions().map((o) => <option key={o.zone} value={o.zone}>{o.label}</option>)}
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
                    <th title="When the signal fired: the close of the candle that completed it">Detected ({tz})</th>
                    <th>Symbol</th>
                    <th>TF</th>
                    <th>Type</th>
                    <th>Level</th>
                    <th title="Next potential low (support) or high (resistance)">Next low/high</th>
                    <th>Touches</th>
                    <th>Strength</th>
                    <th>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {signals.map((s) => (
                    <tr key={s.id} aria-selected={selectedSignal?.id === s.id} onClick={() => setSelectedSignal(s)}>
                      <td className="num">{knownAt(s.detectedAt, s.timeframe, timeZone)}</td>
                      <td>{s.symbol}</td>
                      <td className="num faint">{s.timeframe}</td>
                      <td className={`type-${s.type}`}>{s.type === 'RESISTANCE' ? 'RES' : 'SUP'}</td>
                      <td className="num">{price(s.price)}</td>
                      <td className="num"><NextPotentialCell signal={s} /></td>
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
            <SignalDetail signal={selectedSignal} nextPotentialRule={nextPotentialRule} />
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
                    <th title="When the level was first found: the close of the candle that formed it">First seen ({tz})</th>
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
                      <td className="num">{knownAt(l.firstSeenAt, l.timeframe, timeZone)}</td>
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
          <WatchListView list={watchList} timeZone={timeZone} />
          <div className="section-title" style={{ padding: '0 0 8px' }}>
            Effective detection rules — set by the administrator on the server
          </div>
          <pre className="config">{JSON.stringify(config, null, 2)}</pre>
        </div>
      )}
    </div>
    </ChartFeedContext.Provider>
  )
}
