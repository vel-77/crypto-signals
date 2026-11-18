import { useEffect, useState } from 'react'
import { LiveChart } from './LiveChart'
import { spellingOn } from '../venue'

interface Props {
  symbols: string[]
  timeframes: string[]
  preferredSymbol?: string
  preferredTimeframe?: string
}

/**
 * The right-hand panel when nothing is selected: a live chart of any watched market, so the panel
 * is always useful for following price, not only once a signal has been picked.
 */
export function MarketPanel({ symbols, timeframes, preferredSymbol, preferredTimeframe }: Props) {
  const [symbol, setSymbol] = useState(preferredSymbol ?? symbols[0] ?? 'BTCUSDT')
  const [timeframe, setTimeframe] = useState(preferredTimeframe ?? timeframes[0] ?? '15m')

  useEffect(() => {
    if (preferredSymbol) setSymbol(preferredSymbol)
  }, [preferredSymbol])
  useEffect(() => {
    if (preferredTimeframe) setTimeframe(preferredTimeframe)
  }, [preferredTimeframe])
  // After switching between futures and spot the coin stays, as that market spells it
  // (1000SHIBUSDT / SHIBUSDT); one that market does not watch gives way to one it does.
  useEffect(() => {
    if (symbols.length > 0 && !symbols.includes(symbol)) {
      setSymbol(spellingOn(symbol, symbols)
          ?? (preferredSymbol && symbols.includes(preferredSymbol) ? preferredSymbol : symbols[0]))
    }
  }, [symbols, symbol, preferredSymbol])
  // Nothing is drawn until the market's coins are known, so the chart never asks a market for a
  // coin it does not list.
  const shown = symbols.includes(symbol) ? symbol : null

  return (
    <div className="detail-pane">
      <div className="detail-head market-head">
        <div className="field">
          <label htmlFor="mp-symbol">Coin</label>
          <select id="mp-symbol" value={symbol} onChange={(e) => setSymbol(e.target.value)}>
            {(shown ? symbols : [symbol]).map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
        </div>
        <div className="field">
          <label htmlFor="mp-tf">Timeframe</label>
          <select id="mp-tf" value={timeframe} onChange={(e) => setTimeframe(e.target.value)}>
            {(timeframes.length ? timeframes : [timeframe]).map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        </div>
        <div className="faint market-hint">Select a row on the left to draw its level on the chart.</div>
      </div>
      {shown
        ? <LiveChart symbol={shown} timeframe={timeframe} />
        : <div className="faint chart-wait">Loading the market's coins…</div>}
    </div>
  )
}
