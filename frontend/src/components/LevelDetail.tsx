import { useEffect, useState } from 'react'
import { api } from '../api'
import type { Level } from '../types'
import { useTimeZone } from '../timezone'
import { LiveChart } from './LiveChart'
import { LevelFate, SupersededNote } from './LevelFacts'
import { TouchTable } from './TouchTable'
import { knownAt, price } from './format'

export function LevelDetail({ level }: { level: Level }) {
  const { timeZone } = useTimeZone()
  const [detail, setDetail] = useState<Level | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setError(null)
    setDetail(null)
    api.level(level.id)
      .then((l) => !cancelled && setDetail(l))
      .catch((e: Error) => !cancelled && setError(e.message))
    return () => {
      cancelled = true
    }
  }, [level.id])

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
            <div className="stat-value">{knownAt(level.firstSeenAt, level.timeframe, timeZone)}</div>
          </div>
          <LevelFate level={detail} />
        </div>
      </div>

      {error && <div className="error">{error}</div>}
      <SupersededNote level={detail} />

      {detail && (
        <LiveChart
          symbol={level.symbol}
          timeframe={level.timeframe}
          level={{
            price: Number(level.price), zoneLower: Number(level.zoneLower), zoneUpper: Number(level.zoneUpper),
            type: level.type, touches: detail.touches ?? [],
          }}
        />
      )}

      <div className="section-title">Touches</div>
      <TouchTable touches={detail?.touches ?? []} />
    </div>
  )
}
