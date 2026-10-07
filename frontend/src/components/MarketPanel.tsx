import { useEffect, useState } from 'react'
import { LiveChart } from './LiveChart'

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

  return (
    <div className="detail-pane">
      <div className="detail-head market-head">
        <div className="field">
          <label htmlFor="mp-symbol">Market</label>
          <select id="mp-symbol" value={symbol} onChange={(e) => setSymbol(e.target.value)}>
            {(symbols.length ? symbols : [symbol]).map((s) => <option key={s} value={s}>{s}</option>)}
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
      <LiveChart symbol={symbol} timeframe={timeframe} />
    </div>
  )
}
