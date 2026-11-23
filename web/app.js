(() => {
  "use strict";

  const STORAGE_KEY = "paperpulse_web_state_v2";
  const DEFAULT_CAPITAL = 1000000;
  const INR_PER_USD_FALLBACK = 84.5;
  const TROY_OZ_TO_10G = 10 / 31.1034768;
  const TROY_OZ_TO_1KG = 1000 / 31.1034768;

  // =========================================================================
  // 1. INSTRUMENT CATALOG (NSE, BSE, MCX, F&O, CRYPTO, CRYPTO F&O)
  // =========================================================================
  const SEED_INSTRUMENTS = [
    // NSE Indices
    { symbol: "NIFTY50", name: "NIFTY 50 Index", segment: "NSE", type: "INDEX", category: "INDEX", currency: "₹", lotSize: 75, tickSize: 0.05, ltp: 24380.50, prevClose: 24250.00, yahooSym: "^NSEI", volPct: 0.0005 },
    { symbol: "BANKNIFTY", name: "NIFTY Bank Index", segment: "NSE", type: "INDEX", category: "INDEX", currency: "₹", lotSize: 30, tickSize: 0.05, ltp: 52140.00, prevClose: 51890.00, yahooSym: "^NSEBANK", volPct: 0.0006 },
    // NSE Stocks
    { symbol: "RELIANCE", name: "Reliance Industries", segment: "NSE", type: "EQUITY", category: "STOCK", currency: "₹", lotSize: 1, tickSize: 0.05, ltp: 1324.40, prevClose: 1312.10, yahooSym: "RELIANCE.NS", volPct: 0.0008 },
    { symbol: "TCS", name: "Tata Consultancy Services", segment: "NSE", type: "EQUITY", category: "STOCK", currency: "₹", lotSize: 1, tickSize: 0.05, ltp: 4128.75, prevClose: 4096.20, yahooSym: "TCS.NS", volPct: 0.0007 },
    { symbol: "HDFCBANK", name: "HDFC Bank Ltd", segment: "NSE", type: "EQUITY", category: "STOCK", currency: "₹", lotSize: 1, tickSize: 0.05, ltp: 1742.50, prevClose: 1730.00, yahooSym: "HDFCBANK.NS", volPct: 0.0007 },
    { symbol: "INFY", name: "Infosys Ltd", segment: "NSE", type: "EQUITY", category: "STOCK", currency: "₹", lotSize: 1, tickSize: 0.05, ltp: 1864.20, prevClose: 1848.60, yahooSym: "INFY.NS", volPct: 0.0008 },
    { symbol: "TATAMOTORS", name: "Tata Motors Ltd", segment: "NSE", type: "EQUITY", category: "STOCK", currency: "₹", lotSize: 1, tickSize: 0.05, ltp: 896.30, prevClose: 904.10, yahooSym: "TATAMOTORS.NS", volPct: 0.0011 },
    // NSE F&O (Anchored to live ^NSEI / ^NSEBANK spot quotes!)
    { symbol: "NIFTY-FUT", name: "NIFTY Current Month Fut", segment: "NSE", type: "FUTURES", category: "FNO", currency: "₹", lotSize: 75, tickSize: 0.05, ltp: 24445.00, prevClose: 24315.00, underlyingSym: "NIFTY50", basisPoints: 65, volPct: 0.0006 },
    { symbol: "BANKNIFTY-FUT", name: "BANKNIFTY Current Fut", segment: "NSE", type: "FUTURES", category: "FNO", currency: "₹", lotSize: 30, tickSize: 0.05, ltp: 52290.00, prevClose: 52040.00, underlyingSym: "BANKNIFTY", basisPoints: 150, volPct: 0.0007 },
    { symbol: "NIFTY24400CE", name: "NIFTY 24400 Call Opt", segment: "NSE", type: "OPTIONS", category: "FNO", currency: "₹", lotSize: 75, tickSize: 0.05, ltp: 186.50, prevClose: 162.40, underlyingSym: "NIFTY50", optionType: "CE", strike: 24400, volPct: 0.0038 },
    { symbol: "NIFTY24400PE", name: "NIFTY 24400 Put Opt", segment: "NSE", type: "OPTIONS", category: "FNO", currency: "₹", lotSize: 75, tickSize: 0.05, ltp: 164.20, prevClose: 185.10, underlyingSym: "NIFTY50", optionType: "PE", strike: 24400, volPct: 0.0038 },
    { symbol: "BANKNIFTY52000CE", name: "BANKNIFTY 52000 Call", segment: "NSE", type: "OPTIONS", category: "FNO", currency: "₹", lotSize: 30, tickSize: 0.05, ltp: 448.00, prevClose: 392.00, underlyingSym: "BANKNIFTY", optionType: "CE", strike: 52000, volPct: 0.0042 },
    // BSE Index & Stocks
    { symbol: "SENSEX", name: "BSE SENSEX Index", segment: "BSE", type: "INDEX", category: "INDEX", currency: "₹", lotSize: 20, tickSize: 0.05, ltp: 80120.40, prevClose: 79840.00, yahooSym: "^BSESN", volPct: 0.0005 },
    { symbol: "SBIN-BSE", name: "State Bank of India", segment: "BSE", type: "EQUITY", category: "STOCK", currency: "₹", lotSize: 1, tickSize: 0.05, ltp: 824.80, prevClose: 816.20, yahooSym: "SBIN.BO", volPct: 0.0009 },
    { symbol: "BHARTIARTL-BSE", name: "Bharti Airtel Ltd", segment: "BSE", type: "EQUITY", category: "STOCK", currency: "₹", lotSize: 1, tickSize: 0.05, ltp: 1654.10, prevClose: 1639.00, yahooSym: "BHARTIARTL.BO", volPct: 0.0008 },
    // MCX Commodities (Live Yahoo Global Futures × Live USD/INR)
    { symbol: "GOLDM-FUT", name: "MCX Gold Mini (10g)", segment: "MCX", type: "FUTURES", category: "MCX", currency: "₹", lotSize: 10, tickSize: 1, ltp: 76840.00, prevClose: 76310.00, yahooSym: "GC=F", mcxConv: "GOLD_10G", volPct: 0.0005 },
    { symbol: "SILVERM-FUT", name: "MCX Silver Mini (1kg)", segment: "MCX", type: "FUTURES", category: "MCX", currency: "₹", lotSize: 5, tickSize: 1, ltp: 91420.00, prevClose: 90650.00, yahooSym: "SI=F", mcxConv: "SILVER_1KG", volPct: 0.0008 },
    { symbol: "CRUDEOIL-FUT", name: "MCX Crude Oil (bbl)", segment: "MCX", type: "FUTURES", category: "MCX", currency: "₹", lotSize: 100, tickSize: 1, ltp: 5940.00, prevClose: 5892.00, yahooSym: "CL=F", mcxConv: "CRUDE_BBL", volPct: 0.0012 },
    { symbol: "NATURALGAS-FUT", name: "MCX Natural Gas", segment: "MCX", type: "FUTURES", category: "MCX", currency: "₹", lotSize: 1250, tickSize: 0.1, ltp: 238.40, prevClose: 234.10, yahooSym: "NG=F", mcxConv: "NATGAS", volPct: 0.0018 },
    // Crypto Spot (Live Binance WS + REST)
    { symbol: "BTCUSDT", name: "Bitcoin / USDT Spot", segment: "CRYPTO", type: "CRYPTO", category: "CRYPTO", currency: "$", lotSize: 1, tickSize: 0.01, ltp: 91450.00, prevClose: 89920.00, binanceSym: "BTCUSDT", volPct: 0.0012 },
    { symbol: "ETHUSDT", name: "Ethereum / USDT Spot", segment: "CRYPTO", type: "CRYPTO", category: "CRYPTO", currency: "$", lotSize: 1, tickSize: 0.01, ltp: 3180.00, prevClose: 3115.00, binanceSym: "ETHUSDT", volPct: 0.0015 },
    { symbol: "SOLUSDT", name: "Solana / USDT Spot", segment: "CRYPTO", type: "CRYPTO", category: "CRYPTO", currency: "$", lotSize: 1, tickSize: 0.01, ltp: 214.60, prevClose: 206.40, binanceSym: "SOLUSDT", volPct: 0.0020 },
    // Crypto F&O Perpetuals (Live Binance Spot + Funding Basis)
    { symbol: "BTCUSDT-PERP", name: "BTC Perpetual Futures", segment: "CRYPTO", type: "FUTURES", category: "CRYPTO_FNO", currency: "$", lotSize: 1, tickSize: 0.01, ltp: 91495.00, prevClose: 89960.00, binanceSym: "BTCUSDT", basisPct: 0.0005, volPct: 0.0013 },
    { symbol: "ETHUSDT-PERP", name: "ETH Perpetual Futures", segment: "CRYPTO", type: "FUTURES", category: "CRYPTO_FNO", currency: "$", lotSize: 1, tickSize: 0.01, ltp: 3182.50, prevClose: 3117.00, binanceSym: "ETHUSDT", basisPct: 0.0006, volPct: 0.0016 },
  ];

  const TF_SECONDS = { "1m": 60, "5m": 300, "15m": 900, "1H": 3600, "1D": 86400 };
  const BINANCE_TF = { "1m": "1m", "5m": "5m", "15m": "15m", "1H": "1h", "1D": "1d" };
  const YAHOO_TF = {
    "1m": { interval: "1m", range: "1d" },
    "5m": { interval: "5m", range: "5d" },
    "15m": { interval: "15m", range: "5d" },
    "1H": { interval: "60m", range: "1mo" },
    "1D": { interval: "1d", range: "6mo" },
  };

  // =========================================================================
  // 2. APPLICATION STATE & PERSISTENCE
  // =========================================================================
  let state = loadState();
  let usdInrRate = INR_PER_USD_FALLBACK;
  let activeRelayIndex = 0;

  // Live runtime market map
  const market = new Map();
  const candlesByKey = new Map(); // key = `${symbol}_${tf}`
  const liveHistoryFetched = new Set();

  SEED_INSTRUMENTS.forEach((inst) => {
    market.set(inst.symbol, {
      ...inst,
      high: inst.ltp * 1.008,
      low: inst.ltp * 0.992,
      open: inst.prevClose,
      isLive: false,
      source: "Simulated",
      lastUpdated: Date.now(),
    });
  });

  // UI runtime state
  let currentTab = "watchlist";
  let currentSegmentFilter = "ALL";
  let currentSearch = "";
  let currentChartSymbol = "NIFTY50";
  let currentTf = "5m";
  let currentSignalSubTab = "active";
  let currentSignalCategory = "ALL";
  let currentSignalDir = "ALL";
  let currentOrderFilter = "ALL";
  let currentJournalSubTab = "trades";

  // Chart viewport (TradingView zoom/pan/measure/expand)
  let chartZoomCount = 72; // visible bars
  let chartPanOffset = 0;  // bars from right edge (0 = latest)
  let chartMeasureMode = false;
  let measurePointA = null; // { barIdx, price, time }
  let measurePointB = null;
  let isChartExpanded = false;
  let isDraggingChart = false;
  let dragStartX = 0;
  let dragStartOffset = 0;
  let hoverCrosshair = null; // { x, y }
  let lastChartGeom = null;

  // Modal state
  let orderModalSymbol = null;
  let orderModalSide = "BUY";
  let modifyOrderId = null;
  let averagePosId = null;
  let fundsMode = "ADD";

  // Signals runtime
  let activeSignals = [];
  let binanceWs = null;
  let binanceConnected = false;

  function loadState() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const parsed = JSON.parse(raw);
        return {
          capital: typeof parsed.capital === "number" ? parsed.capital : DEFAULT_CAPITAL,
          realisedPnl: typeof parsed.realisedPnl === "number" ? parsed.realisedPnl : 0,
          positions: Array.isArray(parsed.positions) ? parsed.positions : [],
          orders: Array.isArray(parsed.orders) ? parsed.orders : [],
          journal: Array.isArray(parsed.journal) ? parsed.journal : [],
          capitalLedger: Array.isArray(parsed.capitalLedger) ? parsed.capitalLedger : [
            { id: "cap_init", type: "RESET", amount: DEFAULT_CAPITAL, balanceAfter: DEFAULT_CAPITAL, note: "Initial Paper Capital", time: Date.now() },
          ],
          signalRecords: Array.isArray(parsed.signalRecords) ? parsed.signalRecords : [],
          notificationsEnabled: Boolean(parsed.notificationsEnabled),
          signalNotificationsEnabled: parsed.signalNotificationsEnabled !== false,
          broker: parsed.broker || { provider: "PUBLIC_FREE", apiKey: "", accessToken: "", connectedAt: null },
          theme: parsed.theme || "dark",
        };
      }
    } catch (_) { /* ignore corrupt storage */ }
    return {
      capital: DEFAULT_CAPITAL,
      realisedPnl: 0,
      positions: [],
      orders: [],
      journal: [],
      capitalLedger: [
        { id: "cap_init", type: "RESET", amount: DEFAULT_CAPITAL, balanceAfter: DEFAULT_CAPITAL, note: "Initial Paper Capital", time: Date.now() },
      ],
      signalRecords: [],
      notificationsEnabled: false,
      signalNotificationsEnabled: true,
      broker: { provider: "PUBLIC_FREE", apiKey: "", accessToken: "", connectedAt: null },
      theme: "dark",
    };
  }

  function saveState() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    } catch (_) { /* storage quota fallback */ }
  }

  // =========================================================================
  // 3. UTILITIES, FORMATTERS & SAFE ESCAPING
  // =========================================================================
  function esc(str) {
    return String(str ?? "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function fmtInr(val, showSign = false) {
    const abs = Math.abs(Number(val) || 0);
    const formatted = abs.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    if (showSign) return (val >= 0 ? "+₹" : "-₹") + formatted;
    return (val < 0 ? "-₹" : "₹") + formatted;
  }

  function fmtPrice(val, currency = "₹") {
    const num = Number(val) || 0;
    const decimals = num >= 1000 ? 2 : num >= 1 ? 2 : 4;
    return currency + num.toLocaleString("en-IN", { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
  }

  function fmtQtyLots(qty, lotSize) {
    const ls = Math.max(1, Number(lotSize) || 1);
    const lots = Math.max(1, Math.round(qty / ls));
    return `${qty} (${lots} lot${lots > 1 ? "s" : ""} × ${ls})`;
  }

  function roundTick(price, tick = 0.05) {
    return Math.max(tick, Math.round(price / tick) * tick);
  }

  function getIstParts(date = new Date()) {
    const utcMs = date.getTime() + date.getTimezoneOffset() * 60000;
    const ist = new Date(utcMs + 5.5 * 3600000);
    return {
      date: ist,
      day: ist.getDay(), // 0 Sun, 6 Sat
      hours: ist.getHours(),
      minutes: ist.getMinutes(),
      seconds: ist.getSeconds(),
      totalMinutes: ist.getHours() * 60 + ist.getMinutes(),
    };
  }

  function fmtIstTime(ts) {
    return new Date(ts).toLocaleTimeString("en-IN", {
      timeZone: "Asia/Kolkata",
      hour12: false,
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
  }

  function fmtIstDateTime(ts) {
    return new Date(ts).toLocaleString("en-IN", {
      timeZone: "Asia/Kolkata",
      day: "2-digit",
      month: "short",
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    });
  }

  function fmtIstShortAxis(ts, tf) {
    const d = new Date(ts);
    if (tf === "1D") {
      return d.toLocaleDateString("en-IN", { timeZone: "Asia/Kolkata", day: "2-digit", month: "short" });
    }
    return d.toLocaleTimeString("en-IN", { timeZone: "Asia/Kolkata", hour: "2-digit", minute: "2-digit", hour12: false });
  }

  function genId(prefix) {
    return `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;
  }

  function showToast(msg, type = "info") {
    const container = document.getElementById("toastContainer");
    const el = document.createElement("div");
    el.className = `toast ${type}`;
    el.textContent = msg;
    container.appendChild(el);
    setTimeout(() => {
      el.style.opacity = "0";
      setTimeout(() => el.remove(), 220);
    }, 3800);
  }

  // Browser Notification helper
  function sendBrowserNotification(title, body, isSignal = false) {
    if (!state.notificationsEnabled) return;
    if (isSignal && !state.signalNotificationsEnabled) return;
    if (!("Notification" in window)) return;
    if (Notification.permission === "granted") {
      try {
        new Notification(title, { body, tag: isSignal ? "pp_signal" : "pp_trade" });
      } catch (_) { /* ignore blocked notification */ }
    }
  }

  // =========================================================================
  // 4. INDIA SESSION TIMING (15:15 EQ/FUT vs 15:40 OPTIONS) & CHARGES ENGINE
  // =========================================================================
  function getIntradaySquareOffInfo(inst) {
    if (!inst) return { label: "15:15 IST", closeMins: 15 * 60 + 15, window: "09:15–15:15 IST" };
    if (inst.segment === "CRYPTO") {
      return { label: "24×7 (No auto close)", closeMins: null, window: "24×7 Continuous" };
    }
    if (inst.segment === "MCX") {
      return { label: "23:25 IST", closeMins: 23 * 60 + 25, window: "09:00–23:30 IST" };
    }
    if (inst.type === "OPTIONS") {
      return { label: "15:40 IST (Options)", closeMins: 15 * 60 + 40, window: "09:15–15:40 IST (Options)" };
    }
    return { label: "15:15 IST (Eq/Fut)", closeMins: 15 * 60 + 15, window: "09:15–15:15 IST (Eq/Fut)" };
  }

  function isIntradaySquareOffDue(inst, now = new Date()) {
    if (!inst || inst.segment === "CRYPTO") return false;
    const p = getIstParts(now);
    if (p.day === 0 || p.day === 6) return false;
    const info = getIntradaySquareOffInfo(inst);
    if (info.closeMins == null) return false;
    return p.totalMinutes >= info.closeMins && p.totalMinutes < info.closeMins + 10;
  }

  function getInrMultiplier(inst) {
    return inst && inst.currency === "$" ? usdInrRate : 1;
  }

  function getLeverage(inst, product) {
    if (product === "INTRADAY") {
      if (inst.type === "OPTIONS") return 1;
      if (inst.type === "FUTURES") return 5;
      if (inst.segment === "CRYPTO") return 5;
      return 5;
    }
    return inst.type === "FUTURES" ? 3 : 1;
  }

  function calculateMarginRequired(inst, qty, price, product) {
    const notionalInr = qty * price * getInrMultiplier(inst);
    return notionalInr / getLeverage(inst, product);
  }

  // Indian & Crypto Itemized Brokerage + Statutory Taxes Calculator (in INR)
  function calculateOrderCharges(inst, side, qty, price, product) {
    const turnoverInr = qty * price * getInrMultiplier(inst);
    if (turnoverInr <= 0) {
      return { brokerage: 0, stt: 0, exchangeTxn: 0, sebi: 0, stampDuty: 0, gst: 0, total: 0 };
    }

    let brokerage = 0;
    let stt = 0;
    let exchangeTxn = 0;
    let sebi = 0;
    let stampDuty = 0;
    let gst = 0;

    if (inst.segment === "CRYPTO") {
      brokerage = turnoverInr * (inst.type === "FUTURES" ? 0.0005 : 0.001); // 0.05% perp / 0.1% spot
      exchangeTxn = turnoverInr * 0.0002;
      gst = (brokerage + exchangeTxn) * 0.18;
    } else if (inst.segment === "MCX") {
      brokerage = Math.min(20, turnoverInr * 0.0003);
      stt = side === "SELL" ? turnoverInr * 0.0001 : 0; // CTT 0.01% on sell
      exchangeTxn = turnoverInr * 0.000026;
      sebi = turnoverInr * 0.000001;
      stampDuty = side === "BUY" ? turnoverInr * 0.00002 : 0;
      gst = (brokerage + exchangeTxn + sebi) * 0.18;
    } else if (inst.type === "OPTIONS") {
      brokerage = 20; // Flat ₹20 per executed option order
      stt = side === "SELL" ? turnoverInr * 0.001 : 0; // 0.1% on sell premium
      exchangeTxn = turnoverInr * 0.0003503; // NSE Options txn charge
      sebi = turnoverInr * 0.000001;
      stampDuty = side === "BUY" ? turnoverInr * 0.00003 : 0;
      gst = (brokerage + exchangeTxn + sebi) * 0.18;
    } else if (inst.type === "FUTURES") {
      brokerage = Math.min(20, turnoverInr * 0.0003);
      stt = side === "SELL" ? turnoverInr * 0.0002 : 0; // 0.02% on sell
      exchangeTxn = turnoverInr * 0.0000173;
      sebi = turnoverInr * 0.000001;
      stampDuty = side === "BUY" ? turnoverInr * 0.00002 : 0;
      gst = (brokerage + exchangeTxn + sebi) * 0.18;
    } else {
      // NSE / BSE Equity
      if (product === "CARRY_FORWARD") {
        brokerage = 0; // Zero delivery brokerage standard
        stt = turnoverInr * 0.001; // 0.1% both sides
        stampDuty = side === "BUY" ? turnoverInr * 0.00015 : 0;
      } else {
        brokerage = Math.min(20, turnoverInr * 0.0003); // Flat ₹20 or 0.03%
        stt = side === "SELL" ? turnoverInr * 0.00025 : 0; // 0.025% sell side
        stampDuty = side === "BUY" ? turnoverInr * 0.00003 : 0;
      }
      exchangeTxn = turnoverInr * (inst.segment === "BSE" ? 0.0000375 : 0.0000297);
      sebi = turnoverInr * 0.000001;
      gst = (brokerage + exchangeTxn + sebi) * 0.18;
    }

    const total = brokerage + stt + exchangeTxn + sebi + stampDuty + gst;
    return { brokerage, stt, exchangeTxn, sebi, stampDuty, gst, total };
  }

  function sumCharges(a, b) {
    const x = a || { brokerage: 0, stt: 0, exchangeTxn: 0, sebi: 0, stampDuty: 0, gst: 0, total: 0 };
    const y = b || { brokerage: 0, stt: 0, exchangeTxn: 0, sebi: 0, stampDuty: 0, gst: 0, total: 0 };
    return {
      brokerage: x.brokerage + y.brokerage,
      stt: x.stt + y.stt,
      exchangeTxn: x.exchangeTxn + y.exchangeTxn,
      sebi: x.sebi + y.sebi,
      stampDuty: x.stampDuty + y.stampDuty,
      gst: x.gst + y.gst,
      total: x.total + y.total,
    };
  }

  // =========================================================================
  // 5. LIVE PUBLIC DATA FEEDS (BINANCE WS + BINANCE REST + YAHOO NSE/BSE/MCX)
  // =========================================================================
  const CORS_RELAYS = [
    (url) => `https://corsproxy.io/?url=${encodeURIComponent(url)}`,
    (url) => `https://api.allorigins.win/raw?url=${encodeURIComponent(url)}`,
    (url) => `https://api.codetabs.com/v1/proxy?quest=${encodeURIComponent(url)}`,
  ];

  async function fetchJsonWithCorsRelay(targetUrl, timeoutMs = 8000) {
    for (let attempt = 0; attempt < CORS_RELAYS.length; attempt++) {
      const idx = (activeRelayIndex + attempt) % CORS_RELAYS.length;
      const proxied = CORS_RELAYS[idx](targetUrl);
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), timeoutMs);
      try {
        const res = await fetch(proxied, { signal: ctrl.signal, cache: "no-store" });
        clearTimeout(timer);
        if (!res.ok) continue;
        const json = await res.json();
        if (json) {
          activeRelayIndex = idx;
          return json;
        }
      } catch (_) {
        clearTimeout(timer);
      }
    }
    return null;
  }

  function applyPriceUpdate(symbol, newLtp, meta = {}) {
    const inst = market.get(symbol);
    if (!inst || !Number.isFinite(newLtp) || newLtp <= 0) return;

    const price = roundTick(newLtp, inst.tickSize);
    inst.ltp = price;
    if (Number.isFinite(meta.prevClose) && meta.prevClose > 0) inst.prevClose = meta.prevClose;
    if (Number.isFinite(meta.high) && meta.high > 0) inst.high = Math.max(meta.high, price);
    else inst.high = Math.max(inst.high || price, price);
    if (Number.isFinite(meta.low) && meta.low > 0) inst.low = Math.min(meta.low, price);
    else inst.low = Math.min(inst.low || price, price);
    if (meta.isLive) {
      inst.isLive = true;
      inst.source = meta.source || "Live Public";
    }
    inst.lastUpdated = Date.now();

    // Update live candles for currently cached timeframes
    Object.keys(TF_SECONDS).forEach((tf) => {
      const key = `${symbol}_${tf}`;
      const list = candlesByKey.get(key);
      if (list && list.length > 0) {
        updateCandleWithTick(list, price, TF_SECONDS[tf]);
      }
    });

    // Propagate spot updates to derived F&O instruments (NIFTY/BANKNIFTY Fut & Opts)
    if (symbol === "NIFTY50" || symbol === "BANKNIFTY") {
      syncDerivedFnoFromSpot(symbol, inst);
    }
  }

  function syncDerivedFnoFromSpot(spotSym, spotInst) {
    market.forEach((child) => {
      if (child.underlyingSym !== spotSym) return;
      let derivedPrice = child.ltp;
      if (child.type === "FUTURES") {
        derivedPrice = spotInst.ltp + (child.basisPoints || 60);
        const derivedPrev = spotInst.prevClose + (child.basisPoints || 60);
        applyPriceUpdate(child.symbol, derivedPrice, {
          prevClose: derivedPrev,
          isLive: spotInst.isLive,
          source: spotInst.isLive ? "Live Spot-Anchored" : "Simulated",
        });
      } else if (child.type === "OPTIONS") {
        const intrinsic = child.optionType === "CE"
          ? Math.max(0, spotInst.ltp - child.strike)
          : Math.max(0, child.strike - spotInst.ltp);
        const prevIntrinsic = child.optionType === "CE"
          ? Math.max(0, spotInst.prevClose - child.strike)
          : Math.max(0, child.strike - spotInst.prevClose);
        const timeVal = spotSym === "NIFTY50" ? 168 : 345;
        const distDecay = Math.exp(-Math.abs(spotInst.ltp - child.strike) / (spotSym === "NIFTY50" ? 550 : 1200));
        derivedPrice = Math.max(5, intrinsic + timeVal * distDecay);
        const prevDistDecay = Math.exp(-Math.abs(spotInst.prevClose - child.strike) / (spotSym === "NIFTY50" ? 550 : 1200));
        const derivedPrev = Math.max(5, prevIntrinsic + timeVal * prevDistDecay);
        applyPriceUpdate(child.symbol, derivedPrice, {
          prevClose: derivedPrev,
          isLive: spotInst.isLive,
          source: spotInst.isLive ? "Live Spot-Anchored" : "Simulated",
        });
      }
    });
  }

  // A) Binance Live WebSocket (Crypto Spot + Perps)
  function startBinanceWebSocket() {
    try {
      if (binanceWs) {
        try { binanceWs.close(); } catch (_) {}
      }
      const streams = "btcusdt@ticker/ethusdt@ticker/solusdt@ticker";
      binanceWs = new WebSocket(`wss://data-stream.binance.vision/stream?streams=${streams}`);
      binanceWs.onopen = () => {
        binanceConnected = true;
        updateFeedStatusPill();
      };
      binanceWs.onmessage = (evt) => {
        try {
          const msg = JSON.parse(evt.data);
          const d = msg && msg.data;
          if (!d || !d.s) return;
          const bSym = d.s.toUpperCase();
          const last = parseFloat(d.c);
          const open = parseFloat(d.o);
          const high = parseFloat(d.h);
          const low = parseFloat(d.l);
          if (!Number.isFinite(last) || last <= 0) return;

          market.forEach((inst) => {
            if (inst.binanceSym === bSym) {
              const mult = 1 + (inst.basisPct || 0);
              applyPriceUpdate(inst.symbol, last * mult, {
                prevClose: Number.isFinite(open) ? open * mult : undefined,
                high: Number.isFinite(high) ? high * mult : undefined,
                low: Number.isFinite(low) ? low * mult : undefined,
                isLive: true,
                source: "Binance Live WS",
              });
            }
          });
        } catch (_) {}
      };
      binanceWs.onclose = () => {
        binanceConnected = false;
        updateFeedStatusPill();
        setTimeout(startBinanceWebSocket, 7000);
      };
      binanceWs.onerror = () => {
        binanceConnected = false;
      };
    } catch (_) {
      binanceConnected = false;
    }
  }

  // B) Binance Public REST Fallback & Fast Refresh (CORS-open natively, zero proxy needed!)
  async function pollBinanceRest() {
    try {
      const syms = encodeURIComponent(JSON.stringify(["BTCUSDT", "ETHUSDT", "SOLUSDT"]));
      const res = await fetch(`https://data-api.binance.vision/api/v3/ticker/24hr?symbols=${syms}`, { cache: "no-store" });
      if (!res.ok) return;
      const arr = await res.json();
      if (!Array.isArray(arr)) return;
      arr.forEach((item) => {
        const bSym = String(item.symbol || "").toUpperCase();
        const last = parseFloat(item.lastPrice);
        const open = parseFloat(item.openPrice);
        const high = parseFloat(item.highPrice);
        const low = parseFloat(item.lowPrice);
        if (!Number.isFinite(last) || last <= 0) return;
        market.forEach((inst) => {
          if (inst.binanceSym === bSym) {
            const mult = 1 + (inst.basisPct || 0);
            applyPriceUpdate(inst.symbol, last * mult, {
              prevClose: Number.isFinite(open) ? open * mult : undefined,
              high: Number.isFinite(high) ? high * mult : undefined,
              low: Number.isFinite(low) ? low * mult : undefined,
              isLive: true,
              source: binanceConnected ? "Binance Live" : "Binance REST Live",
            });
          }
        });
      });
      updateFeedStatusPill();
    } catch (_) {}
  }

  // C) Live USD/INR FX + NSE / BSE / MCX via Yahoo Finance v8 Public Chart API
  function convertMcxToInr(usdPrice, convType) {
    if (!Number.isFinite(usdPrice) || usdPrice <= 0) return usdPrice;
    if (convType === "GOLD_10G") return usdPrice * TROY_OZ_TO_10G * usdInrRate * 1.06; // import duty + local premium
    if (convType === "SILVER_1KG") return usdPrice * TROY_OZ_TO_1KG * usdInrRate * 1.06;
    if (convType === "CRUDE_BBL") return usdPrice * usdInrRate;
    if (convType === "NATGAS") return usdPrice * usdInrRate;
    return usdPrice * usdInrRate;
  }

  async function fetchYahooQuote(yahooSym) {
    const url = `https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(yahooSym)}?interval=5m&range=1d`;
    const json = await fetchJsonWithCorsRelay(url, 8500);
    const result = json && json.chart && json.chart.result && json.chart.result[0];
    if (!result || !result.meta) return null;
    const meta = result.meta;
    const price = Number(meta.regularMarketPrice);
    if (!Number.isFinite(price) || price <= 0) return null;
    const prevClose = Number(meta.chartPreviousClose || meta.previousClose || price);
    const high = Number(meta.regularMarketDayHigh || price);
    const low = Number(meta.regularMarketDayLow || price);
    return { price, prevClose, high, low, result };
  }

  let isPollingYahoo = false;
  async function pollPublicIndiaAndMcxQuotes(showToastOnComplete = false) {
    if (isPollingYahoo) return;
    isPollingYahoo = true;
    let updatedCount = 0;

    try {
      // 1. Update live USD/INR first for accurate MCX INR conversion
      const fx = await fetchYahooQuote("INR=X");
      if (fx && fx.price > 50 && fx.price < 150) {
        usdInrRate = fx.price;
      }

      // 2. Fetch all instruments with yahooSym in controlled batches of 4
      const yahooInsts = Array.from(market.values()).filter((i) => Boolean(i.yahooSym));
      for (let i = 0; i < yahooInsts.length; i += 4) {
        const batch = yahooInsts.slice(i, i + 4);
        await Promise.all(
          batch.map(async (inst) => {
            const q = await fetchYahooQuote(inst.yahooSym);
            if (!q) return;
            const conv = inst.mcxConv;
            const ltp = conv ? convertMcxToInr(q.price, conv) : q.price;
            const prev = conv ? convertMcxToInr(q.prevClose, conv) : q.prevClose;
            const hi = conv ? convertMcxToInr(q.high, conv) : q.high;
            const lo = conv ? convertMcxToInr(q.low, conv) : q.low;

            applyPriceUpdate(inst.symbol, ltp, {
              prevClose: prev,
              high: hi,
              low: lo,
              isLive: true,
              source: inst.segment === "MCX" ? "Live MCX/FX" : `Live ${inst.segment}`,
            });
            updatedCount++;

            // Hydrate 5m chart candles from the same Yahoo response if available
            const c5 = extractYahooCandles(q.result, inst);
            if (c5.length >= 12) {
              candlesByKey.set(`${inst.symbol}_5m`, c5);
              liveHistoryFetched.add(`${inst.symbol}_5m`);
            }
          })
        );
      }

      updateFeedStatusPill();
      renderAllViews();
      if (showToastOnComplete) {
        const liveTotal = Array.from(market.values()).filter((x) => x.isLive).length;
        showToast(`Live feeds synced: ${liveTotal}/${market.size} instruments LIVE`, liveTotal > 0 ? "success" : "warn");
      }
    } finally {
      isPollingYahoo = false;
    }
  }

  function extractYahooCandles(result, inst) {
    if (!result || !Array.isArray(result.timestamp) || !result.indicators || !result.indicators.quote || !result.indicators.quote[0]) {
      return [];
    }
    const ts = result.timestamp;
    const q = result.indicators.quote[0];
    const conv = inst.mcxConv;
    const out = [];
    for (let i = 0; i < ts.length; i++) {
      let o = q.open && q.open[i];
      let h = q.high && q.high[i];
      let l = q.low && q.low[i];
      let c = q.close && q.close[i];
      const v = (q.volume && q.volume[i]) || 1000;
      if (!Number.isFinite(o) || !Number.isFinite(h) || !Number.isFinite(l) || !Number.isFinite(c)) continue;
      if (conv) {
        o = convertMcxToInr(o, conv);
        h = convertMcxToInr(h, conv);
        l = convertMcxToInr(l, conv);
        c = convertMcxToInr(c, conv);
      }
      out.push({
        time: ts[i] * 1000,
        open: roundTick(o, inst.tickSize),
        high: roundTick(h, inst.tickSize),
        low: roundTick(l, inst.tickSize),
        close: roundTick(c, inst.tickSize),
        volume: Math.max(1, v),
      });
    }
    return out.slice(-180);
  }

  // Fetch real chart candlesticks when user picks a symbol/timeframe
  async function loadRealCandlesForChart(symbol, tf) {
    const key = `${symbol}_${tf}`;
    if (liveHistoryFetched.has(key)) return;
    const inst = market.get(symbol);
    if (!inst) return;

    try {
      if (inst.binanceSym) {
        const bTf = BINANCE_TF[tf] || "5m";
        const res = await fetch(`https://data-api.binance.vision/api/v3/klines?symbol=${inst.binanceSym}&interval=${bTf}&limit=150`, { cache: "no-store" });
        if (res.ok) {
          const rows = await res.json();
          if (Array.isArray(rows) && rows.length > 10) {
            const mult = 1 + (inst.basisPct || 0);
            const candles = rows.map((r) => ({
              time: Number(r[0]),
              open: roundTick(parseFloat(r[1]) * mult, inst.tickSize),
              high: roundTick(parseFloat(r[2]) * mult, inst.tickSize),
              low: roundTick(parseFloat(r[3]) * mult, inst.tickSize),
              close: roundTick(parseFloat(r[4]) * mult, inst.tickSize),
              volume: Math.max(1, parseFloat(r[5]) || 1),
            }));
            candlesByKey.set(key, candles);
            liveHistoryFetched.add(key);
            if (currentTab === "chart" && currentChartSymbol === symbol && currentTf === tf) {
              drawChart();
            }
          }
        }
      } else {
        const targetYahoo = inst.yahooSym || (inst.underlyingSym && market.get(inst.underlyingSym)?.yahooSym);
        if (!targetYahoo) return;
        const cfg = YAHOO_TF[tf] || YAHOO_TF["5m"];
        const url = `https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(targetYahoo)}?interval=${cfg.interval}&range=${cfg.range}`;
        const json = await fetchJsonWithCorsRelay(url, 8000);
        const result = json && json.chart && json.chart.result && json.chart.result[0];
        if (!result) return;
        if (inst.yahooSym) {
          const candles = extractYahooCandles(result, inst);
          if (candles.length > 10) {
            candlesByKey.set(key, candles);
            liveHistoryFetched.add(key);
            if (currentTab === "chart" && currentChartSymbol === symbol && currentTf === tf) drawChart();
          }
        }
      }
    } catch (_) {}
  }

  // Fallback micro-tick ONLY for instruments not yet live (keeps UI responsive if offline)
  function runFallbackSimTicks() {
    market.forEach((inst) => {
      if (inst.isLive) return;
      if (inst.underlyingSym) return; // derived from spot
      const drift = (Math.random() - 0.498) * inst.volPct * inst.ltp;
      const next = Math.max(inst.tickSize * 5, inst.ltp + drift);
      applyPriceUpdate(inst.symbol, next, { isLive: false });
    });
  }

  function updateFeedStatusPill() {
    const el = document.getElementById("feedStatusPill");
    if (!el) return;
    const all = Array.from(market.values());
    const liveCount = all.filter((i) => i.isLive).length;
    const brokerTag = state.broker && state.broker.provider !== "PUBLIC_FREE" && state.broker.accessToken
      ? ` • ${state.broker.provider}`
      : "";
    if (liveCount > 0) {
      el.textContent = `LIVE ${liveCount}/${all.length} quotes • Binance + NSE/BSE/MCX${brokerTag}`;
      el.classList.add("live-ok");
    } else {
      el.textContent = `Connecting live public feeds… (fallback active)`;
      el.classList.remove("live-ok");
    }
  }

  // =========================================================================
  // 6. CANDLE ENGINE & TECHNICAL INDICATORS
  // =========================================================================
  function getCandles(symbol, tf = "5m") {
    const key = `${symbol}_${tf}`;
    if (!candlesByKey.has(key)) {
      const inst = market.get(symbol);
      candlesByKey.set(key, generateSeededCandles(inst, TF_SECONDS[tf] || 300, 140));
    }
    return candlesByKey.get(key);
  }

  function generateSeededCandles(inst, stepSec, count) {
    const base = inst ? inst.ltp : 100;
    const vol = inst ? inst.volPct * 2.2 : 0.002;
    const nowSec = Math.floor(Date.now() / 1000 / stepSec) * stepSec;
    const arr = [];
    let price = base * (0.985 + Math.random() * 0.02);

    for (let i = count - 1; i >= 0; i--) {
      const t = (nowSec - i * stepSec) * 1000;
      const open = price;
      const pullToLtp = i < 15 ? (base - open) * 0.16 : 0;
      const move = (Math.random() - 0.49) * vol * open + pullToLtp;
      const close = roundTick(Math.max(inst.tickSize * 5, open + move), inst.tickSize);
      const wickHi = Math.abs(Math.random() * vol * 0.7 * open);
      const wickLo = Math.abs(Math.random() * vol * 0.7 * open);
      const high = roundTick(Math.max(open, close) + wickHi, inst.tickSize);
      const low = roundTick(Math.max(inst.tickSize, Math.min(open, close) - wickLo), inst.tickSize);
      arr.push({
        time: t,
        open: roundTick(open, inst.tickSize),
        high,
        low,
        close,
        volume: Math.round(800 + Math.random() * 6500),
      });
      price = close;
    }
    if (arr.length > 0 && inst) {
      arr[arr.length - 1].close = inst.ltp;
      arr[arr.length - 1].high = Math.max(arr[arr.length - 1].high, inst.ltp);
      arr[arr.length - 1].low = Math.min(arr[arr.length - 1].low, inst.ltp);
    }
    return arr;
  }

  function updateCandleWithTick(candles, price, stepSec) {
    const nowBucket = Math.floor(Date.now() / 1000 / stepSec) * stepSec * 1000;
    const last = candles[candles.length - 1];
    if (last && last.time === nowBucket) {
      last.close = price;
      if (price > last.high) last.high = price;
      if (price < last.low) last.low = price;
      last.volume += 45;
    } else {
      const open = last ? last.close : price;
      candles.push({
        time: nowBucket,
        open,
        high: Math.max(open, price),
        low: Math.min(open, price),
        close: price,
        volume: 120,
      });
      if (candles.length > 220) candles.shift();
    }
  }

  function calcEmaSeries(candles, period) {
    const out = new Array(candles.length).fill(null);
    if (candles.length < period) return out;
    const k = 2 / (period + 1);
    let ema = candles.slice(0, period).reduce((s, c) => s + c.close, 0) / period;
    out[period - 1] = ema;
    for (let i = period; i < candles.length; i++) {
      ema = candles[i].close * k + ema * (1 - k);
      out[i] = ema;
    }
    return out;
  }

  function calcVwapSeries(candles) {
    let cumPv = 0;
    let cumVol = 0;
    return candles.map((c) => {
      const typical = (c.high + c.low + c.close) / 3;
      const vol = Math.max(1, c.volume || 1);
      cumPv += typical * vol;
      cumVol += vol;
      return cumPv / cumVol;
    });
  }

  function calcRsi(candles, period = 14) {
    if (candles.length <= period) return 50;
    let gains = 0;
    let losses = 0;
    for (let i = candles.length - period; i < candles.length; i++) {
      const diff = candles[i].close - candles[i - 1].close;
      if (diff >= 0) gains += diff;
      else losses -= diff;
    }
    if (losses === 0) return 100;
    const rs = (gains / period) / (losses / period);
    return 100 - 100 / (1 + rs);
  }

  function calcAtr(candles, period = 14) {
    if (candles.length <= period) return 1;
    let sum = 0;
    for (let i = candles.length - period; i < candles.length; i++) {
      const c = candles[i];
      const prev = candles[i - 1];
      const tr = Math.max(c.high - c.low, Math.abs(c.high - prev.close), Math.abs(c.low - prev.close));
      sum += tr;
    }
    return sum / period;
  }

  // =========================================================================
  // 7. SIGNAL ENGINE (MULTI-STRATEGY + BTST/STBT + AUTO-REMOVAL & ACCURACY)
  // =========================================================================
  function scanSignals(notifyNew = false) {
    const existingByKey = new Map(activeSignals.map((s) => [`${s.symbol}_${s.strategy}`, s]));
    const nextSignals = [];

    market.forEach((inst) => {
      const candles = getCandles(inst.symbol, "5m");
      if (candles.length < 55) return;

      const ema9 = calcEmaSeries(candles, 9);
      const ema21 = calcEmaSeries(candles, 21);
      const ema50 = calcEmaSeries(candles, 50);
      const vwap = calcVwapSeries(candles);
      const rsi = calcRsi(candles, 14);
      const atr = Math.max(calcAtr(candles, 14), inst.ltp * 0.0025);

      const last = candles.length - 1;
      const e9 = ema9[last];
      const e21 = ema21[last];
      const e50 = ema50[last];
      const vw = vwap[last];
      const price = inst.ltp;

      // 1) Intraday Momentum / Trend Signal
      let dir = null;
      let score = 50;
      const reasons = [];
      if (e9 > e21 && price > vw && rsi >= 51 && rsi <= 74) {
        dir = "LONG";
        score = 68 + (e21 > e50 ? 14 : 5) + (rsi > 56 && rsi < 68 ? 8 : 0);
        reasons.push("EMA 9 > EMA 21", "Price > VWAP", `RSI ${rsi.toFixed(1)}`);
        if (e21 > e50) reasons.push("HTF Bullish Align");
      } else if (e9 < e21 && price < vw && rsi <= 49 && rsi >= 26) {
        dir = "SHORT";
        score = 68 + (e21 < e50 ? 14 : 5) + (rsi < 44 && rsi > 32 ? 8 : 0);
        reasons.push("EMA 9 < EMA 21", "Price < VWAP", `RSI ${rsi.toFixed(1)}`);
        if (e21 < e50) reasons.push("HTF Bearish Align");
      }

      if (dir) {
        const key = `${inst.symbol}_TREND`;
        const prev = existingByKey.get(key);
        if (prev && prev.direction === dir) {
          nextSignals.push(prev);
        } else {
          const slDist = atr * 1.15;
          const sig = {
            id: genId("sig"),
            symbol: inst.symbol,
            name: inst.name,
            segment: inst.segment,
            category: inst.category,
            lotSize: inst.lotSize,
            strategy: "EMA+VWAP Trend",
            isBtst: false,
            direction: dir,
            confidence: Math.min(95, Math.round(score)),
            entry: roundTick(price, inst.tickSize),
            sl: roundTick(dir === "LONG" ? price - slDist : price + slDist, inst.tickSize),
            t1: roundTick(dir === "LONG" ? price + slDist * 1.4 : price - slDist * 1.4, inst.tickSize),
            t2: roundTick(dir === "LONG" ? price + slDist * 2.5 : price - slDist * 2.5, inst.tickSize),
            reasons,
            createdAt: Date.now(),
            expiresAt: Date.now() + 90 * 60 * 1000, // 90m validity
          };
          nextSignals.push(sig);
          if (notifyNew) {
            sendBrowserNotification(`Signal: ${sig.direction} ${sig.symbol}`, `Entry ${fmtPrice(sig.entry, inst.currency)} | T1 ${fmtPrice(sig.t1, inst.currency)} | SL ${fmtPrice(sig.sl, inst.currency)}`, true);
          }
        }
      }

      // 2) BTST / STBT Overnight Strategy (Multi-Factor Close-Near-Extreme + Trend + Volume Momentum)
      const dayRange = Math.max(inst.high - inst.low, inst.tickSize * 5);
      const posInRange = (price - inst.low) / dayRange;
      const dayChgPct = inst.prevClose > 0 ? ((price - inst.prevClose) / inst.prevClose) * 100 : 0;

      let btstDir = null;
      const btstReasons = [];
      if (posInRange >= 0.72 && e9 > e21 && price > e50 && rsi >= 54 && rsi <= 76 && dayChgPct >= 0.15) {
        btstDir = "LONG";
        btstReasons.push("BTST Overnight Setup", `Close Near High (${Math.round(posInRange * 100)}%)`, "Above EMA 21 & 50", `Day +${dayChgPct.toFixed(2)}%`);
      } else if (posInRange <= 0.28 && e9 < e21 && price < e50 && rsi <= 46 && rsi >= 24 && dayChgPct <= -0.15) {
        btstDir = "SHORT";
        btstReasons.push("STBT Overnight Setup", `Close Near Low (${Math.round(posInRange * 100)}%)`, "Below EMA 21 & 50", `Day ${dayChgPct.toFixed(2)}%`);
      }

      if (btstDir) {
        const key = `${inst.symbol}_BTST_STBT`;
        const prev = existingByKey.get(key);
        if (prev && prev.direction === btstDir) {
          nextSignals.push(prev);
        } else {
          const swingRisk = atr * 1.65;
          const btstSig = {
            id: genId("btst"),
            symbol: inst.symbol,
            name: inst.name,
            segment: inst.segment,
            category: inst.category,
            lotSize: inst.lotSize,
            strategy: btstDir === "LONG" ? "BTST" : "STBT",
            isBtst: true,
            direction: btstDir,
            confidence: Math.min(96, Math.round(74 + Math.abs(dayChgPct) * 6)),
            entry: roundTick(price, inst.tickSize),
            sl: roundTick(btstDir === "LONG" ? price - swingRisk : price + swingRisk, inst.tickSize),
            t1: roundTick(btstDir === "LONG" ? price + swingRisk * 1.5 : price - swingRisk * 1.5, inst.tickSize),
            t2: roundTick(btstDir === "LONG" ? price + swingRisk * 2.8 : price - swingRisk * 2.8, inst.tickSize),
            reasons: btstReasons,
            createdAt: Date.now(),
            expiresAt: Date.now() + 18 * 3600 * 1000, // Overnight validity
          };
          nextSignals.push(btstSig);
          if (notifyNew) {
            sendBrowserNotification(`${btstSig.strategy} Signal: ${btstSig.symbol}`, `Overnight ${btstSig.direction} @ ${fmtPrice(btstSig.entry, inst.currency)} | T1 ${fmtPrice(btstSig.t1, inst.currency)}`, true);
          }
        }
      }
    });

    activeSignals = nextSignals.sort((a, b) => b.confidence - a.confidence);
    evaluateSignalOutcomes();
  }

  // Live monitor: Remove active signals immediately upon SL / Target hit or Expiry, and track accuracy
  function evaluateSignalOutcomes() {
    const now = Date.now();
    let changed = false;

    const remaining = [];
    for (const sig of activeSignals) {
      const inst = market.get(sig.symbol);
      if (!inst) {
        remaining.push(sig);
        continue;
      }
      const ltp = inst.ltp;
      let outcome = null;

      if (sig.direction === "LONG") {
        if (ltp <= sig.sl) outcome = "HIT_SL";
        else if (ltp >= sig.t2) outcome = "HIT_T2";
        else if (ltp >= sig.t1) outcome = "HIT_T1";
      } else {
        if (ltp >= sig.sl) outcome = "HIT_SL";
        else if (ltp <= sig.t2) outcome = "HIT_T2";
        else if (ltp <= sig.t1) outcome = "HIT_T1";
      }

      if (!outcome && now >= sig.expiresAt) {
        outcome = "EXPIRED";
      }

      if (outcome) {
        const record = {
          id: sig.id,
          symbol: sig.symbol,
          category: sig.category,
          strategy: sig.strategy,
          isBtst: sig.isBtst,
          direction: sig.direction,
          entry: sig.entry,
          sl: sig.sl,
          t1: sig.t1,
          t2: sig.t2,
          closePrice: ltp,
          outcome,
          closedAt: now,
        };
        state.signalRecords.unshift(record);
        if (state.signalRecords.length > 250) state.signalRecords.pop();
        changed = true;

        const label = outcome === "HIT_T2" ? "Target 2 Hit 🎯" : outcome === "HIT_T1" ? "Target 1 Hit ✅" : outcome === "HIT_SL" ? "Stop Loss Hit 🛑" : "Signal Expired ⏱";
        showToast(`${sig.symbol} (${sig.strategy}): ${label} — removed from Active`, outcome === "HIT_SL" ? "error" : outcome === "EXPIRED" ? "warn" : "success");
        sendBrowserNotification(`${sig.symbol} — ${label}`, `${sig.strategy} ${sig.direction} closed @ ${fmtPrice(ltp, inst.currency)}`, true);
      } else {
        remaining.push(sig);
      }
    }

    // Upgrade recent HIT_T1 records to HIT_T2 if price extends to Target 2 within 2 hours
    for (const rec of state.signalRecords) {
      if (rec.outcome !== "HIT_T1" || now - rec.closedAt > 2 * 3600 * 1000) continue;
      const inst = market.get(rec.symbol);
      if (!inst) continue;
      const hitT2 = rec.direction === "LONG" ? inst.ltp >= rec.t2 : inst.ltp <= rec.t2;
      if (hitT2) {
        rec.outcome = "HIT_T2";
        rec.closePrice = inst.ltp;
        rec.closedAt = now;
        changed = true;
      }
    }

    activeSignals = remaining;
    if (changed) saveState();
  }

  function computeSignalAccuracy() {
    const recs = state.signalRecords;
    const t1 = recs.filter((r) => r.outcome === "HIT_T1").length;
    const t2 = recs.filter((r) => r.outcome === "HIT_T2").length;
    const sl = recs.filter((r) => r.outcome === "HIT_SL").length;
    const expired = recs.filter((r) => r.outcome === "EXPIRED").length;
    const decided = t1 + t2 + sl;
    const accuracy = decided > 0 ? ((t1 + t2) / decided) * 100 : 0;
    return { total: recs.length, t1, t2, sl, expired, accuracy };
  }

  // =========================================================================
  // 8. EXECUTION ENGINE: ORDERS, MODIFY PENDING, AVERAGE POSITION, CAPITAL
  // =========================================================================
  function getUsedMargin() {
    return state.positions.reduce((sum, p) => {
      const inst = market.get(p.symbol);
      return sum + calculateMarginRequired(inst || { currency: "₹", type: "EQUITY" }, p.qty, p.avgEntry, p.product);
    }, 0);
  }

  function getTotalUnrealisedPnl() {
    return state.positions.reduce((sum, p) => sum + getPositionPnl(p), 0);
  }

  function getPositionPnl(pos) {
    const inst = market.get(pos.symbol);
    if (!inst) return 0;
    const diff = pos.side === "BUY" ? (inst.ltp - pos.avgEntry) : (pos.avgEntry - inst.ltp);
    return diff * pos.qty * getInrMultiplier(inst);
  }

  function placeOrder({ symbol, side, product, orderType, lots, limitPrice, triggerPrice, stopLoss, target }) {
    const inst = market.get(symbol);
    if (!inst) return { ok: false, error: "Instrument not found" };
    const cleanLots = Math.max(1, Math.floor(Number(lots) || 1));
    const qty = cleanLots * inst.lotSize;
    const refPrice = orderType === "MARKET" ? inst.ltp : (Number(limitPrice) || Number(triggerPrice) || inst.ltp);

    const neededMargin = calculateMarginRequired(inst, qty, refPrice, product);
    const availMargin = state.capital + getTotalUnrealisedPnl() - getUsedMargin();
    if (neededMargin > availMargin) {
      return { ok: false, error: `Insufficient margin. Need ${fmtInr(neededMargin)}, available ${fmtInr(availMargin)}.` };
    }

    const order = {
      id: genId("ord"),
      symbol,
      side,
      product,
      orderType,
      lots: cleanLots,
      lotSize: inst.lotSize,
      qty,
      limitPrice: orderType === "LIMIT" || orderType === "SL_LIMIT" ? Number(limitPrice) || inst.ltp : null,
      triggerPrice: orderType === "SL_LIMIT" || orderType === "SL_MARKET" ? Number(triggerPrice) || inst.ltp : null,
      stopLoss: Number(stopLoss) > 0 ? Number(stopLoss) : null,
      target: Number(target) > 0 ? Number(target) : null,
      status: "PENDING",
      fillPrice: null,
      charges: null,
      createdAt: Date.now(),
    };

    state.orders.unshift(order);
    if (orderType === "MARKET") {
      executeOrder(order, inst.ltp);
    } else {
      saveState();
      showToast(`Pending ${side} ${orderType} placed for ${symbol} (${fmtQtyLots(qty, inst.lotSize)})`, "info");
      sendBrowserNotification(`Order Placed: ${symbol}`, `${side} ${orderType} ${fmtQtyLots(qty, inst.lotSize)}`);
    }
    renderAllViews();
    return { ok: true };
  }

  function executeOrder(order, fillPrice) {
    const inst = market.get(order.symbol);
    if (!inst) return;
    const price = roundTick(fillPrice, inst.tickSize);
    const charges = calculateOrderCharges(inst, order.side, order.qty, price, order.product);

    order.status = "FILLED";
    order.fillPrice = price;
    order.filledAt = Date.now();
    order.charges = charges;

    // Deduct entry charges from capital immediately
    state.capital -= charges.total;

    // Check if an existing position exists for same symbol & product
    const existingIdx = state.positions.findIndex((p) => p.symbol === order.symbol && p.product === order.product);
    if (existingIdx >= 0) {
      const pos = state.positions[existingIdx];
      if (pos.side === order.side) {
        // Scale-in / Average automatically
        const totalQty = pos.qty + order.qty;
        pos.avgEntry = roundTick(((pos.avgEntry * pos.qty) + (price * order.qty)) / totalQty, inst.tickSize);
        pos.qty = totalQty;
        pos.lots = Math.round(totalQty / pos.lotSize);
        pos.entryCharges = sumCharges(pos.entryCharges, charges);
        if (order.stopLoss) pos.stopLoss = order.stopLoss;
        if (order.target) pos.target = order.target;
      } else {
        // Opposite side closes or reduces position
        closePositionInternal(pos, Math.min(pos.qty, order.qty), price, "Order Fill", charges);
      }
    } else {
      state.positions.unshift({
        id: genId("pos"),
        symbol: order.symbol,
        side: order.side,
        product: order.product,
        qty: order.qty,
        lotSize: inst.lotSize,
        lots: order.lots,
        avgEntry: price,
        stopLoss: order.stopLoss,
        target: order.target,
        entryCharges: charges,
        openedAt: Date.now(),
      });
    }

    saveState();
    const msg = `${order.side} ${order.symbol} filled @ ${fmtPrice(price, inst.currency)} • Qty ${fmtQtyLots(order.qty, inst.lotSize)} • Charges ${fmtInr(charges.total)}`;
    showToast(msg, "success");
    sendBrowserNotification(`Trade Executed: ${order.symbol}`, msg);
  }

  // Feature 2: Modify Pending Order
  function modifyPendingOrder(orderId, { lots, limitPrice, triggerPrice, stopLoss, target }) {
    const order = state.orders.find((o) => o.id === orderId && o.status === "PENDING");
    if (!order) return { ok: false, error: "Pending order not found." };
    const inst = market.get(order.symbol);
    if (!inst) return { ok: false, error: "Instrument not found." };

    const cleanLots = Math.max(1, Math.floor(Number(lots) || 1));
    order.lots = cleanLots;
    order.qty = cleanLots * inst.lotSize;
    if (order.orderType === "LIMIT" || order.orderType === "SL_LIMIT") {
      order.limitPrice = roundTick(Number(limitPrice) || order.limitPrice || inst.ltp, inst.tickSize);
    }
    if (order.orderType === "SL_LIMIT" || order.orderType === "SL_MARKET") {
      order.triggerPrice = roundTick(Number(triggerPrice) || order.triggerPrice || inst.ltp, inst.tickSize);
    }
    order.stopLoss = Number(stopLoss) > 0 ? roundTick(Number(stopLoss), inst.tickSize) : null;
    order.target = Number(target) > 0 ? roundTick(Number(target), inst.tickSize) : null;

    saveState();
    const msg = `Modified pending ${order.symbol}: ${fmtQtyLots(order.qty, inst.lotSize)}`;
    showToast(msg, "success");
    sendBrowserNotification(`Order Modified: ${order.symbol}`, msg);
    renderAllViews();
    return { ok: true };
  }

  // Feature 3: Average Running Trade (Scale-in extra lots)
  function averageRunningPosition(posId, addLots, execPrice) {
    const pos = state.positions.find((p) => p.id === posId);
    if (!pos) return { ok: false, error: "Position not found." };
    const inst = market.get(pos.symbol);
    if (!inst) return { ok: false, error: "Instrument not found." };

    const extraLots = Math.max(1, Math.floor(Number(addLots) || 1));
    const extraQty = extraLots * pos.lotSize;
    const price = roundTick(Number(execPrice) || inst.ltp, inst.tickSize);

    const extraMargin = calculateMarginRequired(inst, extraQty, price, pos.product);
    const availMargin = state.capital + getTotalUnrealisedPnl() - getUsedMargin();
    if (extraMargin > availMargin) {
      return { ok: false, error: `Insufficient margin to average. Need ${fmtInr(extraMargin)}, available ${fmtInr(availMargin)}.` };
    }

    const charges = calculateOrderCharges(inst, pos.side, extraQty, price, pos.product);
    const oldQty = pos.qty;
    const newQty = oldQty + extraQty;
    const newAvg = roundTick(((pos.avgEntry * oldQty) + (price * extraQty)) / newQty, inst.tickSize);

    pos.qty = newQty;
    pos.lots = Math.round(newQty / pos.lotSize);
    pos.avgEntry = newAvg;
    pos.entryCharges = sumCharges(pos.entryCharges, charges);
    state.capital -= charges.total;

    // Log averaging execution in Orders ledger
    state.orders.unshift({
      id: genId("avg"),
      symbol: pos.symbol,
      side: pos.side,
      product: pos.product,
      orderType: "AVERAGE",
      lots: extraLots,
      lotSize: pos.lotSize,
      qty: extraQty,
      limitPrice: null,
      triggerPrice: null,
      stopLoss: pos.stopLoss,
      target: pos.target,
      status: "FILLED",
      fillPrice: price,
      charges,
      createdAt: Date.now(),
      filledAt: Date.now(),
    });

    // Sync any pending opposite SL/Target orders for this symbol to new total quantity
    state.orders.forEach((o) => {
      if (o.status === "PENDING" && o.symbol === pos.symbol && o.side !== pos.side && o.product === pos.product) {
        o.qty = newQty;
        o.lots = pos.lots;
      }
    });

    saveState();
    const msg = `Averaged ${pos.symbol}: +${extraLots} lot(s) @ ${fmtPrice(price, inst.currency)} → New Avg ${fmtPrice(newAvg, inst.currency)} (${fmtQtyLots(newQty, pos.lotSize)})`;
    showToast(msg, "success");
    sendBrowserNotification(`Position Averaged: ${pos.symbol}`, msg);
    renderAllViews();
    return { ok: true };
  }

  function closePositionInternal(pos, closeQty, exitPrice, reason = "Market Exit", precomputedExitCharges = null) {
    const inst = market.get(pos.symbol);
    if (!inst) return;
    const exitSide = pos.side === "BUY" ? "SELL" : "BUY";
    const exitCharges = precomputedExitCharges || calculateOrderCharges(inst, exitSide, closeQty, exitPrice, pos.product);
    const ratio = closeQty / pos.qty;
    const entryChargesShare = {
      brokerage: (pos.entryCharges?.brokerage || 0) * ratio,
      stt: (pos.entryCharges?.stt || 0) * ratio,
      exchangeTxn: (pos.entryCharges?.exchangeTxn || 0) * ratio,
      sebi: (pos.entryCharges?.sebi || 0) * ratio,
      stampDuty: (pos.entryCharges?.stampDuty || 0) * ratio,
      gst: (pos.entryCharges?.gst || 0) * ratio,
      total: (pos.entryCharges?.total || 0) * ratio,
    };
    const totalCharges = sumCharges(entryChargesShare, exitCharges);

    const rawDiff = pos.side === "BUY" ? (exitPrice - pos.avgEntry) : (pos.avgEntry - exitPrice);
    const grossPnl = rawDiff * closeQty * getInrMultiplier(inst);
    const netPnl = grossPnl - totalCharges.total;

    // Entry charges were deducted on open; now add grossPnl and deduct exitCharges
    state.capital += grossPnl - exitCharges.total;
    state.realisedPnl += netPnl;

    state.journal.unshift({
      id: genId("jrn"),
      symbol: pos.symbol,
      side: pos.side,
      product: pos.product,
      qty: closeQty,
      lotSize: pos.lotSize,
      lots: Math.max(1, Math.round(closeQty / pos.lotSize)),
      entryPrice: pos.avgEntry,
      exitPrice,
      grossPnl,
      charges: totalCharges,
      netPnl,
      reason,
      note: "",
      closedAt: Date.now(),
    });

    if (!precomputedExitCharges) {
      state.orders.unshift({
        id: genId("exit"),
        symbol: pos.symbol,
        side: exitSide,
        product: pos.product,
        orderType: "MARKET",
        lots: Math.max(1, Math.round(closeQty / pos.lotSize)),
        lotSize: pos.lotSize,
        qty: closeQty,
        status: "FILLED",
        fillPrice: exitPrice,
        charges: exitCharges,
        createdAt: Date.now(),
        filledAt: Date.now(),
      });
    }

    if (closeQty >= pos.qty) {
      state.positions = state.positions.filter((p) => p.id !== pos.id);
    } else {
      pos.qty -= closeQty;
      pos.lots = Math.max(1, Math.round(pos.qty / pos.lotSize));
    }

    saveState();
    const msg = `Closed ${pos.symbol} (${reason}): Net ${fmtInr(netPnl, true)} (Charges ${fmtInr(totalCharges.total)})`;
    showToast(msg, netPnl >= 0 ? "success" : "warn");
    sendBrowserNotification(`Position Closed: ${pos.symbol}`, msg);
  }

  function evaluatePendingOrdersAndRisk() {
    let changed = false;

    // 1. Trigger pending Limit / SL orders
    for (const order of state.orders) {
      if (order.status !== "PENDING") continue;
      const inst = market.get(order.symbol);
      if (!inst) continue;
      const ltp = inst.ltp;

      if (order.orderType === "LIMIT" && order.limitPrice != null) {
        const hit = order.side === "BUY" ? ltp <= order.limitPrice : ltp >= order.limitPrice;
        if (hit) {
          executeOrder(order, order.limitPrice);
          changed = true;
        }
      } else if ((order.orderType === "SL_LIMIT" || order.orderType === "SL_MARKET") && order.triggerPrice != null) {
        const triggered = order.side === "BUY" ? ltp >= order.triggerPrice : ltp <= order.triggerPrice;
        if (triggered) {
          const fill = order.orderType === "SL_LIMIT" && order.limitPrice != null ? order.limitPrice : ltp;
          executeOrder(order, fill);
          changed = true;
        }
      }
    }

    // 2. Check Position SL, Target, and 15:15 / 15:40 India Intraday Auto Square-Off
    const snapshot = [...state.positions];
    for (const pos of snapshot) {
      const inst = market.get(pos.symbol);
      if (!inst) continue;
      const ltp = inst.ltp;

      if (pos.stopLoss != null) {
        const slHit = pos.side === "BUY" ? ltp <= pos.stopLoss : ltp >= pos.stopLoss;
        if (slHit) {
          closePositionInternal(pos, pos.qty, ltp, "Stop Loss Hit");
          changed = true;
          continue;
        }
      }

      if (pos.target != null) {
        const tgtHit = pos.side === "BUY" ? ltp >= pos.target : ltp <= pos.target;
        if (tgtHit) {
          closePositionInternal(pos, pos.qty, ltp, "Target Hit");
          changed = true;
          continue;
        }
      }

      if (pos.product === "INTRADAY" && isIntradaySquareOffDue(inst)) {
        const info = getIntradaySquareOffInfo(inst);
        closePositionInternal(pos, pos.qty, ltp, `Auto Square-Off (${info.label})`);
        changed = true;
      }
    }

    if (changed) renderAllViews();
  }

  // Feature 11: Capital Management (Add / Reduce / Reset + Persistent Ledger)
  function applyCapitalAction(mode, amount, note) {
    const amt = Number(amount);
    if (!Number.isFinite(amt) || amt <= 0) {
      return { ok: false, error: "Enter a valid positive amount." };
    }
    const cleanNote = (note || "").trim();

    if (mode === "ADD") {
      state.capital += amt;
      state.capitalLedger.unshift({
        id: genId("cap"),
        type: "ADD",
        amount: amt,
        balanceAfter: state.capital,
        note: cleanNote || "Added Paper Capital",
        time: Date.now(),
      });
      showToast(`Added ${fmtInr(amt)} to paper capital`, "success");
    } else if (mode === "REDUCE") {
      const avail = Math.max(0, state.capital - getUsedMargin());
      if (amt > avail) {
        return { ok: false, error: `Cannot reduce more than free margin (${fmtInr(avail)}).` };
      }
      state.capital -= amt;
      state.capitalLedger.unshift({
        id: genId("cap"),
        type: "REDUCE",
        amount: amt,
        balanceAfter: state.capital,
        note: cleanNote || "Reduced Paper Capital",
        time: Date.now(),
      });
      showToast(`Reduced ${fmtInr(amt)} from paper capital`, "warn");
    } else if (mode === "RESET") {
      state.capital = amt;
      state.realisedPnl = 0;
      state.positions = [];
      state.orders = [];
      state.capitalLedger.unshift({
        id: genId("cap"),
        type: "RESET",
        amount: amt,
        balanceAfter: state.capital,
        note: cleanNote || "Account Reset",
        time: Date.now(),
      });
      showToast(`Paper account reset to ${fmtInr(amt)}`, "info");
    }

    saveState();
    renderAllViews();
    return { ok: true };
  }

  // =========================================================================
  // 9. TRADINGVIEW-STYLE INTERACTIVE CANVAS CHART
  // =========================================================================
  function drawChart() {
    const canvas = document.getElementById("candleCanvas");
    if (!canvas) return;
    const container = canvas.parentElement;
    const dpr = window.devicePixelRatio || 1;
    const w = container.clientWidth;
    const h = container.clientHeight;
    if (w <= 0 || h <= 0) return;

    if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(h * dpr);
    }

    const ctx = canvas.getContext("2d");
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    const inst = market.get(currentChartSymbol);
    if (!inst) return;
    const allCandles = getCandles(currentChartSymbol, currentTf);
    const total = allCandles.length;
    if (total === 0) return;

    const visibleCount = Math.max(15, Math.min(total, chartZoomCount));
    const maxOffset = Math.max(0, total - visibleCount);
    chartPanOffset = Math.max(0, Math.min(maxOffset, chartPanOffset));

    const endIdx = total - chartPanOffset;
    const startIdx = Math.max(0, endIdx - visibleCount);
    const slice = allCandles.slice(startIdx, endIdx);

    const ema9Full = calcEmaSeries(allCandles, 9);
    const ema21Full = calcEmaSeries(allCandles, 21);
    const ema50Full = calcEmaSeries(allCandles, 50);
    const vwapFull = calcVwapSeries(allCandles);

    const isLight = document.documentElement.getAttribute("data-theme") === "light";
    const bgCol = isLight ? "#ffffff" : "#0f172a";
    const gridCol = isLight ? "rgba(15,23,42,0.08)" : "rgba(148,163,184,0.12)";
    const textCol = isLight ? "#475569" : "#94a3b8";

    ctx.fillStyle = bgCol;
    ctx.fillRect(0, 0, w, h);

    const padLeft = 10;
    const padRight = 76;
    const padTop = 36;
    const padBottom = 30;
    const plotW = Math.max(10, w - padLeft - padRight);
    const plotH = Math.max(10, h - padTop - padBottom);

    let minP = Infinity;
    let maxP = -Infinity;
    slice.forEach((c) => {
      if (c.low < minP) minP = c.low;
      if (c.high > maxP) maxP = c.high;
    });
    const span = Math.max(inst.tickSize * 10, maxP - minP);
    minP -= span * 0.08;
    maxP += span * 0.08;

    const yFor = (p) => padTop + ((maxP - p) / (maxP - minP)) * plotH;
    const pFor = (y) => maxP - ((y - padTop) / plotH) * (maxP - minP);
    const stepX = plotW / slice.length;
    const xForLocal = (i) => padLeft + (i + 0.5) * stepX;

    lastChartGeom = { padLeft, padRight, padTop, padBottom, plotW, plotH, startIdx, endIdx, slice, stepX, minP, maxP, yFor, pFor };

    // Horizontal price grid + right price axis labels
    ctx.strokeStyle = gridCol;
    ctx.lineWidth = 1;
    ctx.font = "11px 'JetBrains Mono', monospace";
    ctx.fillStyle = textCol;
    ctx.textAlign = "left";

    const hLevels = 6;
    for (let i = 0; i <= hLevels; i++) {
      const y = padTop + (plotH * i) / hLevels;
      const p = pFor(y);
      ctx.beginPath();
      ctx.moveTo(padLeft, y);
      ctx.lineTo(padLeft + plotW, y);
      ctx.stroke();
      ctx.fillText(p.toFixed(p >= 1000 ? 1 : 2), padLeft + plotW + 6, y + 4);
    }

    // Vertical IST time grid + bottom IST time axis
    const timeStep = Math.max(1, Math.floor(slice.length / 7));
    ctx.textAlign = "center";
    for (let i = 0; i < slice.length; i += timeStep) {
      const x = xForLocal(i);
      ctx.beginPath();
      ctx.moveTo(x, padTop);
      ctx.lineTo(x, padTop + plotH);
      ctx.stroke();
      ctx.fillText(fmtIstShortAxis(slice[i].time, currentTf), x, h - 10);
    }

    // Bottom axis border
    ctx.strokeStyle = isLight ? "#cbd5e1" : "#334155";
    ctx.beginPath();
    ctx.moveTo(padLeft, padTop + plotH);
    ctx.lineTo(padLeft + plotW, padTop + plotH);
    ctx.stroke();

    // Draw candles
    const bodyW = Math.max(2, Math.min(14, stepX * 0.68));
    slice.forEach((c, idx) => {
      const x = xForLocal(idx);
      const isUp = c.close >= c.open;
      const col = isUp ? "#22c55e" : "#ef4444";
      ctx.strokeStyle = col;
      ctx.fillStyle = col;

      // Wick
      ctx.lineWidth = 1.2;
      ctx.beginPath();
      ctx.moveTo(x, yFor(c.high));
      ctx.lineTo(x, yFor(c.low));
      ctx.stroke();

      // Body
      const topY = yFor(Math.max(c.open, c.close));
      const botY = yFor(Math.min(c.open, c.close));
      ctx.fillRect(x - bodyW / 2, topY, bodyW, Math.max(1.5, botY - topY));
    });

    // Draw EMA & VWAP overlays
    const showEma = document.getElementById("toggleEma")?.checked;
    const showVwap = document.getElementById("toggleVwap")?.checked;

    const drawSeries = (fullArr, color, width = 1.5) => {
      ctx.strokeStyle = color;
      ctx.lineWidth = width;
      ctx.beginPath();
      let started = false;
      for (let i = 0; i < slice.length; i++) {
        const val = fullArr[startIdx + i];
        if (val == null) continue;
        const x = xForLocal(i);
        const y = yFor(val);
        if (!started) { ctx.moveTo(x, y); started = true; }
        else ctx.lineTo(x, y);
      }
      ctx.stroke();
    };

    if (showEma) {
      drawSeries(ema9Full, "#38bdf8", 1.4);
      drawSeries(ema21Full, "#f59e0b", 1.4);
      drawSeries(ema50Full, "#a855f7", 1.4);
    }
    if (showVwap) {
      drawSeries(vwapFull, "#ec4899", 1.5);
    }

    // Live LTP dashed horizontal line + right tag
    const ltpY = Math.max(padTop, Math.min(padTop + plotH, yFor(inst.ltp)));
    const isUpDay = inst.ltp >= inst.prevClose;
    const ltpCol = isUpDay ? "#16a34a" : "#dc2626";
    ctx.save();
    ctx.setLineDash([4, 4]);
    ctx.strokeStyle = ltpCol;
    ctx.lineWidth = 1.2;
    ctx.beginPath();
    ctx.moveTo(padLeft, ltpY);
    ctx.lineTo(padLeft + plotW, ltpY);
    ctx.stroke();
    ctx.restore();

    ctx.fillStyle = ltpCol;
    ctx.fillRect(padLeft + plotW + 2, ltpY - 10, padRight - 4, 20);
    ctx.fillStyle = "#ffffff";
    ctx.textAlign = "left";
    ctx.font = "bold 11px 'JetBrains Mono', monospace";
    ctx.fillText(inst.ltp.toFixed(inst.ltp >= 1000 ? 1 : 2), padLeft + plotW + 6, ltpY + 4);

    // TradingView Time & Price Range Measure Overlay
    if (measurePointA && measurePointB) {
      const localA = measurePointA.barIdx - startIdx;
      const localB = measurePointB.barIdx - startIdx;
      const x1 = xForLocal(localA);
      const x2 = xForLocal(localB);
      const y1 = yFor(measurePointA.price);
      const y2 = yFor(measurePointB.price);

      const priceDelta = measurePointB.price - measurePointA.price;
      const pctDelta = measurePointA.price > 0 ? (priceDelta / measurePointA.price) * 100 : 0;
      const barDiff = Math.abs(measurePointB.barIdx - measurePointA.barIdx);
      const minsDiff = Math.round(Math.abs(measurePointB.time - measurePointA.time) / 60000);
      const durText = minsDiff >= 60 ? `${Math.floor(minsDiff / 60)}h ${minsDiff % 60}m` : `${minsDiff}m`;
      const isGain = priceDelta >= 0;

      ctx.fillStyle = isGain ? "rgba(34, 197, 94, 0.18)" : "rgba(239, 68, 68, 0.18)";
      ctx.strokeStyle = isGain ? "#22c55e" : "#ef4444";
      ctx.lineWidth = 1.5;
      const rx = Math.min(x1, x2);
      const ry = Math.min(y1, y2);
      const rw = Math.max(4, Math.abs(x2 - x1));
      const rh = Math.max(4, Math.abs(y2 - y1));
      ctx.fillRect(rx, ry, rw, rh);
      ctx.strokeRect(rx, ry, rw, rh);

      // Measurement callout box
      const label1 = `${priceDelta >= 0 ? "+" : ""}${priceDelta.toFixed(2)} (${pctDelta >= 0 ? "+" : ""}${pctDelta.toFixed(2)}%)`;
      const label2 = `${barDiff} bars • ${durText}`;
      const boxW = 178;
      const boxH = 40;
      const bx = Math.max(padLeft + 4, Math.min(padLeft + plotW - boxW - 4, (x1 + x2) / 2 - boxW / 2));
      const by = Math.max(padTop + 4, ry - boxH - 6);

      ctx.fillStyle = isGain ? "#15803d" : "#b91c1c";
      ctx.fillRect(bx, by, boxW, boxH);
      ctx.fillStyle = "#ffffff";
      ctx.textAlign = "center";
      ctx.font = "bold 11.5px 'JetBrains Mono', monospace";
      ctx.fillText(label1, bx + boxW / 2, by + 16);
      ctx.font = "10.5px 'JetBrains Mono', monospace";
      ctx.fillText(label2, bx + boxW / 2, by + 32);
    } else if (measurePointA && chartMeasureMode) {
      const localA = measurePointA.barIdx - startIdx;
      const x1 = xForLocal(localA);
      const y1 = yFor(measurePointA.price);
      ctx.fillStyle = "#3b82f6";
      ctx.beginPath();
      ctx.arc(x1, y1, 5, 0, Math.PI * 2);
      ctx.fill();
    }

    // Hover crosshair & dynamic OHLC legend
    if (hoverCrosshair && hoverCrosshair.x >= padLeft && hoverCrosshair.x <= padLeft + plotW && hoverCrosshair.y >= padTop && hoverCrosshair.y <= padTop + plotH) {
      ctx.save();
      ctx.setLineDash([3, 3]);
      ctx.strokeStyle = isLight ? "rgba(15,23,42,0.35)" : "rgba(226,232,240,0.35)";
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(hoverCrosshair.x, padTop);
      ctx.lineTo(hoverCrosshair.x, padTop + plotH);
      ctx.moveTo(padLeft, hoverCrosshair.y);
      ctx.lineTo(padLeft + plotW, hoverCrosshair.y);
      ctx.stroke();
      ctx.restore();

      const localBar = Math.max(0, Math.min(slice.length - 1, Math.floor((hoverCrosshair.x - padLeft) / stepX)));
      const hc = slice[localBar];
      if (hc) {
        document.getElementById("chartLegend").textContent =
          `${fmtIstDateTime(hc.time)} IST  |  O ${hc.open.toFixed(2)}  H ${hc.high.toFixed(2)}  L ${hc.low.toFixed(2)}  C ${hc.close.toFixed(2)}`;
      }
    } else {
      const lastC = slice[slice.length - 1];
      if (lastC) {
        document.getElementById("chartLegend").textContent =
          `${currentChartSymbol} (${currentTf})  |  O ${lastC.open.toFixed(2)}  H ${lastC.high.toFixed(2)}  L ${lastC.low.toFixed(2)}  C ${lastC.close.toFixed(2)}  |  ${chartMeasureMode ? "Click 2 points to measure Range" : "Scroll to zoom • Drag to pan"}`;
      }
    }

    // Header LTP & Indicator Strip
    const chg = inst.ltp - inst.prevClose;
    const chgPct = inst.prevClose > 0 ? (chg / inst.prevClose) * 100 : 0;
    document.getElementById("chartLtp").textContent = fmtPrice(inst.ltp, inst.currency);
    const chgEl = document.getElementById("chartChange");
    chgEl.textContent = `${chg >= 0 ? "+" : ""}${chg.toFixed(2)} (${chgPct >= 0 ? "+" : ""}${chgPct.toFixed(2)}%)`;
    chgEl.className = `chart-change mono ${chg >= 0 ? "text-pos" : "text-neg"}`;

    const feedTag = document.getElementById("chartFeedTag");
    feedTag.textContent = inst.isLive ? "LIVE" : "SIM";
    feedTag.className = `feed-tag ${inst.isLive ? "live" : "sim"}`;
    document.getElementById("chartLotChip").textContent = `LOT ${inst.lotSize}`;
    document.getElementById("chartSessionHint").textContent = getIntradaySquareOffInfo(inst).window;

    const rsiVal = calcRsi(allCandles, 14);
    const atrVal = calcAtr(allCandles, 14);
    const e9Last = ema9Full[allCandles.length - 1];
    const e21Last = ema21Full[allCandles.length - 1];
    const vwLast = vwapFull[allCandles.length - 1];

    document.getElementById("chartIndicatorStrip").innerHTML = `
      <div class="ind-card"><div class="ind-card-label">RSI (14)</div><div class="ind-card-val mono ${rsiVal > 60 ? "text-pos" : rsiVal < 40 ? "text-neg" : ""}">${rsiVal.toFixed(1)}</div></div>
      <div class="ind-card"><div class="ind-card-label">ATR (14)</div><div class="ind-card-val mono">${atrVal.toFixed(2)}</div></div>
      <div class="ind-card"><div class="ind-card-label">EMA 9 / 21</div><div class="ind-card-val mono">${e9Last ? e9Last.toFixed(2) : "--"} / ${e21Last ? e21Last.toFixed(2) : "--"}</div></div>
      <div class="ind-card"><div class="ind-card-label">VWAP</div><div class="ind-card-val mono">${vwLast ? vwLast.toFixed(2) : "--"}</div></div>
      <div class="ind-card"><div class="ind-card-label">Data Source</div><div class="ind-card-val">${esc(inst.source)}</div></div>
    `;
  }

  // =========================================================================
  // 10. UI RENDERERS
  // =========================================================================
  function renderTopMetrics() {
    const usedMargin = getUsedMargin();
    const unrealised = getTotalUnrealisedPnl();
    const availMargin = state.capital + unrealised - usedMargin;

    document.getElementById("metricCapital").textContent = fmtInr(state.capital);
    document.getElementById("metricAvailMargin").textContent = fmtInr(availMargin);

    const unrEl = document.getElementById("metricUnrealised");
    unrEl.textContent = fmtInr(unrealised, true);
    unrEl.className = `metric-value mono ${unrealised >= 0 ? "text-pos" : "text-neg"}`;

    const realEl = document.getElementById("metricRealised");
    realEl.textContent = fmtInr(state.realisedPnl, true);
    realEl.className = `metric-value mono ${state.realisedPnl >= 0 ? "text-pos" : "text-neg"}`;

    document.getElementById("signalsBadge").textContent = String(activeSignals.length);
    document.getElementById("positionsBadge").textContent = String(state.positions.length);
    document.getElementById("ordersBadge").textContent = String(state.orders.filter((o) => o.status === "PENDING").length);
    document.getElementById("activeSigCount").textContent = String(activeSignals.length);
    document.getElementById("historySigCount").textContent = String(state.signalRecords.length);
    document.getElementById("capitalLedgerCount").textContent = String(state.capitalLedger.length);

    // Notification button state
    const notifBtn = document.getElementById("toggleNotifBtn");
    notifBtn.textContent = state.notificationsEnabled ? "🔔 Alerts On" : "🔕 Alerts";
    document.getElementById("signalNotifToggle").checked = Boolean(state.notificationsEnabled && state.signalNotificationsEnabled);
  }

  function renderWatchlist() {
    const tbody = document.getElementById("watchlistBody");
    const q = currentSearch.trim().toLowerCase();
    const rows = Array.from(market.values()).filter((inst) => {
      if (currentSegmentFilter !== "ALL" && inst.segment !== currentSegmentFilter) return false;
      if (q && !inst.symbol.toLowerCase().includes(q) && !inst.name.toLowerCase().includes(q)) return false;
      return true;
    });

    if (rows.length === 0) {
      tbody.innerHTML = `<tr><td colspan="7" class="empty-state">No instruments match your filter.</td></tr>`;
      return;
    }

    tbody.innerHTML = rows.map((inst) => {
      const chg = inst.ltp - inst.prevClose;
      const pct = inst.prevClose > 0 ? (chg / inst.prevClose) * 100 : 0;
      const cls = chg >= 0 ? "text-pos" : "text-neg";
      return `
        <tr>
          <td>
            <div class="sym-cell">
              <div class="sym-title-row">
                <span class="sym-name">${esc(inst.symbol)}</span>
                <span class="seg-badge ${esc(inst.segment)}">${esc(inst.segment)}</span>
                <span class="lot-chip">LOT ${inst.lotSize}</span>
              </div>
              <span class="sym-sub">${esc(inst.name)}</span>
            </div>
          </td>
          <td><span class="feed-tag ${inst.isLive ? "live" : "sim"}">${inst.isLive ? "● LIVE" : "SIM"}</span></td>
          <td><span class="seg-badge">${esc(inst.type)}</span></td>
          <td class="num mono"><strong>${fmtPrice(inst.ltp, inst.currency)}</strong></td>
          <td class="num mono ${cls}">${chg >= 0 ? "+" : ""}${chg.toFixed(2)} (${pct >= 0 ? "+" : ""}${pct.toFixed(2)}%)</td>
          <td class="num mono sym-sub">${fmtPrice(inst.high, inst.currency)} / ${fmtPrice(inst.low, inst.currency)}</td>
          <td class="num">
            <div class="action-cell">
              <button class="btn btn-buy btn-xs" data-action="trade" data-symbol="${esc(inst.symbol)}" data-side="BUY">Buy</button>
              <button class="btn btn-sell btn-xs" data-action="trade" data-symbol="${esc(inst.symbol)}" data-side="SELL">Sell</button>
              <button class="btn btn-ghost btn-xs" data-action="chart" data-symbol="${esc(inst.symbol)}">Chart</button>
            </div>
          </td>
        </tr>
      `;
    }).join("");
  }

  function renderSignals() {
    // 1. Accuracy strip
    const stats = computeSignalAccuracy();
    document.getElementById("signalAccuracyStrip").innerHTML = `
      <div class="ind-card"><div class="ind-card-label">Signal Accuracy</div><div class="ind-card-val mono ${stats.accuracy >= 55 ? "text-pos" : "text-warn"}">${stats.accuracy.toFixed(1)}%</div></div>
      <div class="ind-card"><div class="ind-card-label">Target 1 Hits</div><div class="ind-card-val mono text-pos">${stats.t1}</div></div>
      <div class="ind-card"><div class="ind-card-label">Target 2 Hits</div><div class="ind-card-val mono text-pos">${stats.t2}</div></div>
      <div class="ind-card"><div class="ind-card-label">Stop Loss Hits</div><div class="ind-card-val mono text-neg">${stats.sl}</div></div>
      <div class="ind-card"><div class="ind-card-label">Expired Signals</div><div class="ind-card-val mono text-warn">${stats.expired}</div></div>
      <div class="ind-card"><div class="ind-card-label">Tracked Total</div><div class="ind-card-val mono">${stats.total}</div></div>
    `;

    // 2. Active signals grid
    const filtered = activeSignals.filter((s) => {
      if (currentSignalDir !== "ALL" && s.direction !== currentSignalDir) return false;
      if (currentSignalCategory === "BTST_STBT") return s.isBtst;
      if (currentSignalCategory !== "ALL" && s.category !== currentSignalCategory) return false;
      return true;
    });

    const grid = document.getElementById("signalsGrid");
    if (filtered.length === 0) {
      grid.innerHTML = `<div class="empty-state">No active signals in this category right now. Signals auto-archive on SL/Target hit to reduce noise. Click Rescan to refresh.</div>`;
    } else {
      grid.innerHTML = filtered.map((s) => {
        const inst = market.get(s.symbol);
        const cur = inst ? inst.currency : "₹";
        const ltp = inst ? inst.ltp : s.entry;
        return `
          <div class="signal-card ${s.isBtst ? "btst-card" : ""}">
            <div class="signal-head">
              <div>
                <div class="sym-title-row">
                  <span class="sym-name">${esc(s.symbol)}</span>
                  <span class="seg-badge ${esc(s.segment)}">${esc(s.segment)}</span>
                  <span class="lot-chip">LOT ${s.lotSize}</span>
                </div>
                <div class="sym-sub">${esc(s.strategy)} • Live LTP: <span class="mono">${fmtPrice(ltp, cur)}</span></div>
              </div>
              <span class="status-badge status-${esc(s.direction)}">${esc(s.direction)} • ${s.confidence}%</span>
            </div>

            <div class="signal-levels mono">
              <div><div class="sig-lvl-label">Entry</div><div class="sig-lvl-val">${fmtPrice(s.entry, cur)}</div></div>
              <div><div class="sig-lvl-label">SL</div><div class="sig-lvl-val text-neg">${fmtPrice(s.sl, cur)}</div></div>
              <div><div class="sig-lvl-label">Target 1</div><div class="sig-lvl-val text-pos">${fmtPrice(s.t1, cur)}</div></div>
              <div><div class="sig-lvl-label">Target 2</div><div class="sig-lvl-val text-pos">${fmtPrice(s.t2, cur)}</div></div>
            </div>

            <div class="signal-reasons">
              ${s.reasons.map((r) => `<span class="reason-tag">${esc(r)}</span>`).join("")}
            </div>

            <div class="signal-footer">
              <span class="sym-sub">Valid till ${fmtIstTime(s.expiresAt)} IST</span>
              <div class="action-cell">
                <button class="btn btn-ghost btn-xs" data-action="chart" data-symbol="${esc(s.symbol)}">Chart</button>
                <button class="btn ${s.direction === "LONG" ? "btn-buy" : "btn-sell"} btn-xs" data-action="signal-trade" data-sig-id="${esc(s.id)}">
                  ${s.isBtst ? `Trade ${s.strategy}` : `Paper ${s.direction}`}
                </button>
              </div>
            </div>
          </div>
        `;
      }).join("");
    }

    // 3. Signal Performance & History table
    const histBody = document.getElementById("signalHistoryBody");
    if (state.signalRecords.length === 0) {
      histBody.innerHTML = `<tr><td colspan="8" class="empty-state">No completed signal records yet. Once an active signal hits Target 1, Target 2, Stop Loss, or Expires, it will appear here automatically.</td></tr>`;
    } else {
      histBody.innerHTML = state.signalRecords.map((r) => {
        const inst = market.get(r.symbol);
        const cur = inst ? inst.currency : "₹";
        const label = r.outcome === "HIT_T2" ? "TARGET 2 HIT" : r.outcome === "HIT_T1" ? "TARGET 1 HIT" : r.outcome === "HIT_SL" ? "SL HIT" : "EXPIRED";
        return `
          <tr>
            <td><strong>${esc(r.symbol)}</strong> <span class="seg-badge">${esc(r.category)}</span></td>
            <td>${esc(r.strategy)}</td>
            <td><span class="status-badge status-${esc(r.direction)}">${esc(r.direction)}</span></td>
            <td class="num mono">${fmtPrice(r.entry, cur)}</td>
            <td class="num mono sym-sub">${fmtPrice(r.sl, cur)} / ${fmtPrice(r.t1, cur)} / ${fmtPrice(r.t2, cur)}</td>
            <td class="num mono">${fmtPrice(r.closePrice, cur)}</td>
            <td><span class="status-badge status-${esc(r.outcome)}">${label}</span></td>
            <td class="mono sym-sub">${fmtIstDateTime(r.closedAt)}</td>
          </tr>
        `;
      }).join("");
    }
  }

  function renderPositions() {
    const tbody = document.getElementById("positionsBody");
    if (state.positions.length === 0) {
      tbody.innerHTML = `<tr><td colspan="9" class="empty-state">No open positions. Place an order from Watchlist, Chart, or Signals.</td></tr>`;
      return;
    }

    tbody.innerHTML = state.positions.map((pos) => {
      const inst = market.get(pos.symbol) || { currency: "₹", ltp: pos.avgEntry, segment: "NSE", type: "EQUITY" };
      const pnl = getPositionPnl(pos);
      const cls = pnl >= 0 ? "text-pos" : "text-neg";
      const sqInfo = getIntradaySquareOffInfo(inst);
      return `
        <tr>
          <td>
            <div class="sym-cell">
              <div class="sym-title-row">
                <span class="sym-name">${esc(pos.symbol)}</span>
                <span class="lot-chip">LOT ${pos.lotSize}</span>
              </div>
              <span class="sym-sub">${pos.product === "INTRADAY" ? `Auto close: ${sqInfo.label}` : "Carry Forward / Overnight"}</span>
            </div>
          </td>
          <td><span class="seg-badge">${pos.product === "INTRADAY" ? "INTRADAY" : "CARRY FWD"}</span></td>
          <td><span class="status-badge status-${esc(pos.side)}">${esc(pos.side)}</span></td>
          <td class="num mono"><strong>${fmtQtyLots(pos.qty, pos.lotSize)}</strong></td>
          <td class="num mono">${fmtPrice(pos.avgEntry, inst.currency)}</td>
          <td class="num mono">${fmtPrice(inst.ltp, inst.currency)}</td>
          <td class="num mono sym-sub">
            SL: ${pos.stopLoss ? fmtPrice(pos.stopLoss, inst.currency) : "--"}<br/>
            Tgt: ${pos.target ? fmtPrice(pos.target, inst.currency) : "--"}
          </td>
          <td class="num mono ${cls}"><strong>${fmtInr(pnl, true)}</strong></td>
          <td class="num">
            <div class="action-cell">
              <button class="btn btn-primary btn-xs" data-action="average-pos" data-pos-id="${esc(pos.id)}">＋ Average</button>
              <button class="btn btn-ghost btn-xs" data-action="edit-sl" data-pos-id="${esc(pos.id)}">SL/Tgt</button>
              ${pos.product === "INTRADAY" ? `<button class="btn btn-ghost btn-xs" data-action="convert-cf" data-pos-id="${esc(pos.id)}">→ CF</button>` : ""}
              <button class="btn btn-danger btn-xs" data-action="exit-pos" data-pos-id="${esc(pos.id)}">Exit</button>
            </div>
          </td>
        </tr>
      `;
    }).join("");
  }

  function renderOrders() {
    const tbody = document.getElementById("ordersBody");
    const rows = state.orders.filter((o) => currentOrderFilter === "ALL" || o.status === currentOrderFilter);

    if (rows.length === 0) {
      tbody.innerHTML = `<tr><td colspan="8" class="empty-state">No orders found in this filter.</td></tr>`;
      return;
    }

    tbody.innerHTML = rows.map((o) => {
      const inst = market.get(o.symbol) || { currency: "₹", lotSize: o.lotSize || 1 };
      const priceText = o.fillPrice != null
        ? fmtPrice(o.fillPrice, inst.currency)
        : (o.limitPrice ? `Lmt ${fmtPrice(o.limitPrice, inst.currency)}` : "") +
          (o.triggerPrice ? ` Trig ${fmtPrice(o.triggerPrice, inst.currency)}` : "");
      const chargeText = o.charges
        ? `${fmtInr(o.charges.total)}<div class="sym-sub">Brok ${fmtInr(o.charges.brokerage)} • Tax ${fmtInr(o.charges.total - o.charges.brokerage)}</div>`
        : "--";

      return `
        <tr>
          <td class="mono sym-sub">${fmtIstTime(o.createdAt)}</td>
          <td><strong>${esc(o.symbol)}</strong> <span class="seg-badge">${o.product === "INTRADAY" ? "MIS" : "NRML"}</span></td>
          <td><span class="status-badge status-${esc(o.side)}">${esc(o.side)}</span> <span class="sym-sub">${esc(o.orderType)}</span></td>
          <td class="num mono">${fmtQtyLots(o.qty, o.lotSize || inst.lotSize)}</td>
          <td class="num mono">${priceText || "MARKET"}</td>
          <td class="num mono">${chargeText}</td>
          <td><span class="status-badge status-${esc(o.status)}">${esc(o.status)}</span></td>
          <td class="num">
            ${o.status === "PENDING" ? `
              <div class="action-cell">
                <button class="btn btn-primary btn-xs" data-action="modify-order" data-order-id="${esc(o.id)}">Edit</button>
                <button class="btn btn-danger btn-xs" data-action="cancel-order" data-order-id="${esc(o.id)}">Cancel</button>
              </div>
            ` : `<span class="sym-sub">—</span>`}
          </td>
        </tr>
      `;
    }).join("");
  }

  function renderJournal() {
    const trades = state.journal;
    const totalGross = trades.reduce((s, t) => s + (t.grossPnl || 0), 0);
    const totalCharges = trades.reduce((s, t) => s + (t.charges?.total || 0), 0);
    const totalNet = trades.reduce((s, t) => s + (t.netPnl || 0), 0);
    const wins = trades.filter((t) => t.netPnl > 0);
    const losses = trades.filter((t) => t.netPnl < 0);
    const winRate = trades.length > 0 ? (wins.length / trades.length) * 100 : 0;
    const grossWins = wins.reduce((s, t) => s + t.netPnl, 0);
    const grossLosses = Math.abs(losses.reduce((s, t) => s + t.netPnl, 0));
    const pf = grossLosses > 0 ? (grossWins / grossLosses).toFixed(2) : (grossWins > 0 ? "∞" : "0.00");

    document.getElementById("journalSummary").innerHTML = `
      <div class="ind-card"><div class="ind-card-label">Net Realised P&amp;L</div><div class="ind-card-val mono ${totalNet >= 0 ? "text-pos" : "text-neg"}">${fmtInr(totalNet, true)}</div></div>
      <div class="ind-card"><div class="ind-card-label">Gross P&amp;L</div><div class="ind-card-val mono ${totalGross >= 0 ? "text-pos" : "text-neg"}">${fmtInr(totalGross, true)}</div></div>
      <div class="ind-card"><div class="ind-card-label">Total Charges &amp; Taxes</div><div class="ind-card-val mono text-warn">${fmtInr(totalCharges)}</div></div>
      <div class="ind-card"><div class="ind-card-label">Win Rate</div><div class="ind-card-val mono">${winRate.toFixed(1)}% (${wins.length}/${trades.length})</div></div>
      <div class="ind-card"><div class="ind-card-label">Profit Factor</div><div class="ind-card-val mono">${pf}</div></div>
    `;

    const tbody = document.getElementById("journalBody");
    if (trades.length === 0) {
      tbody.innerHTML = `<tr><td colspan="8" class="empty-state">No closed trades recorded yet.</td></tr>`;
    } else {
      tbody.innerHTML = trades.map((t) => {
        const inst = market.get(t.symbol) || { currency: "₹", lotSize: t.lotSize || 1 };
        const c = t.charges || { brokerage: 0, stt: 0, exchangeTxn: 0, sebi: 0, stampDuty: 0, gst: 0, total: 0 };
        return `
          <tr>
            <td class="mono sym-sub">${fmtIstDateTime(t.closedAt)}<br/>${esc(t.reason || "")}</td>
            <td><strong>${esc(t.symbol)}</strong></td>
            <td><span class="status-badge status-${esc(t.side)}">${esc(t.side)}</span><div class="sym-sub mono">${fmtQtyLots(t.qty, t.lotSize || inst.lotSize)}</div></td>
            <td class="num mono">${fmtPrice(t.entryPrice, inst.currency)} → ${fmtPrice(t.exitPrice, inst.currency)}</td>
            <td class="num mono ${t.grossPnl >= 0 ? "text-pos" : "text-neg"}">${fmtInr(t.grossPnl, true)}</td>
            <td class="num mono">
              <details class="charges-details">
                <summary>${fmtInr(c.total)} ▾</summary>
                <div class="charges-breakdown-box">
                  <div><span>Brokerage</span><span>${fmtInr(c.brokerage)}</span></div>
                  <div><span>STT / CTT</span><span>${fmtInr(c.stt)}</span></div>
                  <div><span>Exchange Txn</span><span>${fmtInr(c.exchangeTxn)}</span></div>
                  <div><span>SEBI Fees</span><span>${fmtInr(c.sebi)}</span></div>
                  <div><span>Stamp Duty</span><span>${fmtInr(c.stampDuty)}</span></div>
                  <div><span>GST (18%)</span><span>${fmtInr(c.gst)}</span></div>
                </div>
              </details>
            </td>
            <td class="num mono ${t.netPnl >= 0 ? "text-pos" : "text-neg"}"><strong>${fmtInr(t.netPnl, true)}</strong></td>
            <td><input class="note-input" data-note-id="${esc(t.id)}" value="${esc(t.note || "")}" placeholder="Add note..." /></td>
          </tr>
        `;
      }).join("");
    }

    // Capital Ledger
    const capBody = document.getElementById("capitalLedgerBody");
    if (state.capitalLedger.length === 0) {
      capBody.innerHTML = `<tr><td colspan="5" class="empty-state">No capital adjustments yet.</td></tr>`;
    } else {
      capBody.innerHTML = state.capitalLedger.map((entry) => `
        <tr>
          <td class="mono sym-sub">${fmtIstDateTime(entry.time)}</td>
          <td><span class="status-badge status-${esc(entry.type)}">${esc(entry.type)}</span></td>
          <td class="num mono ${entry.type === "ADD" ? "text-pos" : entry.type === "REDUCE" ? "text-neg" : ""}">
            ${entry.type === "ADD" ? "+" : entry.type === "REDUCE" ? "-" : ""}${fmtInr(entry.amount)}
          </td>
          <td class="num mono"><strong>${fmtInr(entry.balanceAfter)}</strong></td>
          <td>${esc(entry.note || "")}</td>
        </tr>
      `).join("");
    }
  }

  function renderAllViews() {
    renderTopMetrics();
    renderWatchlist();
    if (currentTab === "chart") drawChart();
    renderSignals();
    renderPositions();
    renderOrders();
    renderJournal();
  }

  // =========================================================================
  // 11. MODALS: ORDER TICKET, MODIFY PENDING, AVERAGE POS, CAPITAL, BROKER
  // =========================================================================
  function openOrderModal(symbol, side = "BUY", preset = null) {
    const inst = market.get(symbol);
    if (!inst) return;
    orderModalSymbol = symbol;
    orderModalSide = side;

    document.getElementById("orderModalTitle").textContent = `${inst.symbol} — ${inst.name}`;
    document.getElementById("orderModalSub").textContent = `LTP: ${fmtPrice(inst.ltp, inst.currency)} • ${inst.segment} ${inst.type} • Lot Size: ${inst.lotSize}`;
    document.getElementById("orderLotSizeHint").textContent = `1 lot = ${inst.lotSize} qty`;

    const sqInfo = getIntradaySquareOffInfo(inst);
    const prodSelect = document.getElementById("orderProduct");
    prodSelect.options[0].textContent = `Intraday (${sqInfo.label})`;
    prodSelect.value = preset && preset.product ? preset.product : "INTRADAY";

    document.getElementById("orderType").value = "MARKET";
    document.getElementById("orderLots").value = "1";
    document.getElementById("orderLimitPrice").value = inst.ltp.toFixed(2);
    document.getElementById("orderTriggerPrice").value = inst.ltp.toFixed(2);
    document.getElementById("orderStopLoss").value = preset && preset.sl ? preset.sl.toFixed(2) : "";
    document.getElementById("orderTarget").value = preset && preset.t1 ? preset.t1.toFixed(2) : "";

    syncOrderModalUi();
    document.getElementById("orderModalBackdrop").classList.add("open");
  }

  function syncOrderModalUi() {
    const inst = market.get(orderModalSymbol);
    if (!inst) return;

    document.getElementById("sideBuyBtn").classList.toggle("active", orderModalSide === "BUY");
    document.getElementById("sideSellBtn").classList.toggle("active", orderModalSide === "SELL");
    const submitBtn = document.getElementById("submitOrderBtn");
    submitBtn.textContent = orderModalSide === "BUY" ? "Submit Buy Order" : "Submit Sell Order";
    submitBtn.className = `btn ${orderModalSide === "BUY" ? "btn-buy" : "btn-sell"}`;

    const oType = document.getElementById("orderType").value;
    document.getElementById("limitPriceGroup").style.display = (oType === "LIMIT" || oType === "SL_LIMIT") ? "flex" : "none";
    document.getElementById("triggerPriceGroup").style.display = (oType === "SL_LIMIT" || oType === "SL_MARKET") ? "flex" : "none";

    const lots = Math.max(1, Math.floor(Number(document.getElementById("orderLots").value) || 1));
    const qty = lots * inst.lotSize;
    document.getElementById("orderTotalQtyHint").textContent = `Total Qty: ${fmtQtyLots(qty, inst.lotSize)}`;

    const product = document.getElementById("orderProduct").value;
    const price = oType === "MARKET" ? inst.ltp : (Number(document.getElementById("orderLimitPrice").value) || inst.ltp);
    const margin = calculateMarginRequired(inst, qty, price, product);
    const avail = state.capital + getTotalUnrealisedPnl() - getUsedMargin();
    const ch = calculateOrderCharges(inst, orderModalSide, qty, price, product);

    document.getElementById("orderPreview").innerHTML = `
      <div class="preview-row"><span>Quantity &amp; Lot Size</span><strong class="mono">${fmtQtyLots(qty, inst.lotSize)}</strong></div>
      <div class="preview-row"><span>Est. Margin Required</span><strong class="mono ${margin > avail ? "text-neg" : ""}">${fmtInr(margin)}</strong></div>
      <div class="preview-row"><span>Available Margin</span><span class="mono">${fmtInr(avail)}</span></div>
      <div class="preview-divider"></div>
      <div class="preview-row"><span>Brokerage</span><span class="mono">${fmtInr(ch.brokerage)}</span></div>
      <div class="preview-row"><span>STT / CTT + Exchange + SEBI + Stamp</span><span class="mono">${fmtInr(ch.stt + ch.exchangeTxn + ch.sebi + ch.stampDuty)}</span></div>
      <div class="preview-row"><span>GST (18%)</span><span class="mono">${fmtInr(ch.gst)}</span></div>
      <div class="preview-row"><strong>Total Est. Order Charges &amp; Taxes</strong><strong class="mono text-warn">${fmtInr(ch.total)}</strong></div>
    `;
  }

  function openModifyOrderModal(orderId) {
    const order = state.orders.find((o) => o.id === orderId && o.status === "PENDING");
    if (!order) return;
    const inst = market.get(order.symbol);
    modifyOrderId = orderId;

    document.getElementById("modifyOrderTitle").textContent = `Modify ${order.symbol} (${order.side} ${order.orderType})`;
    document.getElementById("modifyOrderSub").textContent = `Live LTP: ${inst ? fmtPrice(inst.ltp, inst.currency) : "--"} • Lot Size: ${order.lotSize}`;
    document.getElementById("modLotHint").textContent = `1 lot = ${order.lotSize} qty`;
    document.getElementById("modOrderLots").value = String(order.lots || 1);
    document.getElementById("modOrderLimit").value = order.limitPrice != null ? order.limitPrice.toFixed(2) : "";
    document.getElementById("modOrderTrigger").value = order.triggerPrice != null ? order.triggerPrice.toFixed(2) : "";
    document.getElementById("modOrderSl").value = order.stopLoss != null ? order.stopLoss.toFixed(2) : "";
    document.getElementById("modOrderTarget").value = order.target != null ? order.target.toFixed(2) : "";

    document.getElementById("modLimitGroup").style.display = (order.orderType === "LIMIT" || order.orderType === "SL_LIMIT") ? "flex" : "none";
    document.getElementById("modTriggerGroup").style.display = (order.orderType === "SL_LIMIT" || order.orderType === "SL_MARKET") ? "flex" : "none";

    updateModifyOrderHint();
    document.getElementById("modifyOrderModalBackdrop").classList.add("open");
  }

  function updateModifyOrderHint() {
    const order = state.orders.find((o) => o.id === modifyOrderId);
    if (!order) return;
    const lots = Math.max(1, Math.floor(Number(document.getElementById("modOrderLots").value) || 1));
    document.getElementById("modTotalQtyHint").textContent = `Total Qty: ${fmtQtyLots(lots * order.lotSize, order.lotSize)}`;
  }

  function openAveragePositionModal(posId) {
    const pos = state.positions.find((p) => p.id === posId);
    if (!pos) return;
    const inst = market.get(pos.symbol);
    if (!inst) return;
    averagePosId = posId;

    document.getElementById("averagePosTitle").textContent = `Average ${pos.symbol} (${pos.side})`;
    document.getElementById("averagePosSub").textContent = `Current: ${fmtQtyLots(pos.qty, pos.lotSize)} @ Avg ${fmtPrice(pos.avgEntry, inst.currency)}`;
    document.getElementById("avgAddLots").value = "1";
    document.getElementById("avgExecPrice").value = inst.ltp.toFixed(2);
    updateAveragePreview();
    document.getElementById("averagePosModalBackdrop").classList.add("open");
  }

  function updateAveragePreview() {
    const pos = state.positions.find((p) => p.id === averagePosId);
    if (!pos) return;
    const inst = market.get(pos.symbol);
    if (!inst) return;

    const addLots = Math.max(1, Math.floor(Number(document.getElementById("avgAddLots").value) || 1));
    const addQty = addLots * pos.lotSize;
    const execPrice = Number(document.getElementById("avgExecPrice").value) || inst.ltp;
    const projectedQty = pos.qty + addQty;
    const projectedAvg = ((pos.avgEntry * pos.qty) + (execPrice * addQty)) / projectedQty;
    const extraMargin = calculateMarginRequired(inst, addQty, execPrice, pos.product);
    const extraCharges = calculateOrderCharges(inst, pos.side, addQty, execPrice, pos.product);

    document.getElementById("avgAddQtyHint").textContent = `Adding: ${fmtQtyLots(addQty, pos.lotSize)}`;
    document.getElementById("averagePreview").innerHTML = `
      <div class="preview-row"><span>Current Position</span><span class="mono">${fmtQtyLots(pos.qty, pos.lotSize)} @ ${fmtPrice(pos.avgEntry, inst.currency)}</span></div>
      <div class="preview-row"><strong>Projected Total Qty</strong><strong class="mono">${fmtQtyLots(projectedQty, pos.lotSize)}</strong></div>
      <div class="preview-row"><strong>Projected Weighted Avg Entry</strong><strong class="mono text-pos">${fmtPrice(projectedAvg, inst.currency)}</strong></div>
      <div class="preview-divider"></div>
      <div class="preview-row"><span>Incremental Margin</span><span class="mono">${fmtInr(extraMargin)}</span></div>
      <div class="preview-row"><span>Est. Order Charges &amp; Taxes</span><span class="mono text-warn">${fmtInr(extraCharges.total)}</span></div>
    `;
  }

  function openFundsModal() {
    fundsMode = "ADD";
    syncFundsModalUi();
    document.getElementById("fundsModalBackdrop").classList.add("open");
  }

  function syncFundsModalUi() {
    document.getElementById("fundModeAdd").classList.toggle("active", fundsMode === "ADD");
    document.getElementById("fundModeReduce").classList.toggle("active", fundsMode === "REDUCE");
    document.getElementById("fundModeReset").classList.toggle("active", fundsMode === "RESET");

    const amt = Number(document.getElementById("fundAmountInput").value) || 0;
    const label = document.getElementById("fundAmountLabel");
    const submit = document.getElementById("submitFundsBtn");
    let projected = state.capital;

    if (fundsMode === "ADD") {
      label.textContent = "Amount to Add (₹)";
      submit.textContent = "Add Paper Funds";
      projected = state.capital + amt;
    } else if (fundsMode === "REDUCE") {
      label.textContent = "Amount to Reduce (₹)";
      submit.textContent = "Reduce Paper Funds";
      projected = Math.max(0, state.capital - amt);
    } else {
      label.textContent = "New Starting Capital (₹)";
      submit.textContent = "Reset Account & Balance";
      projected = amt;
    }

    document.getElementById("fundsPreview").innerHTML = `
      <div class="preview-row"><span>Current Capital</span><span class="mono">${fmtInr(state.capital)}</span></div>
      <div class="preview-row"><strong>Projected Capital After Action</strong><strong class="mono">${fmtInr(projected)}</strong></div>
      ${fundsMode === "RESET" ? `<div class="hint-text text-warn">Reset closes open paper positions/orders and logs a fresh starting entry in your Capital Ledger.</div>` : ""}
    `;
  }

  function openBrokerModal() {
    document.getElementById("brokerSelect").value = state.broker.provider || "PUBLIC_FREE";
    document.getElementById("brokerApiKey").value = state.broker.apiKey || "";
    document.getElementById("brokerAccessToken").value = state.broker.accessToken || "";
    renderBrokerStatusBox();
    document.getElementById("brokerModalBackdrop").classList.add("open");
  }

  function renderBrokerStatusBox() {
    const liveCount = Array.from(market.values()).filter((x) => x.isLive).length;
    const isCustom = state.broker.provider && state.broker.provider !== "PUBLIC_FREE" && state.broker.accessToken;
    document.getElementById("brokerStatusBox").innerHTML = `
      <div class="preview-row"><span>Active Feed Mode</span><strong>${isCustom ? esc(state.broker.provider) + " + Public Live" : "Free Public Live Feeds (Binance + NSE/BSE/MCX)"}</strong></div>
      <div class="preview-row"><span>Live Instruments Active</span><strong class="mono text-pos">${liveCount} / ${market.size}</strong></div>
      <div class="hint-text">Clicking 1-Click Login opens the broker's official OAuth portal with your redirect callback automatically attached.</div>
    `;
  }

  function launchBrokerOAuth(provider) {
    const apiKey = document.getElementById("brokerApiKey").value.trim();
    const redirectUri = encodeURIComponent(window.location.origin + window.location.pathname);
    let url = "";
    if (provider === "ANGEL_ONE") {
      url = `https://smartapi.angelbroking.com/publisher-login?api_key=${encodeURIComponent(apiKey || "YOUR_API_KEY")}`;
    } else if (provider === "KITE") {
      url = `https://kite.zerodha.com/connect/login?v=3&api_key=${encodeURIComponent(apiKey || "YOUR_API_KEY")}`;
    } else if (provider === "UPSTOX") {
      url = `https://api.upstox.com/v2/login/authorization/dialog?response_type=code&client_id=${encodeURIComponent(apiKey || "YOUR_CLIENT_ID")}&redirect_uri=${redirectUri}`;
    }
    if (url) {
      state.broker.provider = provider;
      state.broker.apiKey = apiKey;
      saveState();
      document.getElementById("brokerSelect").value = provider;
      window.open(url, "_blank", "noopener,noreferrer");
      showToast(`Opened ${provider} 1-Click Login in a new tab. Paste or return with your session token to complete.`, "info");
    }
  }

  function checkOAuthCallbackParams() {
    try {
      const params = new URLSearchParams(window.location.search);
      const token = params.get("auth_token") || params.get("request_token") || params.get("code") || params.get("access_token");
      if (token) {
        state.broker.accessToken = token;
        state.broker.connectedAt = Date.now();
        if (state.broker.provider === "PUBLIC_FREE") state.broker.provider = "ANGEL_ONE";
        saveState();
        showToast(`Broker OAuth token captured for ${state.broker.provider}!`, "success");
        window.history.replaceState({}, document.title, window.location.pathname);
      }
    } catch (_) {}
  }

  // =========================================================================
  // 12. EVENT LISTENERS & CHART INTERACTION
  // =========================================================================
  function bindEvents() {
    // Populate chart selector
    const chartSel = document.getElementById("chartSymbolSelect");
    chartSel.innerHTML = Array.from(market.values())
      .map((i) => `<option value="${esc(i.symbol)}">${esc(i.symbol)} (${esc(i.segment)} • Lot ${i.lotSize})</option>`)
      .join("");
    chartSel.value = currentChartSymbol;

    chartSel.addEventListener("change", (e) => {
      currentChartSymbol = e.target.value;
      chartPanOffset = 0;
      measurePointA = null;
      measurePointB = null;
      loadRealCandlesForChart(currentChartSymbol, currentTf);
      drawChart();
    });

    // Main Tabs
    document.querySelectorAll(".nav-tab").forEach((btn) => {
      btn.addEventListener("click", () => {
        currentTab = btn.dataset.tab;
        document.querySelectorAll(".nav-tab").forEach((b) => b.classList.toggle("active", b === btn));
        document.querySelectorAll(".tab-panel").forEach((p) => p.classList.toggle("active", p.id === `panel-${currentTab}`));
        if (currentTab === "chart") {
          loadRealCandlesForChart(currentChartSymbol, currentTf);
          setTimeout(drawChart, 30);
        }
      });
    });

    // Topbar buttons
    document.getElementById("refreshLiveBtn").addEventListener("click", () => {
      pollBinanceRest();
      pollPublicIndiaAndMcxQuotes(true);
    });
    document.getElementById("openBrokerModalBtn").addEventListener("click", openBrokerModal);
    document.getElementById("openFundsModalBtn").addEventListener("click", openFundsModal);
    document.getElementById("manageFundsQuickBtn").addEventListener("click", openFundsModal);
    document.getElementById("journalFundsBtn").addEventListener("click", openFundsModal);

    document.getElementById("toggleThemeBtn").addEventListener("click", () => {
      state.theme = state.theme === "dark" ? "light" : "dark";
      document.documentElement.setAttribute("data-theme", state.theme);
      document.getElementById("toggleThemeBtn").textContent = state.theme === "dark" ? "☀️" : "🌙";
      saveState();
      if (currentTab === "chart") drawChart();
    });

    document.getElementById("toggleNotifBtn").addEventListener("click", async () => {
      if (!state.notificationsEnabled) {
        if ("Notification" in window && Notification.permission === "default") {
          const perm = await Notification.requestPermission();
          if (perm !== "granted") {
            showToast("Browser notification permission denied.", "warn");
            return;
          }
        }
        state.notificationsEnabled = true;
        showToast("Browser Trade & Signal Notifications enabled!", "success");
      } else {
        state.notificationsEnabled = false;
        showToast("Browser notifications paused.", "info");
      }
      saveState();
      renderTopMetrics();
    });

    document.getElementById("signalNotifToggle").addEventListener("change", async (e) => {
      if (e.target.checked) {
        if ("Notification" in window && Notification.permission === "default") {
          await Notification.requestPermission();
        }
        state.notificationsEnabled = true;
        state.signalNotificationsEnabled = true;
        showToast("Signal browser alerts enabled.", "success");
      } else {
        state.signalNotificationsEnabled = false;
      }
      saveState();
      renderTopMetrics();
    });

    // Watchlist filters
    document.getElementById("watchlistSearch").addEventListener("input", (e) => {
      currentSearch = e.target.value;
      renderWatchlist();
    });
    document.querySelectorAll("#segmentFilters .pill").forEach((pill) => {
      pill.addEventListener("click", () => {
        currentSegmentFilter = pill.dataset.seg;
        document.querySelectorAll("#segmentFilters .pill").forEach((p) => p.classList.toggle("active", p === pill));
        renderWatchlist();
      });
    });

    // Chart Timeframe & Tools
    document.querySelectorAll("#tfGroup .tf-btn").forEach((btn) => {
      btn.addEventListener("click", () => {
        currentTf = btn.dataset.tf;
        document.querySelectorAll("#tfGroup .tf-btn").forEach((b) => b.classList.toggle("active", b === btn));
        chartPanOffset = 0;
        measurePointA = null;
        measurePointB = null;
        loadRealCandlesForChart(currentChartSymbol, currentTf);
        drawChart();
      });
    });

    document.getElementById("chartZoomInBtn").addEventListener("click", () => {
      chartZoomCount = Math.max(15, Math.round(chartZoomCount * 0.78));
      drawChart();
    });
    document.getElementById("chartZoomOutBtn").addEventListener("click", () => {
      chartZoomCount = Math.min(180, Math.round(chartZoomCount * 1.28));
      drawChart();
    });
    document.getElementById("chartResetZoomBtn").addEventListener("click", () => {
      chartZoomCount = 72;
      chartPanOffset = 0;
      measurePointA = null;
      measurePointB = null;
      drawChart();
    });
    document.getElementById("chartMeasureBtn").addEventListener("click", (e) => {
      chartMeasureMode = !chartMeasureMode;
      e.currentTarget.classList.toggle("active-tool", chartMeasureMode);
      if (!chartMeasureMode) {
        measurePointA = null;
        measurePointB = null;
      } else {
        showToast("Range Measure active: Click first bar, then second bar on chart.", "info");
      }
      drawChart();
    });
    document.getElementById("chartExpandBtn").addEventListener("click", () => {
      isChartExpanded = !isChartExpanded;
      document.getElementById("chartWrapper").classList.toggle("chart-expanded", isChartExpanded);
      document.getElementById("chartExpandBtn").textContent = isChartExpanded ? "✕ Collapse" : "⛶ Expand";
      setTimeout(drawChart, 40);
    });
    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape" && isChartExpanded) {
        isChartExpanded = false;
        document.getElementById("chartWrapper").classList.remove("chart-expanded");
        document.getElementById("chartExpandBtn").textContent = "⛶ Expand";
        setTimeout(drawChart, 40);
      }
    });

    document.getElementById("toggleEma").addEventListener("change", drawChart);
    document.getElementById("toggleVwap").addEventListener("change", drawChart);
    document.getElementById("chartBuyBtn").addEventListener("click", () => openOrderModal(currentChartSymbol, "BUY"));
    document.getElementById("chartSellBtn").addEventListener("click", () => openOrderModal(currentChartSymbol, "SELL"));

    // Canvas wheel zoom, drag pan, crosshair & range measurement
    const canvas = document.getElementById("candleCanvas");
    canvas.addEventListener("wheel", (e) => {
      e.preventDefault();
      chartZoomCount = e.deltaY < 0
        ? Math.max(15, Math.round(chartZoomCount * 0.88))
        : Math.min(180, Math.round(chartZoomCount * 1.14));
      drawChart();
    }, { passive: false });

    canvas.addEventListener("mousedown", (e) => {
      const rect = canvas.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;

      if (chartMeasureMode && lastChartGeom) {
        const localIdx = Math.max(0, Math.min(lastChartGeom.slice.length - 1, Math.floor((x - lastChartGeom.padLeft) / lastChartGeom.stepX)));
        const bar = lastChartGeom.slice[localIdx];
        const pt = { barIdx: lastChartGeom.startIdx + localIdx, price: lastChartGeom.pFor(y), time: bar ? bar.time : Date.now() };
        if (!measurePointA || (measurePointA && measurePointB)) {
          measurePointA = pt;
          measurePointB = null;
        } else {
          measurePointB = pt;
        }
        drawChart();
        return;
      }

      isDraggingChart = true;
      dragStartX = e.clientX;
      dragStartOffset = chartPanOffset;
    });

    window.addEventListener("mousemove", (e) => {
      const rect = canvas.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      if (isDraggingChart && lastChartGeom) {
        const dx = e.clientX - dragStartX;
        const barsDelta = Math.round(dx / Math.max(4, lastChartGeom.stepX));
        chartPanOffset = Math.max(0, dragStartOffset + barsDelta);
        drawChart();
      } else if (currentTab === "chart" && x >= 0 && x <= rect.width && y >= 0 && y <= rect.height) {
        hoverCrosshair = { x, y };
        drawChart();
      }
    });

    window.addEventListener("mouseup", () => { isDraggingChart = false; });
    canvas.addEventListener("mouseleave", () => { hoverCrosshair = null; if (currentTab === "chart") drawChart(); });
    window.addEventListener("resize", () => { if (currentTab === "chart") drawChart(); });

    // Signals sub-tabs & filters
    document.querySelectorAll("#signalSubTabs .subtab-btn").forEach((btn) => {
      btn.addEventListener("click", () => {
        currentSignalSubTab = btn.dataset.subtab;
        document.querySelectorAll("#signalSubTabs .subtab-btn").forEach((b) => b.classList.toggle("active", b === btn));
        document.getElementById("signalsActivePane").style.display = currentSignalSubTab === "active" ? "block" : "none";
        document.getElementById("signalsHistoryPane").style.display = currentSignalSubTab === "history" ? "block" : "none";
      });
    });

    document.querySelectorAll("#signalCategoryFilter .pill").forEach((pill) => {
      pill.addEventListener("click", () => {
        currentSignalCategory = pill.dataset.cat;
        document.querySelectorAll("#signalCategoryFilter .pill").forEach((p) => p.classList.toggle("active", p === pill));
        renderSignals();
      });
    });

    document.querySelectorAll("#signalDirectionFilter .pill").forEach((pill) => {
      pill.addEventListener("click", () => {
        currentSignalDir = pill.dataset.dir;
        document.querySelectorAll("#signalDirectionFilter .pill").forEach((p) => p.classList.toggle("active", p === pill));
        renderSignals();
      });
    });

    document.getElementById("rescanSignalsBtn").addEventListener("click", () => {
      scanSignals(true);
      renderAllViews();
      showToast(`Signals rescanned (${activeSignals.length} active)`, "info");
    });

    document.getElementById("clearSignalHistoryBtn").addEventListener("click", () => {
      state.signalRecords = [];
      saveState();
      renderAllViews();
      showToast("Signal performance history cleared.", "info");
    });

    // Positions batch actions
    document.getElementById("simSquareOffBtn").addEventListener("click", () => {
      const intraday = state.positions.filter((p) => p.product === "INTRADAY");
      if (intraday.length === 0) {
        showToast("No Intraday positions to square off.", "info");
        return;
      }
      intraday.forEach((p) => {
        const inst = market.get(p.symbol);
        if (inst) closePositionInternal(p, p.qty, inst.ltp, `Intraday Square-Off (${getIntradaySquareOffInfo(inst).label})`);
      });
      renderAllViews();
    });

    document.getElementById("squareOffAllBtn").addEventListener("click", () => {
      if (state.positions.length === 0) return;
      [...state.positions].forEach((p) => {
        const inst = market.get(p.symbol);
        if (inst) closePositionInternal(p, p.qty, inst.ltp, "Square Off All");
      });
      renderAllViews();
    });

    // Orders filter
    document.querySelectorAll("#orderStatusFilter .pill").forEach((pill) => {
      pill.addEventListener("click", () => {
        currentOrderFilter = pill.dataset.status;
        document.querySelectorAll("#orderStatusFilter .pill").forEach((p) => p.classList.toggle("active", p === pill));
        renderOrders();
      });
    });

    // Journal sub-tabs
    document.querySelectorAll("#journalSubTabs .subtab-btn").forEach((btn) => {
      btn.addEventListener("click", () => {
        currentJournalSubTab = btn.dataset.jsub;
        document.querySelectorAll("#journalSubTabs .subtab-btn").forEach((b) => b.classList.toggle("active", b === btn));
        document.getElementById("journalTradesPane").style.display = currentJournalSubTab === "trades" ? "block" : "none";
        document.getElementById("journalCapitalPane").style.display = currentJournalSubTab === "capital" ? "block" : "none";
      });
    });

    // Delegated table & card clicks
    document.addEventListener("click", (e) => {
      const btn = e.target.closest("[data-action]");
      if (!btn) return;
      const action = btn.dataset.action;

      if (action === "trade") {
        openOrderModal(btn.dataset.symbol, btn.dataset.side || "BUY");
      } else if (action === "chart") {
        currentChartSymbol = btn.dataset.symbol;
        document.getElementById("chartSymbolSelect").value = currentChartSymbol;
        document.querySelector('.nav-tab[data-tab="chart"]').click();
      } else if (action === "signal-trade") {
        const sig = activeSignals.find((s) => s.id === btn.dataset.sigId);
        if (sig) {
          openOrderModal(sig.symbol, sig.direction === "LONG" ? "BUY" : "SELL", {
            sl: sig.sl,
            t1: sig.t1,
            product: sig.isBtst ? "CARRY_FORWARD" : "INTRADAY",
          });
        }
      } else if (action === "average-pos") {
        openAveragePositionModal(btn.dataset.posId);
      } else if (action === "edit-sl") {
        const pos = state.positions.find((p) => p.id === btn.dataset.posId);
        if (!pos) return;
        const newSl = window.prompt(`Enter Stop Loss price for ${pos.symbol} (leave blank to clear):`, pos.stopLoss ?? "");
        if (newSl === null) return;
        const newTgt = window.prompt(`Enter Target price for ${pos.symbol} (leave blank to clear):`, pos.target ?? "");
        if (newTgt === null) return;
        pos.stopLoss = Number(newSl) > 0 ? Number(newSl) : null;
        pos.target = Number(newTgt) > 0 ? Number(newTgt) : null;
        saveState();
        renderPositions();
        showToast(`Updated SL/Target for ${pos.symbol}`, "success");
      } else if (action === "convert-cf") {
        const pos = state.positions.find((p) => p.id === btn.dataset.posId);
        if (pos) {
          pos.product = "CARRY_FORWARD";
          saveState();
          renderPositions();
          showToast(`${pos.symbol} converted to Carry Forward / Overnight`, "info");
        }
      } else if (action === "exit-pos") {
        const pos = state.positions.find((p) => p.id === btn.dataset.posId);
        const inst = pos && market.get(pos.symbol);
        if (pos && inst) {
          closePositionInternal(pos, pos.qty, inst.ltp, "Manual Exit");
          renderAllViews();
        }
      } else if (action === "modify-order") {
        openModifyOrderModal(btn.dataset.orderId);
      } else if (action === "cancel-order") {
        const order = state.orders.find((o) => o.id === btn.dataset.orderId && o.status === "PENDING");
        if (order) {
          order.status = "CANCELLED";
          saveState();
          renderAllViews();
          showToast(`Cancelled pending order for ${order.symbol}`, "warn");
        }
      }
    });

    // Journal note editing
    document.getElementById("journalBody").addEventListener("change", (e) => {
      if (e.target.matches(".note-input")) {
        const rec = state.journal.find((j) => j.id === e.target.dataset.noteId);
        if (rec) {
          rec.note = e.target.value;
          saveState();
        }
      }
    });

    // Order Modal bindings
    const closeOrderModal = () => document.getElementById("orderModalBackdrop").classList.remove("open");
    document.getElementById("closeOrderModal").addEventListener("click", closeOrderModal);
    document.getElementById("cancelOrderModalBtn").addEventListener("click", closeOrderModal);
    document.getElementById("sideBuyBtn").addEventListener("click", () => { orderModalSide = "BUY"; syncOrderModalUi(); });
    document.getElementById("sideSellBtn").addEventListener("click", () => { orderModalSide = "SELL"; syncOrderModalUi(); });
    ["orderProduct", "orderType", "orderLots", "orderLimitPrice", "orderTriggerPrice"].forEach((id) => {
      document.getElementById(id).addEventListener("input", syncOrderModalUi);
      document.getElementById(id).addEventListener("change", syncOrderModalUi);
    });
    document.getElementById("submitOrderBtn").addEventListener("click", () => {
      const res = placeOrder({
        symbol: orderModalSymbol,
        side: orderModalSide,
        product: document.getElementById("orderProduct").value,
        orderType: document.getElementById("orderType").value,
        lots: document.getElementById("orderLots").value,
        limitPrice: document.getElementById("orderLimitPrice").value,
        triggerPrice: document.getElementById("orderTriggerPrice").value,
        stopLoss: document.getElementById("orderStopLoss").value,
        target: document.getElementById("orderTarget").value,
      });
      if (!res.ok) showToast(res.error, "error");
      else closeOrderModal();
    });

    // Modify Pending Order Modal bindings
    const closeModModal = () => document.getElementById("modifyOrderModalBackdrop").classList.remove("open");
    document.getElementById("closeModifyOrderModal").addEventListener("click", closeModModal);
    document.getElementById("cancelModifyOrderBtn").addEventListener("click", closeModModal);
    document.getElementById("modOrderLots").addEventListener("input", updateModifyOrderHint);
    document.getElementById("saveModifyOrderBtn").addEventListener("click", () => {
      const res = modifyPendingOrder(modifyOrderId, {
        lots: document.getElementById("modOrderLots").value,
        limitPrice: document.getElementById("modOrderLimit").value,
        triggerPrice: document.getElementById("modOrderTrigger").value,
        stopLoss: document.getElementById("modOrderSl").value,
        target: document.getElementById("modOrderTarget").value,
      });
      if (!res.ok) showToast(res.error, "error");
      else closeModModal();
    });

    // Average Position Modal bindings
    const closeAvgModal = () => document.getElementById("averagePosModalBackdrop").classList.remove("open");
    document.getElementById("closeAveragePosModal").addEventListener("click", closeAvgModal);
    document.getElementById("cancelAveragePosBtn").addEventListener("click", closeAvgModal);
    document.getElementById("avgAddLots").addEventListener("input", updateAveragePreview);
    document.getElementById("avgExecPrice").addEventListener("input", updateAveragePreview);
    document.getElementById("confirmAveragePosBtn").addEventListener("click", () => {
      const res = averageRunningPosition(
        averagePosId,
        document.getElementById("avgAddLots").value,
        document.getElementById("avgExecPrice").value
      );
      if (!res.ok) showToast(res.error, "error");
      else closeAvgModal();
    });

    // Capital / Funds Modal bindings
    const closeFundsModal = () => document.getElementById("fundsModalBackdrop").classList.remove("open");
    document.getElementById("closeFundsModal").addEventListener("click", closeFundsModal);
    document.getElementById("cancelFundsModalBtn").addEventListener("click", closeFundsModal);
    ["fundModeAdd", "fundModeReduce", "fundModeReset"].forEach((id) => {
      document.getElementById(id).addEventListener("click", (e) => {
        fundsMode = e.currentTarget.dataset.fundMode;
        syncFundsModalUi();
      });
    });
    document.querySelectorAll("#quickFundAmounts .pill").forEach((p) => {
      p.addEventListener("click", () => {
        document.getElementById("fundAmountInput").value = p.dataset.amt;
        syncFundsModalUi();
      });
    });
    document.getElementById("fundAmountInput").addEventListener("input", syncFundsModalUi);
    document.getElementById("submitFundsBtn").addEventListener("click", () => {
      const res = applyCapitalAction(
        fundsMode,
        document.getElementById("fundAmountInput").value,
        document.getElementById("fundNoteInput").value
      );
      if (!res.ok) showToast(res.error, "error");
      else {
        document.getElementById("fundNoteInput").value = "";
        closeFundsModal();
      }
    });

    // Broker Modal bindings
    const closeBrokerModal = () => document.getElementById("brokerModalBackdrop").classList.remove("open");
    document.getElementById("closeBrokerModal").addEventListener("click", closeBrokerModal);
    document.querySelectorAll("[data-broker-oauth]").forEach((btn) => {
      btn.addEventListener("click", () => launchBrokerOAuth(btn.dataset.brokerOauth));
    });
    document.getElementById("disconnectBrokerBtn").addEventListener("click", () => {
      state.broker = { provider: "PUBLIC_FREE", apiKey: "", accessToken: "", connectedAt: null };
      saveState();
      updateFeedStatusPill();
      renderBrokerStatusBox();
      showToast("Switched to Free Public Live Feeds (Binance + NSE/BSE/MCX)", "info");
    });
    document.getElementById("saveBrokerBtn").addEventListener("click", () => {
      state.broker = {
        provider: document.getElementById("brokerSelect").value,
        apiKey: document.getElementById("brokerApiKey").value.trim(),
        accessToken: document.getElementById("brokerAccessToken").value.trim(),
        connectedAt: Date.now(),
      };
      saveState();
      updateFeedStatusPill();
      showToast("Broker settings saved.", "success");
      closeBrokerModal();
    });
  }

  // =========================================================================
  // 13. BOOTSTRAP & TIMERS
  // =========================================================================
  function updateClockAndSession() {
    const p = getIstParts(new Date());
    const hh = String(p.hours).padStart(2, "0");
    const mm = String(p.minutes).padStart(2, "0");
    const ss = String(p.seconds).padStart(2, "0");
    document.getElementById("istClock").textContent = `${hh}:${mm}:${ss} IST`;

    const isWeekday = p.day >= 1 && p.day <= 5;
    const eqOpen = isWeekday && p.totalMinutes >= 555 && p.totalMinutes < 915;   // 09:15 - 15:15
    const optOpen = isWeekday && p.totalMinutes >= 555 && p.totalMinutes < 940;  // 09:15 - 15:40
    const label = eqOpen
      ? "NSE Open • Eq 15:15 | Opt 15:40"
      : optOpen
        ? "Eq Closed (15:15) • Opt Open till 15:40"
        : "India Closed • 15:15 Eq | 15:40 Opt";
    document.getElementById("sessionStatusLabel").textContent = label;
  }

  function init() {
    document.documentElement.setAttribute("data-theme", state.theme || "dark");
    document.getElementById("toggleThemeBtn").textContent = state.theme === "dark" ? "☀️" : "🌙";

    checkOAuthCallbackParams();
    bindEvents();
    updateClockAndSession();

    // Generate initial signals & render immediately
    scanSignals(false);
    renderAllViews();

    // Launch real live data feeds immediately!
    startBinanceWebSocket();
    pollBinanceRest();
    pollPublicIndiaAndMcxQuotes(false);
    loadRealCandlesForChart(currentChartSymbol, currentTf);

    // Periodic live polling & evaluation timers
    setInterval(pollBinanceRest, 3500);
    setInterval(() => pollPublicIndiaAndMcxQuotes(false), 15000);

    setInterval(() => {
      updateClockAndSession();
      runFallbackSimTicks();
      evaluatePendingOrdersAndRisk();
      evaluateSignalOutcomes();
      renderTopMetrics();
      if (currentTab === "watchlist") renderWatchlist();
      else if (currentTab === "positions") renderPositions();
      else if (currentTab === "chart") drawChart();
      else if (currentTab === "signals") renderSignals();
    }, 1200);

    setInterval(() => {
      scanSignals(true);
      if (currentTab === "signals") renderSignals();
    }, 25000);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
