export interface Stock {
  ticker: string
  name: string
  sector: string | null
  price: number | null
  peRatio: number | null
  marketCap: number | null
  dividendYield: number | null
  lastUpdated: string
}

export interface ScreenerQuery {
  q: string
  maxPe: string
  minMarketCap: string
  sortBy: 'marketCap' | 'pe' | 'price' | 'name'
  order: 'asc' | 'desc'
}

export async function fetchStocks(query: ScreenerQuery): Promise<Stock[]> {
  const params = new URLSearchParams()
  if (query.q.trim()) params.set('q', query.q.trim())
  if (query.maxPe.trim()) params.set('maxPe', query.maxPe.trim())
  if (query.minMarketCap.trim()) params.set('minMarketCap', query.minMarketCap.trim())
  params.set('sortBy', query.sortBy)
  params.set('order', query.order)

  const response = await fetch(`/api/stocks?${params}`)
  if (!response.ok) {
    const problem = await response.json().catch(() => null)
    throw new Error(problem?.detail ?? `Request failed with status ${response.status}`)
  }
  return response.json()
}
