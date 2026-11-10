import type { WatchList } from '../types'
import { timestamp } from './format'

/** 54_540_000 -> "$54.5M"; USDT is close enough to dollars for a sense of size. */
function money(value?: number): string {
  if (value == null) return '—'
  if (value >= 1e9) return `$${(value / 1e9).toFixed(2)}B`
  if (value >= 1e6) return `$${(value / 1e6).toFixed(1)}M`
  if (value >= 1e3) return `$${(value / 1e3).toFixed(0)}K`
  return `$${value.toFixed(0)}`
}

/** An ISO-8601 duration such as "PT168H" in words: "7 days". */
function span(iso: string): string {
  const m = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:[\d.]+S)?$/.exec(iso)
  if (!m) return iso
  const hours = Number(m[1] ?? 0)
  const minutes = Number(m[2] ?? 0)
  if (hours > 0 && hours % 24 === 0 && minutes === 0) {
    const days = hours / 24
    return days === 1 ? 'day' : `${days} days`
  }
  if (minutes === 0) return hours === 1 ? 'hour' : `${hours} hours`
  return `${hours * 60 + minutes} minutes`
}

interface Props {
  list: WatchList | null
  timeZone: string
}

/** Which coins are analysed and why: shown on the config tab. */
export function WatchListView({ list, timeZone }: Props) {
  if (!list) return null
  const rule = list.rule
  return (
    <section className="watchlist">
      <div className="section-title" style={{ padding: '0 0 8px' }}>
        Watch list — {list.count} coin{list.count === 1 ? '' : 's'}, each on every timeframe
      </div>
      {rule ? (
        <p className="watchlist-rule">
          The fixed coins are always watched. On top of them come the most traded other coins: those
          averaging at least <b>{money(rule.minDailyVolume)}</b> a day over the last <b>{span(rule.window)}</b>,
          highest first, up to <b>{rule.maxSymbols === 0 ? 'any number of' : rule.maxSymbols}</b> coins in all.
          Stablecoins, tokenized stocks and gold are skipped. A coin on the list stays until its volume
          falls below {rule.keepPercent}% of that threshold. Checked every {span(rule.refreshInterval)}
          {list.refreshedAt ? `; last checked ${timestamp(list.refreshedAt, timeZone)}` : ''}.
        </p>
      ) : (
        <p className="watchlist-rule">Only the coins set in the server configuration are watched.</p>
      )}
      {list.lastError && (
        <p className="watchlist-error">
          The last check failed ({list.lastError}); the list below is kept
          {list.nextRefreshAt ? ` and checked again at ${timestamp(list.nextRefreshAt, timeZone)}` : ''}.
        </p>
      )}
      <table>
        <thead>
          <tr>
            <th>Coin</th>
            <th>On the list</th>
            <th title="Average traded value per day over the volume window">Daily volume</th>
            <th>Chosen since</th>
          </tr>
        </thead>
        <tbody>
          {list.symbols.map((s) => (
            <tr key={s.symbol}>
              <td>{s.symbol}</td>
              <td className={`src src-${s.source.toLowerCase()}`}>{s.source === 'FIXED' ? 'fixed' : 'by volume'}</td>
              <td className="num">{money(s.dailyVolume)}</td>
              <td className="num faint">{s.selectedAt ? timestamp(s.selectedAt, timeZone) : ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
