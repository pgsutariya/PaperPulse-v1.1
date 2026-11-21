<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/cbc1a11b-0b7d-4a32-a77e-909013724836

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` in that file to your Gemini API key (see `.env.example` for an example)
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device
7. If you have already published your app in AI Studio, please [request upload key reset](https://support.google.com/googleplay/android-developer/answer/9842756#zippy=%2Crequest-an-upload-key-reset) in Google Play Console.


---

## PaperPulse — Analysis & Strategy Engine (v1.1)

Works for **NSE, BSE, MCX (Angel One SmartAPI)** and **Crypto (Binance)**, plus the built-in simulator.

### Build the APK
* **Android Studio:** open the folder, let it sync, then *Build → Build APK(s)*. Create `debug.keystore`
  in the project root first (or remove the `signingConfig = signingConfigs.getByName("debugConfig")` line):
  `keytool -genkeypair -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -validity 10000 -dname "CN=Android Debug,O=Android,C=US"`
* **GitHub Actions:** push this folder to a GitHub repo — `.github/workflows/build-apk.yml` builds
  `app-debug.apk` and attaches it to the run as the `PaperPulse-debug-apk` artifact.

### Chart & Analysis screen
Open any instrument → **Analytics icon** (top bar), or *Open Chart & Analysis* from a signal.
* Timeframes 1m · 3m · 5m · 10m · 15m · 30m · 45m · 1H · 4H · 1D · 1W · 1Mo, bars anchored to the exchange session
  (09:15 IST NSE/BSE, 09:00 IST MCX, UTC for crypto).
* **HTF shadow candles**: 1m→3m/5m/10m, 3m→5m/10m/15m, 5m→10m/15m/30m, 15m→30m/45m/1H, 30m→45m/1H/4H,
  45m→1H/4H/1D, 1H→4H/1D/1W, 4H→1D/1W/1Mo …
* Overlays: EMA 9/21, EMA 50/200, session VWAP with ±1/2/3σ bands, Pivots + CPR, order blocks, supply/demand
  bases, FVGs (★ = institutional), liquidity (BSL/SSL, equal highs/lows, PDH/PDL — pending/swept/broken),
  BOS/CHoCH, premium/discount, volume profile (POC/VAH/VAL), RSI/volume/delta divergence lines, setup levels.
* Lower pane: Volume + 20 MA (bright bars = above MA), RSI 14, OBV, Elder Force 13, Delta/CVD, ATR 7/14.
* Panels: EMA setups with RR ladder + backtest, SMC structure/POI, liquidity, AMD (1H/4H/1D) + MDR (1m–15m,
  kill zones), pivots, volume/order-flow, divergences, quant models.

### EMA crossover → retest → break model
| Pair | Styles (timeframes) |
|---|---|
| 9/21 | Scalping (1m/3m), Sniper (1m/3m/5m, retest-candle trigger + volume > MA20 + kill zone), Intraday (5m/15m) |
| 50/200 | Swing (1H/4H), Short-term (4H/1D), Investing (1D), Long-term (1W/1Mo) |

Crossover candle high/low is marked → price must retest the fast/slow EMA zone without breaking structure →
a candle must **close** beyond the crossover-candle high (buy) / low (sell) → entry. SL below the retest swing
low or the slow EMA (above for sells). Targets: **1:1, 1:1.25, 1:1.5, 1:1.75, 1:2, 1:2.25, 1:2.5, 1:2.75, 1:3**
(`DEFAULT_RR_LADDER`). Opposite crossover or a structure break invalidates the setup.

### Signal strategies added
EMA 9/21 (Scalp/Sniper/Intraday), EMA 50/200 (Swing/Short-term/Investing/Long-term), SMC/ICT
(sweep → displacement → FVG rebalance in a kill zone, HTF-bias aligned, discount/premium entry, draw on
liquidity target), Trap reversal, Pivot/CPR breakout, Quant z-score mean reversion (Hurst-filtered),
Quant squeeze + Donchian breakout.

### Real history
Crypto history comes from Binance public klines (with taker-buy volume → true delta). NSE/BSE/MCX history
comes from Angel One `getCandleData` once SmartAPI credentials are set in Feed Settings. Without a source,
synthetic warm-up bars are used (the chart says which).
