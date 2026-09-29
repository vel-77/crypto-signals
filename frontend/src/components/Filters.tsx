import type { Filters as FilterState } from '../types'

interface Props {
  value: FilterState
  onChange: (next: FilterState) => void
  onRefresh: () => void
  statusOptions: string[]
  /** Timeframes the app is watching, from the server's configuration. */
  timeframes: string[]
}

export function Filters({ value, onChange, onRefresh, statusOptions, timeframes }: Props) {
  const set = (key: keyof FilterState) => (event: { target: { value: string } }) =>
    onChange({ ...value, [key]: event.target.value })

  return (
    <div className="filters">
      <div className="field">
        <label htmlFor="f-symbol">Symbol</label>
        <input
          id="f-symbol"
          value={value.symbol}
          placeholder="all"
          onChange={set('symbol')}
          onKeyDown={(e) => e.key === 'Enter' && onRefresh()}
        />
      </div>
      <div className="field">
        <label htmlFor="f-tf">Timeframe</label>
        <select id="f-tf" value={value.timeframe} onChange={set('timeframe')}>
          <option value="">all</option>
          {timeframes.map((tf) => (
            <option key={tf} value={tf}>{tf}</option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor="f-type">Type</label>
        <select id="f-type" value={value.type} onChange={set('type')}>
          <option value="">all</option>
          <option value="RESISTANCE">resistance</option>
          <option value="SUPPORT">support</option>
        </select>
      </div>
      <div className="field">
        <label htmlFor="f-status">Status</label>
        <select id="f-status" value={value.status} onChange={set('status')}>
          <option value="">all</option>
          {statusOptions.map((s) => (
            <option key={s} value={s}>{s.toLowerCase()}</option>
          ))}
        </select>
      </div>
      <button className="btn" onClick={onRefresh}>Refresh</button>
    </div>
  )
}
