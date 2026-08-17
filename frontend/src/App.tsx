import { useEffect, useState } from 'react'
import { fetchStocks, type ScreenerQuery, type Stock } from './api'
import './App.css'

const initialQuery: ScreenerQuery = {
  q: '',
  maxPe: '',
  minMarketCap: '',
  sortBy: 'marketCap',
  order: 'desc',
}

function formatMoney(value: number | null): string {
  if (value == null) return '—'
  return `$${value.toFixed(2)}`
}

function formatMarketCap(value: number | null): string {
  if (value == null) return '—'
  if (value >= 1e12) return `$${(value / 1e12).toFixed(2)} T`
  if (value >= 1e9) return `$${(value / 1e9).toFixed(2)} B`
  return `$${(value / 1e6).toFixed(0)} M`
}

export default function App() {
  const [query, setQuery] = useState(initialQuery)
  const [stocks, setStocks] = useState<Stock[]>([])
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    // Debounce so typing does not fire a request per keystroke.
    const timer = setTimeout(() => {
      setLoading(true)
      fetchStocks(query)
        .then((result) => {
          setStocks(result)
          setError(null)
        })
        .catch((e: Error) => setError(e.message))
        .finally(() => setLoading(false))
    }, 300)
    return () => clearTimeout(timer)
  }, [query])

  const update = (patch: Partial<ScreenerQuery>) => setQuery((q) => ({ ...q, ...patch }))

  return (
    <main className="screener">
      <h1>StockLens</h1>
      <p className="subtitle">A simple screener for U.S. stocks</p>

      <form className="controls" onSubmit={(e) => e.preventDefault()}>
        <label>
          Search company or ticker
          <input
            type="text"
            placeholder="e.g. Microsoft or MSFT"
            value={query.q}
            onChange={(e) => update({ q: e.target.value })}
          />
        </label>
        <label>
          P/E maximum
          <input
            type="number"
            min="0.01"
            step="any"
            placeholder="e.g. 30"
            value={query.maxPe}
            onChange={(e) => update({ maxPe: e.target.value })}
          />
        </label>
        <label>
          Market cap minimum ($B)
          <input
            type="number"
            min="0"
            step="any"
            placeholder="e.g. 10"
            value={query.minMarketCap}
            onChange={(e) => update({ minMarketCap: e.target.value })}
          />
        </label>
        <label>
          Sort by
          <select
            value={query.sortBy}
            onChange={(e) => update({ sortBy: e.target.value as ScreenerQuery['sortBy'] })}
          >
            <option value="marketCap">Market cap</option>
            <option value="pe">P/E</option>
            <option value="price">Price</option>
            <option value="name">Name</option>
          </select>
        </label>
        <label>
          Order
          <select
            value={query.order}
            onChange={(e) => update({ order: e.target.value as ScreenerQuery['order'] })}
          >
            <option value="desc">Descending</option>
            <option value="asc">Ascending</option>
          </select>
        </label>
      </form>

      {error && <p className="error">{error}</p>}
      {loading && <p className="status">Loading…</p>}
      {!error && !loading && stocks.length === 0 && (
        <p className="status">No companies match the current criteria.</p>
      )}

      {stocks.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>Company</th>
              <th>Ticker</th>
              <th>Sector</th>
              <th className="num">Price</th>
              <th className="num">P/E</th>
              <th className="num">Market cap</th>
            </tr>
          </thead>
          <tbody>
            {stocks.map((stock) => (
              <tr key={stock.ticker}>
                <td>{stock.name}</td>
                <td>{stock.ticker}</td>
                <td>{stock.sector ?? '—'}</td>
                <td className="num">{formatMoney(stock.price)}</td>
                <td className="num">{stock.peRatio?.toFixed(1) ?? '—'}</td>
                <td className="num">{formatMarketCap(stock.marketCap)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </main>
  )
}
