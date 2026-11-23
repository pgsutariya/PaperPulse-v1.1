(() => {
  "use strict";

  const STORAGE_KEY = "paperpulse_web_state_v1";
  const INITIAL_CAPITAL = 1000000;

  const SEED_INSTRUMENTS = [
    // NSE
    { symbol: "RELIANCE", name: "Reliance Industries Ltd", segment: "NSE", type: "STOCK", lotSize: 1, tick: 0.05, ltp: 1284.50, prevClose: 1268.20 },
    { symbol: "TCS", name: "Tata Consultancy Services", segment: "NSE", type: "STOCK", lotSize: 1, tick: 0.05, ltp: 4142.80, prevClose: 4165.00 },
    { symbol: "HDFCBANK", name: "HDFC Bank Ltd", segment: "NSE", type: "STOCK", lotSize: 1, tick: 0.05, ltp: 1742.35, prevClose: 1725.90 },
    { symbol: "TATAMOTORS", name: "Tata Motors Ltd", segment: "NSE", type: "STOCK", lotSize: 1, tick: 0.05, ltp: 812.60, prevClose: 798.40 },
    { symbol: "NIFTY26NOVFUT", name: "NIFTY 50 Nov Futures", segment: "NSE", type: "FUTURES", lotSize: 25, tick: 0.05, ltp: 24468.50, prevClose: 24355.00 },
    { symbol: "BANKNIFTY26NOVFUT", name: "BANK NIFTY Nov Futures", segment: "NSE", type: "FUTURES", lotSize: 15, tick: 0.05, ltp: 52140.00, prevClose: 51910.25 },
    { symbol: "NIFTY26NOV24500CE", name: "NIFTY 24500 Call Nov", segment: "NSE", type: "OPTIONS", lotSize: 25, tick: 0.05, ltp: 164.25, prevClose: 142.80 },
    { symbol: "BANKNIFTY26NOV52000PE", name: "BANKNIFTY 52000 Put Nov", segment: "NSE", type: "OPTIONS", lotSize: 15, tick: 0.05, ltp: 298.40, prevClose: 334.10 },
    // BSE
    { symbol: "BSE:SBIN", name: "State Bank of India (BSE)", segment: "BSE", type: "STOCK", lotSize: 1, tick: 0.05, ltp: 836.90, prevClose: 829.15 },
    { symbol: "BSE:BHARTIARTL", name: "Bharti Airtel Ltd (BSE)", segment: "BSE", type: "STOCK", lotSize: 1, tick: 0.05, ltp: 1612.40, prevClose: 1624.80 },
    { symbol: "SENSEX26NOVFUT", name: "SENSEX Nov Futures", segment: "BSE", type: "FUTURES", lotSize: 10, tick: 0.05, ltp: 80312.00, prevClose: 79945.50 },
    { symbol: "SENSEX26NOV80000CE", name: "SENSEX 80000 Call Nov", segment: "BSE", type: "OPTIONS", lotSize: 10, tick: 0.05, ltp: 442.75, prevClose: 389.00 },
    // MCX
    { symbol: "GOLD26DECFUT", name: "MCX Gold Dec Futures", segment: "MCX", type: "FUTURES", lotSize: 1, tick: 1.0, ltp: 75840.00, prevClose: 75410.00 },
    { symbol: "SILVERM26DECFUT", name: "MCX Silver Mini Dec Fut", segment: "MCX", type: "FUTURES", lotSize: 5, tick: 1.0, ltp: 90450.00, prevClose: 91120.00 },
    { symbol: "CRUDEOIL26NOVFUT", name: "MCX Crude Oil Nov Fut", segment: "MCX", type: "FUTURES", lotSize: 100, tick: 1.0, ltp: 5842.00, prevClose: 5788.00 },
    { symbol: "CRUDEOIL26NOV5900CE", name: "Crude Oil 5900 Call Nov", segment: "MCX", type: "OPTIONS", lotSize: 100, tick: 0.10, ltp: 112.40, prevClose: 96.80 },
    // CRYPTO
    { symbol: "BTCUSDT", name: "Bitcoin / Tether Spot", segment: "CRYPTO", type: "STOCK", lotSize: 1, tick: 0.50, ltp: 91420.50, prevClose: 89850.00, binanceStream: "btcusdt" },
    { symbol: "ETHUSDT", name: "Ethereum / Tether Spot", segment: "CRYPTO", type: "STOCK", lotSize: 1, tick: 0.10, ltp: 3184.20, prevClose: 3120.80, binanceStream: "ethusdt" },
    { symbol: "SOLUSDT", name: "Solana / Tether Spot", segment: "CRYPTO", type: "STOCK", lotSize: 1, tick: 0.05, ltp: 236.45, prevClose: 241.10, binanceStream: "solusdt" },
    { symbol: "BTCUSDT-PERP", name: "Bitcoin Perpetual Futures", segment: "CRYPTO", type: "FUTURES", lotSize: 1, tick: 1.00, ltp: 91510.00, prevClose: 89910.00, binanceStream: "btcusdt" }
  ];

  const TF_MS = { "1m": 60e3, "5m": 300e3, "15m": 900e3, "1H": 3600e3, "1D": 86400e3 };

  const state = {
    view: "watchlist",
    segmentFilter: "ALL",
    searchQuery: "",
    signalDirFilter: "ALL",
    orderStatusFilter: "ALL",
    chartSymbol: "BTCUSDT",
    chartTf: "5m",
    ocUnderlying: "NIFTY",
    liveCryptoEnabled: true,
    binanceConnected: false,
    instruments: new Map(),
    candles: new Map(), // symbol -> array of OHLCV
    signals: [],
    account: {
      virtualCapital: INITIAL_CAPITAL,
      availableMargin: INITIAL_CAPITAL,
      realisedPnl: 0
    },
    positions: [],
    orders: [],
    journal: [],
    hoverIndex: null,
    ws: null
  };

  // --- Utilities ---
  function esc(str) {
    return String(str ?? "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function roundTick(price, tick = 0.05) {
    const t = tick || 0.05;
    return Number((Math.round(price / t) * t).toFixed(2));
  }

  function fmtPrice(val, segment) {
    const prefix = segment === "CRYPTO" ? "$" : "₹";
    return prefix + Number(val).toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }

  function fmtInr(val, signed = false) {
    const num = Number(val) || 0;
    const sign = signed ? (num > 0 ? "+" : num < 0 ? "-" : "") : (num < 0 ? "-" : "");
    return sign + "₹" + Math.abs(num).toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }

  function fmtTime(ts) {
    return new Date(ts).toLocaleTimeString("en-IN", { hour12: false });
  }

  function toast(message, tone = "info") {
    const stack = document.getElementById("toastStack");
    const el = document.createElement("div");
    el.className = `toast ${tone}`;
    el.textContent = message;
    stack.appendChild(el);
    setTimeout(() => el.remove(), 4200);
  }

  // --- Persistence ---
  function saveState() {
    try {
      const payload = {
        account: state.account,
        positions: state.positions,
        orders: state.orders,
        journal: state.journal,
        theme: document.documentElement.getAttribute("data-theme"),
        liveCryptoEnabled: state.liveCryptoEnabled
      };
      localStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
    } catch (_) { /* ignore quota errors */ }
  }

  function loadState() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return;
      const data = JSON.parse(raw);
      if (data.account) state.account = data.account;
      if (Array.isArray(data.positions)) state.positions = data.positions;
      if (Array.isArray(data.orders)) state.orders = data.orders;
      if (Array.isArray(data.journal)) state.journal = data.journal;
      if (typeof data.liveCryptoEnabled === "boolean") state.liveCryptoEnabled = data.liveCryptoEnabled;
      if (data.theme === "light" || data.theme === "dark") {
        document.documentElement.setAttribute("data-theme", data.theme);
      }
    } catch (_) { /* ignore malformed storage */ }
  }

  // --- Market & Candles Initialization ---
  function seedHistoryFor(inst) {
    const count = 90;
    const candles = [];
    let price = inst.prevClose * (0.985 + (Math.abs(hashCode(inst.symbol)) % 25) * 0.001);
    const now = Date.now();
    const step = TF_MS[state.chartTf] || 300e3;
    const volScale = inst.segment === "CRYPTO" ? 0.0045 : 0.0032;

    for (let i = count; i >= 1; i--) {
      const t = now - i * step;
      const drift = (inst.ltp - price) / (i + 3);
      const wave = Math.sin(i * 0.45 + hashCode(inst.symbol)) * price * volScale * 0.6;
      const noise = (pseudoRand(hashCode(inst.symbol) + i * 17) - 0.5) * price * volScale;
      const open = roundTick(price, inst.tick);
      const close = i === 1 ? inst.ltp : roundTick(Math.max(inst.tick, open + drift + wave + noise), inst.tick);
      const wick = Math.abs(close - open) * 0.8 + price * volScale * 0.35;
      const high = roundTick(Math.max(open, close) + wick * pseudoRand(i * 31), inst.tick);
      const low = roundTick(Math.max(inst.tick, Math.min(open, close) - wick * pseudoRand(i * 47)), inst.tick);
      const volume = Math.round(1200 + pseudoRand(i * 73) * 9500);
      candles.push({ time: t, open, high, low, close, volume });
      price = close;
    }
    return candles;
  }

  function hashCode(s) {
    let h = 0;
    for (let i = 0; i < s.length; i++) h = ((h << 5) - h + s.charCodeAt(i)) | 0;
    return h;
  }

  function pseudoRand(seed) {
    const x = Math.sin(seed * 12.9898) * 43758.5453;
    return x - Math.floor(x);
  }

  function initInstruments() {
    state.instruments.clear();
    state.candles.clear();
    for (const raw of SEED_INSTRUMENTS) {
      const inst = {
        ...raw,
        dayHigh: roundTick(Math.max(raw.ltp, raw.prevClose) * 1.006, raw.tick),
        dayLow: roundTick(Math.min(raw.ltp, raw.prevClose) * 0.994, raw.tick),
        spark: [],
        lastDir: raw.ltp >= raw.prevClose ? 1 : -1
      };
      const hist = seedHistoryFor(inst);
      inst.spark = hist.slice(-20).map((c) => c.close);
      state.instruments.set(inst.symbol, inst);
      state.candles.set(inst.symbol, hist);
    }
  }

  // --- Technical Indicators ---
  function computeEma(values, period) {
    const out = new Array(values.length).fill(null);
    if (values.length < period) return out;
    const k = 2 / (period + 1);
    let ema = values.slice(0, period).reduce((a, b) => a + b, 0) / period;
    out[period - 1] = ema;
    for (let i = period; i < values.length; i++) {
      ema = values[i] * k + ema * (1 - k);
      out[i] = ema;
    }
    return out;
  }

  function computeVwap(candles) {
    let pv = 0;
    let vol = 0;
    return candles.map((c) => {
      const tp = (c.high + c.low + c.close) / 3;
      pv += tp * c.volume;
      vol += c.volume;
      return vol > 0 ? pv / vol : c.close;
    });
  }

  function computeRsi(closes, period = 14) {
    const out = new Array(closes.length).fill(null);
    if (closes.length <= period) return out;
    let gains = 0;
    let losses = 0;
    for (let i = 1; i <= period; i++) {
      const diff = closes[i] - closes[i - 1];
      if (diff >= 0) gains += diff;
      else losses -= diff;
    }
    let avgGain = gains / period;
    let avgLoss = losses / period;
    out[period] = avgLoss === 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss);
    for (let i = period + 1; i < closes.length; i++) {
      const diff = closes[i] - closes[i - 1];
      avgGain = (avgGain * (period - 1) + Math.max(diff, 0)) / period;
      avgLoss = (avgLoss * (period - 1) + Math.max(-diff, 0)) / period;
      out[i] = avgLoss === 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss);
    }
    return out;
  }

  function computeAtr(candles, period = 14) {
    if (candles.length < 2) return 0;
    const trs = [];
    for (let i = 1; i < candles.length; i++) {
      const c = candles[i];
      const pc = candles[i - 1].close;
      trs.push(Math.max(c.high - c.low, Math.abs(c.high - pc), Math.abs(c.low - pc)));
    }
    const slice = trs.slice(-period);
    return slice.reduce((a, b) => a + b, 0) / slice.length;
  }

  // --- Signal Scanner ---
  function scanSignals() {
    const found = [];
    for (const inst of state.instruments.values()) {
      const candles = state.candles.get(inst.symbol) || [];
      if (candles.length < 30) continue;
      const closes = candles.map((c) => c.close);
      const ema9 = computeEma(closes, 9);
      const ema21 = computeEma(closes, 21);
      const vwap = computeVwap(candles);
      const rsi = computeRsi(closes, 14);
      const atr = Math.max(computeAtr(candles, 14), inst.ltp * 0.004);
      const lastIdx = candles.length - 1;
      const c = candles[lastIdx];
      const e9 = ema9[lastIdx];
      const e21 = ema21[lastIdx];
      const vw = vwap[lastIdx];
      const r = rsi[lastIdx] ?? 50;

      if (e9 == null || e21 == null) continue;

      let strategy = null;
      let direction = null;
      let confidence = "MEDIUM";
      const reasons = [];

      if (e9 > e21 && c.close > vw && r >= 52 && r <= 72) {
        strategy = "EMA 9/21 + VWAP Momentum";
        direction = "LONG";
        confidence = r > 58 ? "HIGH" : "MEDIUM";
        reasons.push(`EMA 9 (${e9.toFixed(2)}) above EMA 21 (${e21.toFixed(2)})`);
        reasons.push(`Price holding above session VWAP (${vw.toFixed(2)})`);
        reasons.push(`RSI(14) healthy bullish momentum at ${r.toFixed(1)}`);
      } else if (e9 < e21 && c.close < vw && r <= 48 && r >= 28) {
        strategy = "EMA 9/21 Breakdown";
        direction = "SHORT";
        confidence = r < 42 ? "HIGH" : "MEDIUM";
        reasons.push(`EMA 9 (${e9.toFixed(2)}) below EMA 21 (${e21.toFixed(2)})`);
        reasons.push(`Trading below session VWAP (${vw.toFixed(2)})`);
        reasons.push(`RSI(14) bearish pressure at ${r.toFixed(1)}`);
      } else if (r < 32) {
        strategy = "Oversold Reversal Confluence";
        direction = "LONG";
        confidence = "MEDIUM";
        reasons.push(`RSI(14) oversold extreme at ${r.toFixed(1)}`);
        reasons.push(`Mean-reversion stretch below VWAP (${vw.toFixed(2)})`);
      } else if (r > 68) {
        strategy = "Overbought Mean Reversion";
        direction = "SHORT";
        confidence = "MEDIUM";
        reasons.push(`RSI(14) overbought extreme at ${r.toFixed(1)}`);
        reasons.push(`Extended above VWAP (${vw.toFixed(2)})`);
      }

      if (!strategy) continue;

      const entry = inst.ltp;
      const risk = Math.max(atr * 1.15, inst.tick * 4);
      const sl = roundTick(direction === "LONG" ? entry - risk : entry + risk, inst.tick);
      const t1 = roundTick(direction === "LONG" ? entry + risk * 1.8 : entry - risk * 1.8, inst.tick);
      const t2 = roundTick(direction === "LONG" ? entry + risk * 3.0 : entry - risk * 3.0, inst.tick);

      found.push({
        id: `${inst.symbol}_${direction}`,
        symbol: inst.symbol,
        segment: inst.segment,
        strategy,
        direction,
        timeframe: state.chartTf,
        confidence,
        entry,
        sl,
        t1,
        t2,
        rr: 1.8,
        reasons
      });
    }
    state.signals = found;
    updateBadges();
  }

  // --- Black-Scholes Option Chain ---
  function normCdf(x) {
    const a1 = 0.254829592, a2 = -0.284496736, a3 = 1.421413741, a4 = -1.453152027, a5 = 1.061405429;
    const p = 0.3275911;
    const sign = x < 0 ? -1 : 1;
    const z = Math.abs(x) / Math.SQRT2;
    const t = 1 / (1 + p * z);
    const y = 1 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-z * z);
    return 0.5 * (1 + sign * y);
  }

  function normPdf(x) {
    return Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI);
  }

  function blackScholes(spot, strike, tYears, r, iv, isCall) {
    const sqrtT = Math.sqrt(Math.max(tYears, 1e-5));
    const d1 = (Math.log(spot / strike) + (r + 0.5 * iv * iv) * tYears) / (iv * sqrtT);
    const d2 = d1 - iv * sqrtT;
    const price = isCall
      ? spot * normCdf(d1) - strike * Math.exp(-r * tYears) * normCdf(d2)
      : strike * Math.exp(-r * tYears) * normCdf(-d2) - spot * normCdf(-d1);
    const delta = isCall ? normCdf(d1) : normCdf(d1) - 1;
    const gamma = normPdf(d1) / (spot * iv * sqrtT);
    const vega = (spot * normPdf(d1) * sqrtT) / 100;
    const theta = (-(spot * normPdf(d1) * iv) / (2 * sqrtT) - (isCall ? 1 : -1) * r * strike * Math.exp(-r * tYears) * normCdf(isCall ? d2 : -d2)) / 365;
    return { price: Math.max(0.05, price), delta, gamma, vega, theta };
  }

  function buildOptionChain(underlying) {
    const configs = {
      NIFTY: { ref: "NIFTY26NOVFUT", step: 50, lot: 25, seg: "NSE", baseIv: 0.145, dte: 10 },
      BANKNIFTY: { ref: "BANKNIFTY26NOVFUT", step: 100, lot: 15, seg: "NSE", baseIv: 0.175, dte: 10 },
      SENSEX: { ref: "SENSEX26NOVFUT", step: 100, lot: 10, seg: "BSE", baseIv: 0.14, dte: 11 },
      BTC: { ref: "BTCUSDT", step: 1000, lot: 1, seg: "CRYPTO", baseIv: 0.52, dte: 14 }
    };
    const cfg = configs[underlying] || configs.NIFTY;
    const spot = state.instruments.get(cfg.ref)?.ltp || 24500;
    const atm = Math.round(spot / cfg.step) * cfg.step;
    const tYears = cfg.dte / 365;
    const r = cfg.seg === "CRYPTO" ? 0.04 : 0.065;

    const rows = [];
    let totalCeOi = 0;
    let totalPeOi = 0;

    for (let k = -6; k <= 6; k++) {
      const strike = atm + k * cfg.step;
      const skew = Math.abs(k) * 0.008 + (k < 0 ? 0.01 : 0);
      const ceIv = cfg.baseIv + skew;
      const peIv = cfg.baseIv + skew + 0.006;
      const ce = blackScholes(spot, strike, tYears, r, ceIv, true);
      const pe = blackScholes(spot, strike, tYears, r, peIv, false);
      const ceOi = Math.round((18 - Math.abs(k - 1) * 1.9) * 100000);
      const peOi = Math.round((19 - Math.abs(k + 1) * 1.8) * 100000);
      totalCeOi += Math.max(10000, ceOi);
      totalPeOi += Math.max(10000, peOi);
      rows.push({
        strike,
        isAtm: k === 0,
        ce: { ...ce, iv: ceIv * 100, oi: Math.max(10000, ceOi), symbol: `${underlying} ${strike} CE` },
        pe: { ...pe, iv: peIv * 100, oi: Math.max(10000, peOi), symbol: `${underlying} ${strike} PE` }
      });
    }

    // Max Pain calculation
    let maxPainStrike = atm;
    let minPain = Infinity;
    for (const cand of rows) {
      let pain = 0;
      for (const rRow of rows) {
        if (cand.strike > rRow.strike) pain += (cand.strike - rRow.strike) * rRow.ce.oi;
        if (cand.strike < rRow.strike) pain += (rRow.strike - cand.strike) * rRow.pe.oi;
      }
      if (pain < minPain) { minPain = pain; maxPainStrike = cand.strike; }
    }

    return { underlying, spot, atm, cfg, rows, pcr: totalPeOi / Math.max(1, totalCeOi), maxPainStrike };
  }

  // --- Execution & Risk Engine ---
  function calcMarginRequired(price, qty, product) {
    const notional = price * qty;
    return product === "INTRADAY" ? notional * 0.2 : notional * 0.5;
  }

  function placeOrder(req) {
    const inst = state.instruments.get(req.symbol);
    if (!inst) return;
    const qty = req.lots * inst.lotSize;
    const execPrice = req.orderType === "MARKET" ? inst.ltp : req.price;
    const marginNeeded = calcMarginRequired(execPrice, qty, req.product);

    if (marginNeeded > state.account.availableMargin) {
      toast(`Insufficient margin. Need ${fmtInr(marginNeeded)}, available ${fmtInr(state.account.availableMargin)}.`, "bear");
      return false;
    }

    const order = {
      id: "ORD-" + Math.random().toString(36).slice(2, 8).toUpperCase(),
      time: Date.now(),
      symbol: inst.symbol,
      segment: inst.segment,
      side: req.side,
      orderType: req.orderType,
      product: req.product,
      validity: req.validity,
      qty,
      lots: req.lots,
      price: execPrice,
      sl: req.slEnabled && req.sl > 0 ? req.sl : null,
      target: req.targetEnabled && req.target > 0 ? req.target : null,
      tslEnabled: req.tslEnabled,
      tslPct: req.tslPct || 0.6,
      note: req.note || "",
      status: req.orderType === "MARKET" ? "EXECUTED" : "PENDING"
    };

    state.orders.unshift(order);

    if (order.status === "EXECUTED") {
      openPositionFromOrder(order, inst.ltp);
      toast(`${order.side} ${order.qty} ${order.symbol} filled @ ${fmtPrice(inst.ltp, inst.segment)}`, order.side === "BUY" ? "bull" : "bear");
    } else {
      state.account.availableMargin -= marginNeeded;
      toast(`Pending ${order.orderType} ${order.side} ${order.symbol} @ ${fmtPrice(order.price, order.segment)}`, "info");
    }

    saveState();
    renderAll();
    return true;
  }

  function openPositionFromOrder(order, fillPrice) {
    const marginUsed = calcMarginRequired(fillPrice, order.qty, order.product);
    state.account.availableMargin -= marginUsed;
    state.positions.unshift({
      id: "POS-" + Math.random().toString(36).slice(2, 8).toUpperCase(),
      symbol: order.symbol,
      segment: order.segment,
      side: order.side,
      product: order.product,
      qty: order.qty,
      entryPrice: fillPrice,
      marginUsed,
      sl: order.sl,
      target: order.target,
      tslEnabled: order.tslEnabled,
      tslPct: order.tslPct,
      peakPrice: fillPrice,
      note: order.note,
      openedAt: Date.now()
    });
  }

  function closePosition(posId, exitPrice, reason = "Manual Exit") {
    const idx = state.positions.findIndex((p) => p.id === posId);
    if (idx === -1) return;
    const pos = state.positions[idx];
    const dir = pos.side === "BUY" ? 1 : -1;
    const rawPnl = (exitPrice - pos.entryPrice) * pos.qty * dir;
    const brokerage = Math.min(40, Math.abs(exitPrice * pos.qty) * 0.0003);
    const netPnl = rawPnl - brokerage;

    state.account.availableMargin += pos.marginUsed + netPnl;
    state.account.realisedPnl += netPnl;
    state.positions.splice(idx, 1);

    state.journal.unshift({
      id: "JRN-" + Math.random().toString(36).slice(2, 8).toUpperCase(),
      closedAt: Date.now(),
      symbol: pos.symbol,
      segment: pos.segment,
      side: pos.side,
      qty: pos.qty,
      entryPrice: pos.entryPrice,
      exitPrice,
      netPnl,
      reason,
      note: pos.note
    });

    toast(`${pos.symbol} closed (${reason}): ${fmtInr(netPnl, true)}`, netPnl >= 0 ? "bull" : "bear");
    saveState();
  }

  function cancelOrder(orderId) {
    const ord = state.orders.find((o) => o.id === orderId && o.status === "PENDING");
    if (!ord) return;
    ord.status = "CANCELLED";
    state.account.availableMargin += calcMarginRequired(ord.price, ord.qty, ord.product);
    toast(`Cancelled pending order for ${ord.symbol}`, "info");
    saveState();
    renderAll();
  }

  function evaluateEngineOnTick(inst) {
    // 1. Check pending limit orders
    for (const ord of state.orders) {
      if (ord.status !== "PENDING" || ord.symbol !== inst.symbol) continue;
      const triggered =
        (ord.side === "BUY" && inst.ltp <= ord.price) ||
        (ord.side === "SELL" && inst.ltp >= ord.price);
      if (triggered) {
        ord.status = "EXECUTED";
        // Margin was already reserved on placement; refund then re-reserve at fill
        state.account.availableMargin += calcMarginRequired(ord.price, ord.qty, ord.product);
        openPositionFromOrder(ord, inst.ltp);
        toast(`Limit order filled: ${ord.side} ${ord.symbol} @ ${fmtPrice(inst.ltp, inst.segment)}`, "bull");
      }
    }

    // 2. Check open positions SL / TSL / Target (OCO)
    const snapshot = [...state.positions];
    for (const pos of snapshot) {
      if (pos.symbol !== inst.symbol) continue;
      const isBuy = pos.side === "BUY";

      if (pos.tslEnabled && pos.sl != null) {
        if (isBuy && inst.ltp > pos.peakPrice) {
          pos.peakPrice = inst.ltp;
          const trailedSl = roundTick(inst.ltp * (1 - pos.tslPct / 100), inst.tick);
          if (trailedSl > pos.sl) pos.sl = trailedSl;
        } else if (!isBuy && inst.ltp < pos.peakPrice) {
          pos.peakPrice = inst.ltp;
          const trailedSl = roundTick(inst.ltp * (1 + pos.tslPct / 100), inst.tick);
          if (trailedSl < pos.sl) pos.sl = trailedSl;
        }
      }

      if (pos.sl != null) {
        const slHit = isBuy ? inst.ltp <= pos.sl : inst.ltp >= pos.sl;
        if (slHit) {
          closePosition(pos.id, inst.ltp, pos.tslEnabled ? "Trailing SL Hit" : "Stop-Loss Hit");
          continue;
        }
      }

      if (pos.target != null) {
        const tgtHit = isBuy ? inst.ltp >= pos.target : inst.ltp <= pos.target;
        if (tgtHit) {
          closePosition(pos.id, inst.ltp, "Target Hit (OCO)");
        }
      }
    }
  }

  // --- Feeds (Binance Live WebSocket + Simulated Indian Markets) ---
  function applyTick(symbol, newPrice) {
    const inst = state.instruments.get(symbol);
    if (!inst || !Number.isFinite(newPrice) || newPrice <= 0) return;
    const rounded = roundTick(newPrice, inst.tick);
    inst.lastDir = rounded >= inst.ltp ? 1 : -1;
    inst.ltp = rounded;
    if (rounded > inst.dayHigh) inst.dayHigh = rounded;
    if (rounded < inst.dayLow) inst.dayLow = rounded;
    inst.spark.push(rounded);
    if (inst.spark.length > 24) inst.spark.shift();

    // Update latest candle
    const list = state.candles.get(symbol);
    if (list && list.length > 0) {
      const last = list[list.length - 1];
      const tfMs = TF_MS[state.chartTf] || 300e3;
      const now = Date.now();
      if (now - last.time >= tfMs) {
        list.push({ time: now, open: last.close, high: rounded, low: rounded, close: rounded, volume: 250 });
        if (list.length > 140) list.shift();
      } else {
        last.close = rounded;
        if (rounded > last.high) last.high = rounded;
        if (rounded < last.low) last.low = rounded;
        last.volume += 40;
      }
    }

    evaluateEngineOnTick(inst);
  }

  function connectBinance() {
    if (state.ws) {
      try { state.ws.close(); } catch (_) {}
      state.ws = null;
    }
    if (!state.liveCryptoEnabled) {
      state.binanceConnected = false;
      updateFeedPill();
      return;
    }
    const endpoints = [
      "wss://data-stream.binance.vision/stream?streams=btcusdt@miniTicker/ethusdt@miniTicker/solusdt@miniTicker",
      "wss://stream.binance.com:9443/stream?streams=btcusdt@miniTicker/ethusdt@miniTicker/solusdt@miniTicker"
    ];

    let idx = 0;
    function tryConnect() {
      if (!state.liveCryptoEnabled) return;
      const ws = new WebSocket(endpoints[idx % endpoints.length]);
      state.ws = ws;
      ws.onopen = () => {
        state.binanceConnected = true;
        updateFeedPill();
      };
      ws.onmessage = (ev) => {
        try {
          const msg = JSON.parse(ev.data);
          const d = msg.data;
          if (!d || !d.s) return;
          const price = parseFloat(d.c);
          const open24 = parseFloat(d.o);
          if (state.instruments.has(d.s)) {
            const inst = state.instruments.get(d.s);
            if (Number.isFinite(open24) && open24 > 0) inst.prevClose = roundTick(open24, inst.tick);
            applyTick(d.s, price);
          }
          if (d.s === "BTCUSDT" && state.instruments.has("BTCUSDT-PERP")) {
            applyTick("BTCUSDT-PERP", price * 1.0008);
          }
        } catch (_) {}
      };
      ws.onerror = () => {
        state.binanceConnected = false;
        updateFeedPill();
      };
      ws.onclose = () => {
        state.binanceConnected = false;
        updateFeedPill();
        if (state.liveCryptoEnabled) {
          idx++;
          setTimeout(tryConnect, 4000);
        }
      };
    }
    tryConnect();
  }

  function runSimulatedTick() {
    for (const inst of state.instruments.values()) {
      if (inst.segment === "CRYPTO" && state.liveCryptoEnabled && state.binanceConnected) continue;
      const vol = inst.type === "OPTIONS" ? 0.0085 : inst.segment === "CRYPTO" ? 0.0022 : 0.0014;
      const deltaPct = (Math.random() - 0.495) * vol;
      const next = Math.max(inst.tick, inst.ltp * (1 + deltaPct));
      applyTick(inst.symbol, next);
    }
  }

  function updateFeedPill() {
    const dot = document.getElementById("feedStatusDot");
    const text = document.getElementById("feedStatusText");
    const btnLabel = document.getElementById("feedModeLabel");
    if (state.liveCryptoEnabled && state.binanceConnected) {
      dot.className = "status-dot live";
      text.textContent = "Binance Live + Sim NSE/BSE/MCX";
      btnLabel.textContent = "Live Crypto";
    } else if (state.liveCryptoEnabled) {
      dot.className = "status-dot";
      text.textContent = "Connecting Binance… (Sim active)";
      btnLabel.textContent = "Live Crypto";
    } else {
      dot.className = "status-dot sim";
      text.textContent = "Simulated Feed (All Markets)";
      btnLabel.textContent = "Simulated";
    }
  }

  // --- Rendering ---
  function updateTopMetrics() {
    let unrealised = 0;
    for (const pos of state.positions) {
      const ltp = state.instruments.get(pos.symbol)?.ltp ?? pos.entryPrice;
      const dir = pos.side === "BUY" ? 1 : -1;
      unrealised += (ltp - pos.entryPrice) * pos.qty * dir;
    }

    const marginEl = document.getElementById("metricMargin");
    const unEl = document.getElementById("metricUnrealised");
    const reEl = document.getElementById("metricRealised");
    marginEl.textContent = fmtInr(state.account.availableMargin);
    unEl.textContent = fmtInr(unrealised, true);
    unEl.className = `metric-value mono ${unrealised >= 0 ? "pos" : "neg"}`;
    reEl.textContent = fmtInr(state.account.realisedPnl, true);
    reEl.className = `metric-value mono ${state.account.realisedPnl >= 0 ? "pos" : "neg"}`;

    const ist = new Date().toLocaleTimeString("en-IN", {
      timeZone: "Asia/Kolkata",
      hour12: false
    });
    document.getElementById("istClock").textContent = `${ist} IST`;
  }

  function updateBadges() {
    const sigB = document.getElementById("badgeSignals");
    const posB = document.getElementById("badgePositions");
    const ordB = document.getElementById("badgeOrders");

    sigB.textContent = state.signals.length;
    sigB.hidden = state.signals.length === 0;

    posB.textContent = state.positions.length;
    posB.hidden = state.positions.length === 0;

    const pendingCount = state.orders.filter((o) => o.status === "PENDING").length;
    ordB.textContent = pendingCount;
    ordB.hidden = pendingCount === 0;
  }

  function sparklineSvg(points, isUp) {
    if (!points || points.length < 2) return "";
    const min = Math.min(...points);
    const max = Math.max(...points);
    const span = max - min || 1;
    const w = 78, h = 24;
    const coords = points.map((p, i) => {
      const x = (i / (points.length - 1)) * w;
      const y = h - ((p - min) / span) * (h - 4) - 2;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    }).join(" ");
    const stroke = isUp ? "var(--bull)" : "var(--bear)";
    return `<svg width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" aria-hidden="true"><polyline fill="none" stroke="${stroke}" stroke-width="1.8" points="${coords}"/></svg>`;
  }

  function renderWatchlist() {
    const body = document.getElementById("watchlistBody");
    const q = state.searchQuery.trim().toLowerCase();
    const list = [...state.instruments.values()].filter((inst) => {
      if (state.segmentFilter !== "ALL" && inst.segment !== state.segmentFilter) return false;
      if (q && !inst.symbol.toLowerCase().includes(q) && !inst.name.toLowerCase().includes(q)) return false;
      return true;
    });

    body.innerHTML = list.map((inst) => {
      const chgPct = ((inst.ltp - inst.prevClose) / inst.prevClose) * 100;
      const isUp = chgPct >= 0;
      const segCls = `badge-${inst.segment.toLowerCase()}`;
      return `
        <tr>
          <td>
            <span class="inst-symbol">${esc(inst.symbol)}</span>
            <span class="inst-name">${esc(inst.name)}</span>
          </td>
          <td><span class="badge ${segCls}">${esc(inst.segment)}</span></td>
          <td class="num mono ${inst.lastDir >= 0 ? "pos" : "neg"}">${fmtPrice(inst.ltp, inst.segment)}</td>
          <td class="num mono ${isUp ? "pos" : "neg"}">${isUp ? "+" : ""}${chgPct.toFixed(2)}%</td>
          <td class="num mono hide-sm">${inst.dayLow.toFixed(2)} – ${inst.dayHigh.toFixed(2)}</td>
          <td class="num mono hide-md">${inst.lotSize}</td>
          <td class="num">${sparklineSvg(inst.spark, isUp)}</td>
          <td class="actions-col">
            <div class="row-actions">
              <button class="btn btn-sm btn-secondary" data-action="chart" data-symbol="${esc(inst.symbol)}">Chart</button>
              <button class="btn btn-sm btn-buy" data-action="buy" data-symbol="${esc(inst.symbol)}">B</button>
              <button class="btn btn-sm btn-sell" data-action="sell" data-symbol="${esc(inst.symbol)}">S</button>
            </div>
          </td>
        </tr>
      `;
    }).join("");
  }

  function renderChart() {
    const inst = state.instruments.get(state.chartSymbol);
    if (!inst) return;
    const chgPct = ((inst.ltp - inst.prevClose) / inst.prevClose) * 100;
    document.getElementById("chartLtp").textContent = fmtPrice(inst.ltp, inst.segment);
    const chgEl = document.getElementById("chartChg");
    chgEl.textContent = `${chgPct >= 0 ? "+" : ""}${chgPct.toFixed(2)}%`;
    chgEl.className = `chart-chg mono ${chgPct >= 0 ? "pos" : "neg"}`;

    const candles = state.candles.get(inst.symbol) || [];
    if (!candles.length) return;

    const canvas = document.getElementById("candleCanvas");
    const rect = canvas.parentElement.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    canvas.width = Math.max(300, Math.floor(rect.width * dpr));
    canvas.height = Math.max(240, Math.floor(rect.height * dpr));
    const ctx = canvas.getContext("2d");
    ctx.scale(dpr, dpr);

    const w = rect.width;
    const h = rect.height;
    const padL = 12, padR = 72, padT = 16, padB = 26;
    const plotW = w - padL - padR;
    const plotH = h - padT - padB;

    const styles = getComputedStyle(document.documentElement);
    const colBg = styles.getPropertyValue("--surface").trim();
    const colGrid = styles.getPropertyValue("--border").trim();
    const colText = styles.getPropertyValue("--text-muted").trim();
    const colBull = styles.getPropertyValue("--bull").trim();
    const colBear = styles.getPropertyValue("--bear").trim();

    ctx.fillStyle = colBg;
    ctx.fillRect(0, 0, w, h);

    const visible = candles.slice(-70);
    const closes = visible.map((c) => c.close);
    const ema9 = computeEma(closes, 9);
    const ema21 = computeEma(closes, 21);
    const ema50 = computeEma(closes, 50);
    const vwap = computeVwap(visible);

    let minP = Infinity, maxP = -Infinity;
    for (const c of visible) {
      if (c.low < minP) minP = c.low;
      if (c.high > maxP) maxP = c.high;
    }
    const padRange = (maxP - minP || 1) * 0.08;
    minP -= padRange;
    maxP += padRange;
    const yOf = (p) => padT + ((maxP - p) / (maxP - minP)) * plotH;
    const stepX = plotW / visible.length;

    // Horizontal grid & price axis
    ctx.strokeStyle = colGrid;
    ctx.lineWidth = 1;
    ctx.font = "11px 'JetBrains Mono', monospace";
    ctx.fillStyle = colText;
    for (let i = 0; i <= 5; i++) {
      const y = padT + (plotH * i) / 5;
      const p = maxP - ((maxP - minP) * i) / 5;
      ctx.beginPath();
      ctx.moveTo(padL, y);
      ctx.lineTo(w - padR, y);
      ctx.stroke();
      ctx.fillText(p.toFixed(2), w - padR + 6, y + 4);
    }

    // Candles
    const barW = Math.max(3, stepX * 0.62);
    visible.forEach((c, i) => {
      const cx = padL + i * stepX + stepX / 2;
      const up = c.close >= c.open;
      ctx.strokeStyle = up ? colBull : colBear;
      ctx.fillStyle = up ? colBull : colBear;
      ctx.lineWidth = 1.2;
      ctx.beginPath();
      ctx.moveTo(cx, yOf(c.high));
      ctx.lineTo(cx, yOf(c.low));
      ctx.stroke();
      const top = yOf(Math.max(c.open, c.close));
      const bot = yOf(Math.min(c.open, c.close));
      ctx.fillRect(cx - barW / 2, top, barW, Math.max(1.5, bot - top));
    });

    // Indicator Lines
    function drawSeries(series, color) {
      ctx.strokeStyle = color;
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      let started = false;
      series.forEach((v, i) => {
        if (v == null) return;
        const x = padL + i * stepX + stepX / 2;
        const y = yOf(v);
        if (!started) { ctx.moveTo(x, y); started = true; }
        else ctx.lineTo(x, y);
      });
      ctx.stroke();
    }

    if (document.getElementById("indEma9").checked) drawSeries(ema9, "#22d3ee");
    if (document.getElementById("indEma21").checked) drawSeries(ema21, "#f59e0b");
    if (document.getElementById("indEma50").checked) drawSeries(ema50, "#a78bfa");
    if (document.getElementById("indVwap").checked) drawSeries(vwap, "#4f8bff");

    // Active position Entry / SL / Target lines on chart
    const openPos = state.positions.find((p) => p.symbol === inst.symbol);
    if (openPos) {
      const drawLevel = (price, label, color) => {
        if (price == null || price < minP || price > maxP) return;
        const y = yOf(price);
        ctx.save();
        ctx.setLineDash([5, 4]);
        ctx.strokeStyle = color;
        ctx.beginPath();
        ctx.moveTo(padL, y);
        ctx.lineTo(w - padR, y);
        ctx.stroke();
        ctx.restore();
        ctx.fillStyle = color;
        ctx.fillText(`${label} ${price.toFixed(2)}`, padL + 6, y - 4);
      };
      drawLevel(openPos.entryPrice, `${openPos.side} AVG`, "#4f8bff");
      drawLevel(openPos.sl, openPos.tslEnabled ? "TSL" : "SL", colBear);
      drawLevel(openPos.target, "TGT", colBull);
    }

    // Hover crosshair & OHLC bar
    const hi = state.hoverIndex != null ? Math.min(visible.length - 1, Math.max(0, state.hoverIndex)) : visible.length - 1;
    const hc = visible[hi];
    if (hc) {
      document.getElementById("chartOhlcBar").textContent =
        `${inst.symbol} (${state.chartTf})  O: ${hc.open.toFixed(2)}  H: ${hc.high.toFixed(2)}  L: ${hc.low.toFixed(2)}  C: ${hc.close.toFixed(2)}  Vol: ${hc.volume}`;
      if (state.hoverIndex != null) {
        const hx = padL + hi * stepX + stepX / 2;
        ctx.save();
        ctx.setLineDash([3, 3]);
        ctx.strokeStyle = colText;
        ctx.beginPath();
        ctx.moveTo(hx, padT);
        ctx.lineTo(hx, h - padB);
        ctx.stroke();
        ctx.restore();
      }
    }

    // RSI subplot
    const rsiCanvas = document.getElementById("rsiCanvas");
    const rRect = rsiCanvas.parentElement.getBoundingClientRect();
    rsiCanvas.width = Math.max(300, Math.floor(rRect.width * dpr));
    rsiCanvas.height = Math.max(70, Math.floor((rRect.height - 6) * dpr));
    const rCtx = rsiCanvas.getContext("2d");
    rCtx.scale(dpr, dpr);
    const rw = rRect.width, rh = rRect.height - 6;
    const rsiSeries = computeRsi(closes, 14);
    const lastRsi = rsiSeries[rsiSeries.length - 1];
    document.getElementById("chartRsiLabel").textContent = `RSI(14): ${lastRsi != null ? lastRsi.toFixed(1) : "--"}`;

    rCtx.fillStyle = colBg;
    rCtx.fillRect(0, 0, rw, rh);
    [30, 70].forEach((lvl) => {
      const y = rh - (lvl / 100) * rh;
      rCtx.strokeStyle = colGrid;
      rCtx.beginPath();
      rCtx.moveTo(padL, y);
      rCtx.lineTo(rw - padR, y);
      rCtx.stroke();
    });
    rCtx.strokeStyle = "#a78bfa";
    rCtx.lineWidth = 1.6;
    rCtx.beginPath();
    let rStart = false;
    rsiSeries.forEach((val, i) => {
      if (val == null) return;
      const x = padL + i * stepX + stepX / 2;
      const y = rh - (val / 100) * rh;
      if (!rStart) { rCtx.moveTo(x, y); rStart = true; }
      else rCtx.lineTo(x, y);
    });
    rCtx.stroke();
  }

  function renderSignals() {
    const grid = document.getElementById("signalsGrid");
    const list = state.signals.filter((s) => state.signalDirFilter === "ALL" || s.direction === state.signalDirFilter);
    if (!list.length) {
      grid.innerHTML = `<div class="empty-state">No active setups match this filter right now. Click <strong>Rescan Now</strong> or wait for the next market move.</div>`;
      return;
    }

    grid.innerHTML = list.map((s) => `
      <article class="card signal-card">
        <div class="signal-top">
          <div>
            <span class="inst-symbol">${esc(s.symbol)}</span>
            <span class="inst-name">${esc(s.strategy)} • ${esc(s.timeframe)}</span>
          </div>
          <div style="display:flex;gap:6px;align-items:center;">
            <span class="badge badge-${s.segment.toLowerCase()}">${esc(s.segment)}</span>
            <span class="badge badge-${s.direction.toLowerCase()}">${esc(s.direction)}</span>
          </div>
        </div>
        <div class="signal-levels mono">
          <div class="level-cell"><span>Entry</span><strong>${s.entry.toFixed(2)}</strong></div>
          <div class="level-cell"><span>SL</span><strong class="neg">${s.sl.toFixed(2)}</strong></div>
          <div class="level-cell"><span>Target 1</span><strong class="pos">${s.t1.toFixed(2)}</strong></div>
          <div class="level-cell"><span>R:R</span><strong>1:${s.rr.toFixed(1)}</strong></div>
        </div>
        <ul class="signal-reasons">
          ${s.reasons.map((r) => `<li>${esc(r)}</li>`).join("")}
        </ul>
        <div class="signal-footer">
          <span class="badge badge-paper">${esc(s.confidence)} CONFIDENCE</span>
          <div class="row-actions">
            <button class="btn btn-sm btn-secondary" data-action="chart" data-symbol="${esc(s.symbol)}">Chart</button>
            <button class="btn btn-sm ${s.direction === "LONG" ? "btn-buy" : "btn-sell"}" data-action="trade-signal" data-signal-id="${esc(s.id)}">Trade Setup</button>
          </div>
        </div>
      </article>
    `).join("");
  }

  function renderOptionChain() {
    const chain = buildOptionChain(state.ocUnderlying);
    const strip = document.getElementById("ocSummaryStrip");
    strip.innerHTML = `
      <div class="stat-card"><div class="stat-label">${esc(chain.underlying)} Spot</div><div class="stat-value mono">${fmtPrice(chain.spot, chain.cfg.seg)}</div></div>
      <div class="stat-card"><div class="stat-label">ATM Strike</div><div class="stat-value mono">${chain.atm}</div></div>
      <div class="stat-card"><div class="stat-label">Put-Call Ratio (PCR)</div><div class="stat-value mono ${chain.pcr >= 1 ? "pos" : "neg"}">${chain.pcr.toFixed(2)}</div></div>
      <div class="stat-card"><div class="stat-label">Max Pain</div><div class="stat-value mono">${chain.maxPainStrike}</div></div>
    `;

    const fmtOi = (oi) => oi >= 100000 ? `${(oi / 100000).toFixed(2)}L` : `${(oi / 1000).toFixed(1)}K`;
    const body = document.getElementById("optionChainBody");
    body.innerHTML = chain.rows.map((r) => `
      <tr class="oc-row ${r.isAtm ? "atm" : ""}">
        <td class="num mono">${fmtOi(r.ce.oi)}</td>
        <td class="num mono hide-sm">${r.ce.iv.toFixed(1)}%</td>
        <td class="num mono hide-md">${r.ce.delta.toFixed(2)}</td>
        <td class="num mono pos"><span class="oc-click" data-oc-side="CE" data-strike="${r.strike}" data-price="${r.ce.price.toFixed(2)}">${r.ce.price.toFixed(2)}</span></td>
        <td class="strike-col mono">${r.strike}${r.isAtm ? " ATM" : ""}</td>
        <td class="num mono neg"><span class="oc-click" data-oc-side="PE" data-strike="${r.strike}" data-price="${r.pe.price.toFixed(2)}">${r.pe.price.toFixed(2)}</span></td>
        <td class="num mono hide-md">${r.pe.delta.toFixed(2)}</td>
        <td class="num mono hide-sm">${r.pe.iv.toFixed(1)}%</td>
        <td class="num mono">${fmtOi(r.pe.oi)}</td>
      </tr>
    `).join("");
  }

  function renderPositions() {
    const container = document.getElementById("positionsList");
    if (!state.positions.length) {
      container.innerHTML = `<div class="empty-state">No open positions yet. Place a BUY or SELL order from the Watchlist, Chart, Signals, or Option Chain.</div>`;
      return;
    }

    container.innerHTML = state.positions.map((p) => {
      const ltp = state.instruments.get(p.symbol)?.ltp ?? p.entryPrice;
      const dir = p.side === "BUY" ? 1 : -1;
      const pnl = (ltp - p.entryPrice) * p.qty * dir;
      const pnlPct = ((ltp - p.entryPrice) / p.entryPrice) * 100 * dir;
      return `
        <article class="card position-card">
          <div class="pos-header">
            <div>
              <span class="inst-symbol">${esc(p.symbol)}</span>
              <div style="display:flex;gap:6px;margin-top:4px;">
                <span class="badge badge-${p.side.toLowerCase()}">${esc(p.side)} ${p.qty}</span>
                <span class="badge badge-paper">${esc(p.product)}</span>
                ${p.tslEnabled ? `<span class="badge badge-bse">TSL ${p.tslPct}%</span>` : ""}
              </div>
            </div>
            <div class="pos-pnl mono ${pnl >= 0 ? "pos" : "neg"}">
              ${fmtInr(pnl, true)}
              <div style="font-size:11px;">(${pnlPct >= 0 ? "+" : ""}${pnlPct.toFixed(2)}%)</div>
            </div>
          </div>
          <div class="pos-grid mono">
            <div><span>Entry</span><strong>${fmtPrice(p.entryPrice, p.segment)}</strong></div>
            <div><span>LTP</span><strong>${fmtPrice(ltp, p.segment)}</strong></div>
            <div><span>Margin</span><strong>${fmtInr(p.marginUsed)}</strong></div>
            <div><span>Stop Loss</span><strong class="neg">${p.sl != null ? fmtPrice(p.sl, p.segment) : "None"}</strong></div>
            <div><span>Target</span><strong class="pos">${p.target != null ? fmtPrice(p.target, p.segment) : "None"}</strong></div>
            <div><span>Opened</span><strong>${fmtTime(p.openedAt)}</strong></div>
          </div>
          <div class="pos-actions">
            <button class="btn btn-sm btn-secondary" data-action="chart" data-symbol="${esc(p.symbol)}">Chart</button>
            <button class="btn btn-sm btn-danger" data-action="close-pos" data-pos-id="${esc(p.id)}">Exit @ Market</button>
          </div>
        </article>
      `;
    }).join("");
  }

  function renderOrders() {
    const body = document.getElementById("ordersBody");
    const list = state.orders.filter((o) => state.orderStatusFilter === "ALL" || o.status === state.orderStatusFilter);
    if (!list.length) {
      body.innerHTML = `<tr><td colspan="8" style="text-align:center;padding:30px;color:var(--text-muted);">No orders in this filter.</td></tr>`;
      return;
    }

    body.innerHTML = list.map((o) => `
      <tr>
        <td class="mono">${fmtTime(o.time)}</td>
        <td><strong>${esc(o.symbol)}</strong></td>
        <td><span class="badge badge-${o.side.toLowerCase()}">${esc(o.side)}</span> <span class="badge badge-paper">${esc(o.orderType)} • ${esc(o.validity)}</span></td>
        <td class="num mono">${o.qty}</td>
        <td class="num mono">${fmtPrice(o.price, o.segment)}</td>
        <td class="num mono hide-sm">${o.sl ?? "–"} / ${o.target ?? "–"}</td>
        <td><span class="badge badge-${o.status.toLowerCase()}">${esc(o.status)}</span></td>
        <td class="actions-col">
          ${o.status === "PENDING" ? `<button class="btn btn-sm btn-danger" data-action="cancel-order" data-order-id="${esc(o.id)}">Cancel</button>` : "—"}
        </td>
      </tr>
    `).join("");
  }

  function renderJournal() {
    const strip = document.getElementById("journalSummaryStrip");
    const total = state.journal.length;
    const wins = state.journal.filter((j) => j.netPnl > 0);
    const losses = state.journal.filter((j) => j.netPnl <= 0);
    const grossWin = wins.reduce((a, b) => a + b.netPnl, 0);
    const grossLoss = Math.abs(losses.reduce((a, b) => a + b.netPnl, 0));
    const winRate = total ? (wins.length / total) * 100 : 0;
    const pf = grossLoss > 0 ? (grossWin / grossLoss).toFixed(2) : (grossWin > 0 ? "∞" : "0.00");

    strip.innerHTML = `
      <div class="stat-card"><div class="stat-label">Closed Trades</div><div class="stat-value mono">${total}</div></div>
      <div class="stat-card"><div class="stat-label">Win Rate</div><div class="stat-value mono ${winRate >= 50 ? "pos" : ""}">${winRate.toFixed(1)}%</div></div>
      <div class="stat-card"><div class="stat-label">Profit Factor</div><div class="stat-value mono">${pf}</div></div>
      <div class="stat-card"><div class="stat-label">Net Realised P&amp;L</div><div class="stat-value mono ${state.account.realisedPnl >= 0 ? "pos" : "neg"}">${fmtInr(state.account.realisedPnl, true)}</div></div>
    `;

    const body = document.getElementById("journalBody");
    if (!total) {
      body.innerHTML = `<tr><td colspan="8" style="text-align:center;padding:30px;color:var(--text-muted);">Closed trades will be logged automatically here.</td></tr>`;
      return;
    }

    body.innerHTML = state.journal.map((j) => `
      <tr>
        <td class="mono">${fmtTime(j.closedAt)}</td>
        <td><strong>${esc(j.symbol)}</strong></td>
        <td><span class="badge badge-${j.side.toLowerCase()}">${esc(j.side)}</span></td>
        <td class="num mono">${j.qty}</td>
        <td class="num mono">${j.entryPrice.toFixed(2)} → ${j.exitPrice.toFixed(2)}</td>
        <td>${esc(j.reason)}</td>
        <td class="num mono ${j.netPnl >= 0 ? "pos" : "neg"}">${fmtInr(j.netPnl, true)}</td>
        <td>${esc(j.note || "—")}</td>
      </tr>
    `).join("");
  }

  function renderAll() {
    updateTopMetrics();
    updateBadges();
    if (state.view === "watchlist") renderWatchlist();
    if (state.view === "chart") renderChart();
    if (state.view === "signals") renderSignals();
    if (state.view === "optionchain") renderOptionChain();
    if (state.view === "positions") renderPositions();
    if (state.view === "orders") renderOrders();
    if (state.view === "journal") renderJournal();
  }

  // --- Order Modal ---
  let modalSide = "BUY";

  function openOrderModal(symbol, side = "BUY", prefill = {}) {
    let inst = state.instruments.get(symbol);
    if (!inst && prefill.syntheticOption) {
      // Dynamically add clicked Option Chain contract into instrument universe
      const opt = prefill.syntheticOption;
      inst = {
        symbol,
        name: `${opt.underlying} ${opt.strike} ${opt.optType}`,
        segment: opt.segment,
        type: "OPTIONS",
        lotSize: opt.lotSize,
        tick: 0.05,
        ltp: opt.price,
        prevClose: roundTick(opt.price * 0.96, 0.05),
        dayHigh: roundTick(opt.price * 1.08, 0.05),
        dayLow: roundTick(opt.price * 0.92, 0.05),
        spark: [opt.price, opt.price],
        lastDir: 1
      };
      state.instruments.set(symbol, inst);
      state.candles.set(symbol, seedHistoryFor(inst));
    }
    if (!inst) return;

    modalSide = side;
    document.getElementById("orderSymbol").value = inst.symbol;
    document.getElementById("orderModalTitle").textContent = `${inst.symbol} — Paper Order`;
    document.getElementById("orderModalSub").textContent = `${inst.name} • LTP ${fmtPrice(inst.ltp, inst.segment)}`;
    document.getElementById("orderLotSizeHint").textContent = String(inst.lotSize);
    document.getElementById("orderLots").value = "1";
    document.getElementById("orderPrice").value = (prefill.entry ?? inst.ltp).toFixed(2);
    document.getElementById("orderPrice").step = String(inst.tick);
    document.getElementById("orderType").value = prefill.orderType || "MARKET";
    document.getElementById("orderProduct").value = prefill.product || "INTRADAY";
    document.getElementById("orderValidity").value = prefill.validity || "DAY";
    document.getElementById("orderNote").value = prefill.note || "";

    const defaultSl = prefill.sl ?? roundTick(side === "BUY" ? inst.ltp * 0.988 : inst.ltp * 1.012, inst.tick);
    const defaultTgt = prefill.target ?? roundTick(side === "BUY" ? inst.ltp * 1.022 : inst.ltp * 0.978, inst.tick);
    document.getElementById("orderSlEnabled").checked = true;
    document.getElementById("orderTargetEnabled").checked = true;
    document.getElementById("orderTslEnabled").checked = Boolean(prefill.tsl);
    document.getElementById("orderSlPrice").value = defaultSl.toFixed(2);
    document.getElementById("orderTargetPrice").value = defaultTgt.toFixed(2);

    syncModalSideUI();
    updateOrderSummary();
    document.getElementById("orderModal").hidden = false;
  }

  function syncModalSideUI() {
    document.querySelectorAll(".side-tab").forEach((b) => {
      b.classList.toggle("active", b.dataset.side === modalSide);
    });
    const submitBtn = document.getElementById("submitOrderBtn");
    submitBtn.textContent = `Confirm ${modalSide}`;
    submitBtn.className = `btn ${modalSide === "BUY" ? "btn-buy" : "btn-sell"}`;
  }

  function updateOrderSummary() {
    const symbol = document.getElementById("orderSymbol").value;
    const inst = state.instruments.get(symbol);
    if (!inst) return;
    const lots = Math.max(1, parseInt(document.getElementById("orderLots").value, 10) || 1);
    const price = parseFloat(document.getElementById("orderPrice").value) || inst.ltp;
    const product = document.getElementById("orderProduct").value;
    const sl = parseFloat(document.getElementById("orderSlPrice").value);
    const tgt = parseFloat(document.getElementById("orderTargetPrice").value);
    const margin = calcMarginRequired(price, lots * inst.lotSize, product);

    let rrText = "N/A";
    if (document.getElementById("orderSlEnabled").checked && document.getElementById("orderTargetEnabled").checked) {
      const risk = Math.abs(price - sl);
      const reward = Math.abs(tgt - price);
      if (risk > 0) rrText = `1:${(reward / risk).toFixed(2)}`;
    }
    document.getElementById("orderSummary").textContent =
      `Qty: ${lots * inst.lotSize} | Est. Margin: ${fmtInr(margin)} | R:R: ${rrText}`;
  }

  // --- Event Wiring ---
  function switchView(view) {
    state.view = view;
    document.querySelectorAll(".nav-item").forEach((b) => b.classList.toggle("active", b.dataset.view === view));
    document.querySelectorAll(".view").forEach((s) => s.classList.toggle("active", s.id === `view-${view}`));
    renderAll();
  }

  function populateChartSelect() {
    const sel = document.getElementById("chartSymbolSelect");
    sel.innerHTML = [...state.instruments.values()]
      .map((i) => `<option value="${esc(i.symbol)}">${esc(i.symbol)} (${esc(i.segment)})</option>`)
      .join("");
    sel.value = state.chartSymbol;
  }

  function wireEvents() {
    document.querySelectorAll(".nav-item").forEach((btn) => {
      btn.addEventListener("click", () => switchView(btn.dataset.view));
    });

    document.getElementById("watchlistSearch").addEventListener("input", (e) => {
      state.searchQuery = e.target.value;
      renderWatchlist();
    });

    document.querySelectorAll("#segmentFilters .seg-btn").forEach((btn) => {
      btn.addEventListener("click", () => {
        state.segmentFilter = btn.dataset.segment;
        document.querySelectorAll("#segmentFilters .seg-btn").forEach((b) => b.classList.toggle("active", b === btn));
        renderWatchlist();
      });
    });

    document.querySelectorAll("#signalDirFilters .seg-btn").forEach((btn) => {
      btn.addEventListener("click", () => {
        state.signalDirFilter = btn.dataset.dir;
        document.querySelectorAll("#signalDirFilters .seg-btn").forEach((b) => b.classList.toggle("active", b === btn));
        renderSignals();
      });
    });

    document.getElementById("rescanSignalsBtn").addEventListener("click", () => {
      scanSignals();
      renderSignals();
      toast(`Scanned ${state.instruments.size} instruments — ${state.signals.length} setups active.`, "info");
    });

    document.querySelectorAll("#ocUnderlyingBtns .seg-btn").forEach((btn) => {
      btn.addEventListener("click", () => {
        state.ocUnderlying = btn.dataset.underlying;
        document.querySelectorAll("#ocUnderlyingBtns .seg-btn").forEach((b) => b.classList.toggle("active", b === btn));
        renderOptionChain();
      });
    });

    document.querySelectorAll("#orderStatusFilters .seg-btn").forEach((btn) => {
      btn.addEventListener("click", () => {
        state.orderStatusFilter = btn.dataset.status;
        document.querySelectorAll("#orderStatusFilters .seg-btn").forEach((b) => b.classList.toggle("active", b === btn));
        renderOrders();
      });
    });

    document.getElementById("chartSymbolSelect").addEventListener("change", (e) => {
      state.chartSymbol = e.target.value;
      renderChart();
    });

    document.querySelectorAll("#timeframeButtons .seg-btn").forEach((btn) => {
      btn.addEventListener("click", () => {
        state.chartTf = btn.dataset.tf;
        document.querySelectorAll("#timeframeButtons .seg-btn").forEach((b) => b.classList.toggle("active", b === btn));
        const inst = state.instruments.get(state.chartSymbol);
        if (inst) state.candles.set(inst.symbol, seedHistoryFor(inst));
        renderChart();
      });
    });

    ["indEma9", "indEma21", "indEma50", "indVwap"].forEach((id) => {
      document.getElementById(id).addEventListener("change", renderChart);
    });

    document.getElementById("chartBuyBtn").addEventListener("click", () => openOrderModal(state.chartSymbol, "BUY"));
    document.getElementById("chartSellBtn").addEventListener("click", () => openOrderModal(state.chartSymbol, "SELL"));

    const canvas = document.getElementById("candleCanvas");
    canvas.addEventListener("mousemove", (e) => {
      const rect = canvas.getBoundingClientRect();
      const x = e.clientX - rect.left - 12;
      const plotW = rect.width - 84;
      const candles = (state.candles.get(state.chartSymbol) || []).slice(-70);
      if (!candles.length || plotW <= 0) return;
      state.hoverIndex = Math.floor((x / plotW) * candles.length);
      renderChart();
    });
    canvas.addEventListener("mouseleave", () => {
      state.hoverIndex = null;
      renderChart();
    });

    // Global delegated clicks for dynamic buttons
    document.addEventListener("click", (e) => {
      const btn = e.target.closest("[data-action]");
      if (btn) {
        const action = btn.dataset.action;
        if (action === "chart") {
          state.chartSymbol = btn.dataset.symbol;
          document.getElementById("chartSymbolSelect").value = state.chartSymbol;
          switchView("chart");
        } else if (action === "buy") {
          openOrderModal(btn.dataset.symbol, "BUY");
        } else if (action === "sell") {
          openOrderModal(btn.dataset.symbol, "SELL");
        } else if (action === "trade-signal") {
          const sig = state.signals.find((s) => s.id === btn.dataset.signalId);
          if (sig) {
            openOrderModal(sig.symbol, sig.direction === "LONG" ? "BUY" : "SELL", {
              entry: sig.entry,
              sl: sig.sl,
              target: sig.t1,
              tsl: true,
              note: sig.strategy
            });
          }
        } else if (action === "close-pos") {
          const pos = state.positions.find((p) => p.id === btn.dataset.posId);
          if (pos) {
            const ltp = state.instruments.get(pos.symbol)?.ltp ?? pos.entryPrice;
            closePosition(pos.id, ltp, "Manual Exit");
            renderAll();
          }
        } else if (action === "cancel-order") {
          cancelOrder(btn.dataset.orderId);
        }
        return;
      }

      const ocClick = e.target.closest(".oc-click");
      if (ocClick) {
        const chain = buildOptionChain(state.ocUnderlying);
        const optType = ocClick.dataset.ocSide;
        const strike = Number(ocClick.dataset.strike);
        const price = Number(ocClick.dataset.price);
        const sym = `${chain.underlying}26NOV${strike}${optType}`;
        openOrderModal(sym, "BUY", {
          entry: price,
          syntheticOption: {
            underlying: chain.underlying,
            strike,
            optType,
            price,
            lotSize: chain.cfg.lot,
            segment: chain.cfg.seg
          }
        });
      }
    });

    // Modal controls
    document.querySelectorAll(".side-tab").forEach((b) => {
      b.addEventListener("click", () => {
        modalSide = b.dataset.side;
        const sym = document.getElementById("orderSymbol").value;
        const inst = state.instruments.get(sym);
        if (inst) {
          document.getElementById("orderSlPrice").value = roundTick(
            modalSide === "BUY" ? inst.ltp * 0.988 : inst.ltp * 1.012,
            inst.tick
          ).toFixed(2);
          document.getElementById("orderTargetPrice").value = roundTick(
            modalSide === "BUY" ? inst.ltp * 1.022 : inst.ltp * 0.978,
            inst.tick
          ).toFixed(2);
        }
        syncModalSideUI();
        updateOrderSummary();
      });
    });

    ["orderLots", "orderPrice", "orderProduct", "orderSlPrice", "orderTargetPrice", "orderSlEnabled", "orderTargetEnabled"].forEach((id) => {
      document.getElementById(id).addEventListener("input", updateOrderSummary);
      document.getElementById(id).addEventListener("change", updateOrderSummary);
    });

    const closeModal = () => { document.getElementById("orderModal").hidden = true; };
    document.getElementById("closeOrderModalBtn").addEventListener("click", closeModal);
    document.getElementById("cancelOrderBtn").addEventListener("click", closeModal);
    document.getElementById("orderModal").addEventListener("click", (e) => {
      if (e.target.id === "orderModal") closeModal();
    });

    document.getElementById("orderForm").addEventListener("submit", (e) => {
      e.preventDefault();
      const ok = placeOrder({
        symbol: document.getElementById("orderSymbol").value,
        side: modalSide,
        orderType: document.getElementById("orderType").value,
        product: document.getElementById("orderProduct").value,
        validity: document.getElementById("orderValidity").value,
        lots: Math.max(1, parseInt(document.getElementById("orderLots").value, 10) || 1),
        price: parseFloat(document.getElementById("orderPrice").value),
        slEnabled: document.getElementById("orderSlEnabled").checked,
        sl: parseFloat(document.getElementById("orderSlPrice").value),
        targetEnabled: document.getElementById("orderTargetEnabled").checked,
        target: parseFloat(document.getElementById("orderTargetPrice").value),
        tslEnabled: document.getElementById("orderTslEnabled").checked,
        tslPct: parseFloat(document.getElementById("orderTslPct").value) || 0.6,
        note: document.getElementById("orderNote").value.trim()
      });
      if (ok) closeModal();
    });

    // Square Off All
    document.getElementById("squareOffAllBtn").addEventListener("click", () => {
      if (!state.positions.length) return;
      [...state.positions].forEach((p) => {
        const ltp = state.instruments.get(p.symbol)?.ltp ?? p.entryPrice;
        closePosition(p.id, ltp, "Square Off All");
      });
      renderAll();
    });

    // Theme Toggle
    const syncThemeIcon = () => {
      const dark = document.documentElement.getAttribute("data-theme") !== "light";
      document.getElementById("themeIcon").textContent = dark ? "☀" : "☾";
    };
    syncThemeIcon();
    document.getElementById("toggleThemeBtn").addEventListener("click", () => {
      const next = document.documentElement.getAttribute("data-theme") === "light" ? "dark" : "light";
      document.documentElement.setAttribute("data-theme", next);
      syncThemeIcon();
      saveState();
      if (state.view === "chart") renderChart();
    });

    // Feed Toggle
    document.getElementById("toggleFeedBtn").addEventListener("click", () => {
      state.liveCryptoEnabled = !state.liveCryptoEnabled;
      saveState();
      connectBinance();
      updateFeedPill();
    });

    // Reset Account
    document.getElementById("resetAccountBtn").addEventListener("click", () => {
      state.account = { virtualCapital: INITIAL_CAPITAL, availableMargin: INITIAL_CAPITAL, realisedPnl: 0 };
      state.positions = [];
      state.orders = [];
      state.journal = [];
      saveState();
      toast("Virtual account reset to ₹10,00,000.00.", "info");
      renderAll();
    });

    window.addEventListener("resize", () => {
      if (state.view === "chart") renderChart();
    });
  }

  // --- Boot ---
  loadState();
  initInstruments();
  populateChartSelect();
  wireEvents();
  scanSignals();
  updateFeedPill();
  connectBinance();
  renderAll();

  setInterval(() => {
    runSimulatedTick();
    renderAll();
  }, 1200);

  setInterval(() => {
    scanSignals();
    if (state.view === "signals") renderSignals();
  }, 8000);
})();
