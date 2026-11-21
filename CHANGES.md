# PaperPulse v1.1 — change log

## Bug fixes
| Area | Bug | Fix |
|---|---|---|
| Signals | Evaluated on the **forming** candle (`includeInProgress = true`), right after a bucket roll that is a 1-tick bar → repainting / false crossovers | Signals now use closed candles only |
| Candles | Bars aligned to UTC epoch: NSE 30m/1H/4H bars started at 09:00/09:30 instead of 09:15; no session awareness | Exchange-aware `SessionCalendar` (09:15 IST NSE/BSE, 09:00 IST MCX, UTC crypto, Monday weeks, calendar months) |
| Candles | Missing volume was faked (150/250/120 per tick) and a day-volume reset produced a constant | Real per-trade quantity used when available; day roll handled; unknown = 0 |
| Candles | Only 64 synthetic bars / 200 max → EMA 200 never computed | 260 warm-up bars, 500 max, real history backfill (Binance klines, Angel One getCandleData) |
| Square-off | **MCX intraday positions were squared off at 15:15 IST** (NSE time) and MCX DAY orders cancelled mid-session | MCX square-off window 23:15–23:30 IST |
| Orders | Pending LIMIT/SL orders reserved no margin and the fill never re-checked it (margin silently clamped to 0) | Margin re-checked at fill; order rejected with reason |
| Orders | BUY with SL above entry / target below entry accepted (stopped out on first tick) | Geometry validation for SL/Target (price & %) |
| Orders | New MIS orders accepted during the square-off window, then instantly squared off | Rejected with a message to use Carry Forward |
| Orders | F&O quantity not checked against lot size | Must be a multiple of lot size |
| ORB strategy | Fell back to the **whole history** as the "opening range" when the session had < 4 bars | Session = today's exchange hours only |
| SWIG strategy | VWAP over an arbitrary last-24-bar window | Session-anchored VWAP |
| Scalp strategy | `Double.MIN_VALUE` (smallest positive) used as "minus infinity" fallback | `-Double.MAX_VALUE` |
| Signals list | Expired signals stayed visible until another DB write | 30 s expiry clock |
| Engine | One evaluator throwing would abort the whole scan | Each evaluator isolated with `runCatching` |

## Known limitation (not changed)
Crypto prices/P&L are in USD while the account is in INR, and quantities are whole numbers, so fractional
crypto sizing and FX conversion need a schema change (planned separately).

## New features
See README → "Analysis & Strategy Engine".
