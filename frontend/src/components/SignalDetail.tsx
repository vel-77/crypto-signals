import { useEffect, useState } from 'react'
import { api } from '../api'
import type { Candle, Level, Signal } from '../types'
import { LevelChart } from './LevelChart'
import { TouchTable } from './TouchTable'
import { price, timestamp } from './format'

/**
 * Everything behind one signal: the chart with the level drawn on it, the level's own state
 * (including a breakout if it has since been invalidated), and the touch-by-touch evidence.
 */
export function SignalDetail({ signal }: { signal: Signal }) {
  const [detail, setDetail] = useState<Signal | null>(null)
  const [level, setLevel] = useState<Level | null>(null)
  const [candles, setCandles] = useState<Candle[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setError(null)
    setDetail(null)
    setCandles([])

    Promise.all([
      api.signal(signal.id),
      api.level(signal.levelId),
      api.candles(signal.symbol, signal.timeframe, 500),
    ])
      .then(([s, l, c]) => {
        if (cancelled) return
        setDetail(s)
        setLevel(l)
        setCandles(c)
      })
      .catch((e: Error) => !cancelled && setError(e.message))

    return () => {
      cancelled = true
    }
  }, [signal.id, signal.levelId, signal.symbol, signal.timeframe])

  const touches = detail?.touches ?? []

  return (
    <div className="detail-pane">
      <div className="detail-head">
        <div className="detail-title">
          <span>{signal.symbol}</span>
          <span className="faint">{signal.timeframe}</span>
          <span className={`type-${signal.type}`}>{signal.type}</span>
          <span className={`badge ${signal.status}`}>{signal.status.toLowerCase()}</span>
        </div>
        <div className="detail-sub">
          {price(signal.price)} &nbsp;·&nbsp; zone {price(signal.zoneLower)} – {price(signal.zoneUpper)}
        </div>
        <div className="stat-row">
          <div>
            <div className="stat-label">Touches</div>
            <div className="stat-value">{signal.touchCount}</div>
          </div>
          <div>
            <div className="stat-label">Strength</div>
            <div className="stat-value">{signal.strength.toFixed(2)}</div>
          </div>
          <div>
            <div className="stat-label">Detected</div>
            <div className="stat-value">{timestamp(signal.detectedAt)}</div>
          </div>
          <div>
            <div className="stat-label">Breakout</div>
            <div className="stat-value">
              {level?.breakout ? timestamp(level.breakout.confirmedAt) : 'none'}
            </div>
          </div>
          <div>
            <div className="stat-label">Origin</div>
            <div className="stat-value">{signal.historical ? 'replay' : 'live'}</div>
          </div>
        </div>
      </div>

      {error && <div className="error">{error}</div>}

      {candles.length > 0 && (
        <LevelChart
          candles={candles}
          levelPrice={Number(signal.price)}
          zoneLower={Number(signal.zoneLower)}
          zoneUpper={Number(signal.zoneUpper)}
          touches={touches}
          type={signal.type}
        />
      )}

      <div className="section-title">Touches</div>
      <TouchTable touches={touches} />

      {level?.breakout && (
        <>
          <div className="section-title">Breakout that invalidated this level</div>
          <table>
            <thead>
              <tr>
                <th>Confirmed</th>
                <th>Threshold</th>
                <th>Close</th>
                <th>Candles</th>
                <th>Touches at break</th>
              </tr>
            </thead>
            <tbody>
              <tr style={{ cursor: 'default' }}>
                <td className="num">{timestamp(level.breakout.confirmedAt)}</td>
                <td className="num">{price(level.breakout.thresholdPrice)}</td>
                <td className="num">{price(level.breakout.confirmationPrice)}</td>
                <td className="num dim">{level.breakout.confirmationCandles}</td>
                <td className="num dim">{level.breakout.touchCountAtBreak}</td>
              </tr>
            </tbody>
          </table>
        </>
      )}
    </div>
  )
}
