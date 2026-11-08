import type { NextPotentialRule, Signal } from '../types'
import { useTimeZone } from '../timezone'
import { price, timestamp } from './format'

/** "Next potential low" for a support signal, "next potential high" for a resistance signal. */
export function nextPotentialName(signal: Signal): string {
  return signal.type === 'SUPPORT' ? 'Next potential low' : 'Next potential high'
}

/** Signed percentage with a real minus sign: "−1.50%", "+1.50%". */
export function signedPercent(value: number): string {
  return `${value < 0 ? '−' : '+'}${Math.abs(value).toFixed(2)}%`
}

/** "PT720H" → "30 days"; "unlimited" → "all stored history". */
function lookbackText(lookback: string | undefined): string {
  if (!lookback) return 'the history before the signal'
  if (lookback === 'unlimited') return 'all stored history before the signal'
  const m = /^PT(?:(\d+)H)?(?:(\d+)M)?/.exec(lookback)
  const hours = m ? Number(m[1] ?? 0) + Number(m[2] ?? 0) / 60 : NaN
  if (!Number.isFinite(hours) || hours <= 0) return 'the history before the signal'
  if (hours % 24 === 0) return `the ${hours / 24} day${hours === 24 ? '' : 's'} before the signal`
  return `the ${hours} hours before the signal`
}

/** Compact form for the signals table. */
export function NextPotentialCell({ signal }: { signal: Signal }) {
  const next = signal.nextPotential
  if (!next || !next.evaluated) return <span className="faint" title="Not worked out yet">…</span>
  if (next.price == null || next.percent == null) {
    return <span className="faint" title={`${nextPotentialName(signal)}: none qualifies`}>N/A</span>
  }
  return (
    <span className="next-potential" title={`${nextPotentialName(signal)}: ${price(next.price)}`}>
      {signedPercent(next.percent)}
    </span>
  )
}

/** The information window next to a signal: where price turned last time it was beyond this level. */
export function NextPotentialCard({ signal, rule }: { signal: Signal; rule: NextPotentialRule | null }) {
  const { timeZone } = useTimeZone()
  const next = signal.nextPotential
  const low = signal.type === 'SUPPORT'
  const swing = low ? 'swing low' : 'swing high'
  const min = rule ? `${rule.minDistancePercent}%` : 'the minimum distance'

  let body
  if (!next || !next.evaluated) {
    body = <div className="np-value faint">not worked out yet</div>
  } else if (next.price == null || next.percent == null) {
    body = <div className="np-value faint">N/A</div>
  } else {
    body = (
      <div className="np-value">
        <span className="next-potential">{signedPercent(next.percent)}</span>
        <span className="np-price">{price(next.price)}</span>
        <span className="np-when">{swing} of {timestamp(next.swingAt, timeZone)}</span>
      </div>
    )
  }

  return (
    <div className="np-card">
      <div className="stat-label">{nextPotentialName(signal)}</div>
      {body}
      <div className="np-rule">
        The nearest earlier {swing} at least {min} {low ? 'below' : 'above'} the signal price ({price(signal.price)}),
        from {lookbackText(rule?.lookback)}. The closest one in price, not the {low ? 'lowest' : 'highest'} or the
        most recent. N/A when none is that far {low ? 'below' : 'above'}.
      </div>
    </div>
  )
}
