import { useEffect, useState } from 'react'
import { api } from '../api'
import type { Candle, Level } from '../types'
import { LevelChart } from './LevelChart'
import { TouchTable } from './TouchTable'
import { price, timestamp } from './format'

export function LevelDetail({ level }: { level: Level }) {
  const [detail, setDetail] = useState<Level | null>(null)
  const [candles, setCandles] = useState<Candle[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setError(null)
    setDetail(null)
    setCandles([])

    Promise.all([api.level(level.id), api.candles(level.symbol, level.timeframe, 500)])
      .then(([l, c]) => {
        if (cancelled) return
        setDetail(l)
        setCandles(c)
      })
      .catch((e: Error) => !cancelled && setError(e.message))

    return () => {
      cancelled = true
    }
  }, [level.id, level.symbol, level.timeframe])

  return (
    <div className="detail-pane">
      <div className="detail-head">
        <div className="detail-title">
          <span>{level.symbol}</span>
          <span className="faint">{level.timeframe}</span>
          <span className={`type-${level.type}`}>{level.type}</span>
          <span className={`badge ${level.status}`}>{level.status.toLowerCase()}</span>
        </div>
        <div className="detail-sub">
          {price(level.price)} &nbsp;·&nbsp; zone {price(level.zoneLower)} – {price(level.zoneUpper)}
        </div>
        <div className="stat-row">
          <div>
            <div className="stat-label">Touches</div>
            <div className="stat-value">{level.touchCount}</div>
          </div>
          <div>
            <div className="stat-label">Swings</div>
            <div className="stat-value">{level.swingCount}</div>
          </div>
          <div>
            <div className="stat-label">Strength</div>
            <div className="stat-value">{level.strength.toFixed(2)}</div>
          </div>
          <div>
            <div className="stat-label">First seen</div>
            <div className="stat-value">{timestamp(level.firstSeenAt)}</div>
          </div>
          <div>
            <div className="stat-label">Last touch</div>
            <div className="stat-value">{timestamp(level.lastTouchAt)}</div>
          </div>
        </div>
      </div>

      {error && <div className="error">{error}</div>}

      {candles.length > 0 && (
        <LevelChart
          candles={candles}
          levelPrice={Number(level.price)}
          zoneLower={Number(level.zoneLower)}
          zoneUpper={Number(level.zoneUpper)}
          touches={detail?.touches ?? []}
          type={level.type}
        />
      )}

      <div className="section-title">Touches</div>
      <TouchTable touches={detail?.touches ?? []} />
    </div>
  )
}
