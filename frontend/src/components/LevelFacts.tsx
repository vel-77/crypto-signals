import type { Level } from '../types'
import { useTimeZone } from '../timezone'
import { price, timestamp } from './format'

/** The "what happened to this level since" facts shared by both detail panels. */
export function LevelFate({ level }: { level: Level | null }) {
  const { timeZone } = useTimeZone()
  if (!level) return null
  return (
    <>
      <div>
        <div className="stat-label">Breakout</div>
        <div className="stat-value">{level.breakout ? timestamp(level.breakout.confirmedAt, timeZone) : 'none'}</div>
      </div>
      {level.status === 'SUPERSEDED' && (
        <div>
          <div className="stat-label">Superseded</div>
          <div className="stat-value">
            {timestamp(level.supersededAt, timeZone)}
            {level.supersededByPrice && <span className="dim"> · by {price(level.supersededByPrice)}</span>}
          </div>
        </div>
      )}
    </>
  )
}

/** Plain-language explanation of a superseded level. */
export function SupersededNote({ level }: { level: Level | null }) {
  if (!level || level.status !== 'SUPERSEDED') return null
  const above = level.type === 'RESISTANCE'
  return (
    <div className="note-superseded">
      Price moved {above ? 'above' : 'below'} this level and the {above ? 'resistance' : 'support'} at{' '}
      <b>{level.supersededByPrice ? price(level.supersededByPrice) : 'a nearby price'}</b>, just{' '}
      {above ? 'above' : 'below'} it, became the nearer one. This level is retired: it no longer counts
      touches or raises signals.{level.breakout ? ' It was later broken as well.' : ''}
    </div>
  )
}
