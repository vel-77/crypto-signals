import { useEffect, useState } from 'react'
import { api } from '../api'
import type { Level, NextPotentialRule, Signal } from '../types'
import { useTimeZone } from '../timezone'
import { LiveChart } from './LiveChart'
import { LevelFate, SupersededNote } from './LevelFacts'
import { NextPotentialCard, signedPercent } from './NextPotential'
import { TouchTable } from './TouchTable'
import { knownAt, price } from './format'

/** Everything behind one signal: the live chart with the level drawn on it, its fate, and its touches. */
export function SignalDetail({ signal, nextPotentialRule }: {
  signal: Signal
  nextPotentialRule: NextPotentialRule | null
}) {
  const { timeZone } = useTimeZone()
  const [detail, setDetail] = useState<Signal | null>(null)
  const [level, setLevel] = useState<Level | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setError(null)
    setDetail(null)
    setLevel(null)
    Promise.all([api.signal(signal.id), api.level(signal.levelId)])
      .then(([s, l]) => {
        if (cancelled) return
        setDetail(s)
        setLevel(l)
      })
      .catch((e: Error) => !cancelled && setError(e.message))
    return () => {
      cancelled = true
    }
  }, [signal.id, signal.levelId])

  const touches = detail?.touches ?? []
  const next = signal.nextPotential

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
            <div className="stat-value">{knownAt(signal.detectedAt, signal.timeframe, timeZone)}</div>
          </div>
          <LevelFate level={level} />
          <div>
            <div className="stat-label">Origin</div>
            <div className="stat-value">{signal.historical ? 'replay' : 'live'}</div>
          </div>
        </div>
        <NextPotentialCard signal={signal} rule={nextPotentialRule} />
      </div>

      {error && <div className="error">{error}</div>}
      <SupersededNote level={level} />

      {detail && (
        <LiveChart
          symbol={signal.symbol}
          timeframe={signal.timeframe}
          level={{
            price: Number(signal.price), zoneLower: Number(signal.zoneLower), zoneUpper: Number(signal.zoneUpper),
            type: signal.type, touches,
            target: next?.price != null && next.percent != null ? {
              price: Number(next.price),
              time: next.swingAt ? Date.parse(next.swingAt) : null,
              label: `next ${signal.type === 'SUPPORT' ? 'low' : 'high'} ${signedPercent(next.percent)}`,
            } : null,
          }}
        />
      )}

      <div className="section-title">Touches</div>
      <TouchTable touches={touches} />

      {level?.breakout && (
        <>
          <div className="section-title">Breakout{level.status === 'SUPERSEDED' ? ' (after it was superseded)' : ''}</div>
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
                <td className="num">{knownAt(level.breakout.confirmedAt, level.timeframe, timeZone)}</td>
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
