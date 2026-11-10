import type { Level } from '../types'
import { useTimeZone } from '../timezone'
import { knownAt, price } from './format'

/** The "what happened to this level since" facts shared by both detail panels. */
export function LevelFate({ level }: { level: Level | null }) {
  const { timeZone } = useTimeZone()
  if (!level) return null
  return (
    <>
      <div>
        <div className="stat-label">Breakout</div>
        <div className="stat-value">{level.breakout ? knownAt(level.breakout.confirmedAt, level.timeframe, timeZone) : 'none'}</div>
      </div>
      {level.status === 'SUPERSEDED' && (
        <div>
          <div className="stat-label">Superseded</div>
          <div className="stat-value">
            {knownAt(level.supersededAt, level.timeframe, timeZone)}
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
  const role = above ? 'resistance' : 'support'
  const by = level.supersededByPrice ? price(level.supersededByPrice) : 'a nearby price'
  const retired = 'This level is retired: it no longer counts touches or raises signals.'
  const broken = level.breakout ? ' It was later broken as well.' : ''

  if (level.supersededReason === 'NEARBY_LEVEL') {
    const gap = gapPercent(level.price, level.supersededByPrice)
    return (
      <div className="note-superseded">
        Another {role} with 3+ touches sits close by at <b>{by}</b>
        {gap !== null && <>, {gap} {above ? 'higher' : 'lower'}</>}. Of a group of close-together{' '}
        {role} levels only the {above ? 'highest' : 'lowest'} stays active, so this one is hidden. It
        was not broken. {retired}{broken}
      </div>
    )
  }
  return (
    <div className="note-superseded">
      Price moved {above ? 'above' : 'below'} this level and the {role} at <b>{by}</b>, just{' '}
      {above ? 'above' : 'below'} it, became the nearer one. {retired}{broken}
    </div>
  )
}

function gapPercent(levelPrice: string, byPrice?: string | null): string | null {
  const a = Number(levelPrice)
  const b = Number(byPrice)
  if (!byPrice || !isFinite(a) || !isFinite(b) || a <= 0 || b <= 0) return null
  return `${((Math.abs(b - a) / Math.min(a, b)) * 100).toFixed(2)}%`
}
