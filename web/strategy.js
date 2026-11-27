/*
 * PaperPulse trading strategy engines.
 *
 * Ported from the trader skill packs (MIT):
 *   - trader-skills-india   (Ankur Goel)  - NSE / Zerodha, the primary source for PaperPulse
 *   - day-trader-skill      (Jake Schincariol)
 *   - trader-skill          (Jake Schincariol)
 *
 * The same engines exist in Kotlin under domain/strategy/ for the Android app; keep the two
 * in step. Everything here is pure: no DOM, no network, no clock reads except where a time
 * is passed in. That is what makes it testable under Node.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.PaperPulseStrategy = api;
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  // ------------------------------------------------------------------ helpers

  const HUNDRED = 100;
  /** Round-trip NSE intraday cost as a % of turnover: brokerage, STT, exchange, SEBI, GST. */
  const ROUND_TRIP_COST_PCT = 0.12;
  /** A stop nearer than this % of price sits inside the spread. */
  const MIN_RISK_PCT = 0.05;

  function r2(x) { return Math.round((x + Number.EPSILON) * 100) / 100; }
  function money(x) { return "Rs " + r2(x).toFixed(2); }
  function plain(x) { return String(Math.round(x * 10000) / 10000); }
  function floorToTick(v, tick) { return tick > 0 ? r2(Math.floor(v / tick + 1e-9) * tick) : r2(v); }
  function roundToTick(v, tick) { return tick > 0 ? r2(Math.round(v / tick) * tick) : r2(v); }

  const DEFAULT_RULES = {
    budget: 100000,
    cashBufferPct: 10,
    maxPositionPct: 75,
    maxOrderInr: 100000,
    minOrderInr: 500,
    maxLossPerTradeInr: 500,
    maxDailyLossInr: 1500,
    maxTradesPerDay: 3,
    maxOrdersPerDay: 10,
    minRewardRisk: 1.0,
    requireStop: true,
    noEntriesBefore: "09:35",
    noEntriesAfter: "15:00",
    flatBy: "15:10",
    blocked: [],
    allowShort: false,
    allowMargin: false,
  };

  function withDefaults(rules) {
    const r = Object.assign({}, DEFAULT_RULES, rules || {});
    r.blocked = (r.blocked || []).map((s) => String(s).toUpperCase());
    return r;
  }

  function cashBufferOf(r) { return r2(r.budget * r.cashBufferPct / HUNDRED); }
  function positionCapOf(r) { return r2(r.budget * r.maxPositionPct / HUNDRED); }
  function minutesOf(hhmm) {
    const m = /^(\d{1,2}):(\d{2})$/.exec(String(hhmm));
    return m ? +m[1] * 60 + +m[2] : null;
  }

  // ------------------------------------------------------------------ exit planner

  function PlanRefused(message) {
    const e = new Error(message);
    e.name = "PlanRefused";
    return e;
  }

  /**
   * Fix the stop and the target before the entry.
   *
   * stopRule:   {kind:"or_mid"} | {kind:"or_low"} | {kind:"price", value}
   *             | {kind:"pct", value} | {kind:"atr", multiple}
   * targetRule: {kind:"r", multiple} | {kind:"price", value}
   *
   * Throws PlanRefused with a message meant to be shown to the trader as-is.
   */
  function planExit(opts) {
    const entry = r2(opts.entry);
    const tick = opts.tickSize || 0.05;
    const stopRule = opts.stopRule;
    const targetRule = opts.targetRule;
    if (!(entry > 0)) throw PlanRefused("the entry price must be above Rs 0");

    let raw, label, detail = "";
    if (stopRule.kind === "or_mid" || stopRule.kind === "or_low") {
      const oh = opts.openingRangeHigh, ol = opts.openingRangeLow;
      if (oh == null || ol == null) throw PlanRefused("an opening-range stop needs the opening range high and low");
      if (!(oh > ol)) throw PlanRefused("the opening-range high " + money(oh) + " must be above its low " + money(ol));
      if (stopRule.kind === "or_mid") {
        raw = (oh + ol) / 2;
        label = "opening-range midpoint";
        detail = "(" + money(oh) + " + " + money(ol) + ") / 2";
      } else {
        raw = ol;
        label = "opening-range low";
      }
    } else if (stopRule.kind === "price") {
      raw = stopRule.value;
      label = "your price";
    } else if (stopRule.kind === "pct") {
      if (!(stopRule.value > 0 && stopRule.value < 100)) {
        throw PlanRefused("a percent stop needs a value between 0 and 100");
      }
      raw = entry * (1 - stopRule.value / HUNDRED);
      label = plain(stopRule.value) + "% under the entry";
      detail = money(entry) + " x (1 - " + plain(stopRule.value) + "%)";
    } else if (stopRule.kind === "atr") {
      if (opts.atr == null) throw PlanRefused("an ATR stop needs the session's bars");
      if (!(stopRule.multiple > 0)) throw PlanRefused("an ATR stop needs a multiple above 0");
      raw = entry - stopRule.multiple * opts.atr;
      label = plain(stopRule.multiple) + " x ATR(" + (opts.atrBars || 14) + ") under the entry";
      detail = "ATR Rs " + opts.atr.toFixed(4) + " over " + (opts.atrBars || 14) + " bars";
    } else {
      throw PlanRefused("unknown stop rule " + JSON.stringify(stopRule.kind));
    }

    // Floored, never rounded: rounding a long's stop up would quietly shrink the risk.
    const stop = floorToTick(raw, tick);
    if (stop !== r2(raw)) detail = detail ? detail + ", rounded down to the tick" : "rounded down to the tick";
    if (stop >= entry) {
      throw PlanRefused("the stop " + money(stop) + " (" + label + ") is at or above the entry " +
        money(entry) + ": a long's stop goes under the entry");
    }
    if (stop <= 0) throw PlanRefused("the stop works out to " + money(stop) + ": pick a stop above Rs 0");

    const risk = entry - stop;
    const floorRisk = entry * MIN_RISK_PCT / HUNDRED;
    if (risk < floorRisk) {
      throw PlanRefused("the stop is " + money(risk) + " under the entry, less than " + MIN_RISK_PCT +
        "% of the price (" + money(floorRisk) + " on " + money(entry) +
        "): too tight to survive the spread. Widen it.");
    }

    let target, targetLabel;
    if (targetRule.kind === "r") {
      if (!(targetRule.multiple > 0)) throw PlanRefused("the target needs an R multiple above 0, like 1R or 2R");
      target = roundToTick(entry + targetRule.multiple * risk, tick);
      targetLabel = plain(targetRule.multiple) + "R (" + plain(targetRule.multiple) + " times the risk)";
    } else {
      target = roundToTick(targetRule.value, tick);
      targetLabel = "your price";
    }
    if (target <= entry) {
      throw PlanRefused("the target " + money(target) + " is at or below the entry " + money(entry) +
        ": a long's target goes above it");
    }
    const gainPct = (target - entry) / entry * HUNDRED;
    if (gainPct <= ROUND_TRIP_COST_PCT) {
      throw PlanRefused("the target " + money(target) + " is only " + gainPct.toFixed(2) + "% over the entry " +
        money(entry) + ", not past the ~" + ROUND_TRIP_COST_PCT + "% round-trip cost (brokerage, STT, " +
        "exchange and SEBI fees, GST): it cannot pay. Raise it.");
    }
    const rewardRisk = r2((target - entry) / risk);
    if (targetRule.kind === "price") targetLabel = "your price (" + plain(rewardRisk) + "R)";

    return {
      entry: entry, stop: stop, stopLabel: label, stopDetail: detail,
      target: target, targetLabel: targetLabel,
      riskPerShare: r2(risk), rewardRisk: rewardRisk,
    };
  }

  // ------------------------------------------------------------------ position sizer

  function sharesFor(amount, perUnit) {
    if (!(perUnit > 0) || !(amount > 0)) return 0;
    return Math.max(0, Math.floor(amount / perUnit + 1e-9));
  }

  /**
   * Size one long day trade against four limits and report which one decided it.
   * Returns {quantity, boundBy, limits[], refusal, ...}; refusal non-null means no order.
   */
  function sizePosition(opts) {
    const rules = withDefaults(opts.rules);
    const sym = String(opts.symbol || "").trim().toUpperCase();
    if (!sym) throw new Error("the plan has no symbol");
    if (rules.blocked.indexOf(sym) >= 0) throw new Error(sym + " is on your blocked list");
    if (!(rules.budget > 0)) throw new Error("no budget set");

    const plan = opts.plan;
    const entryPlan = r2(plan.entry);
    let reference = entryPlan, source = "PLAN";
    if (opts.limitPrice != null) {
      reference = r2(opts.limitPrice); source = "LIMIT";
    } else if (opts.livePrice != null && r2(opts.livePrice) > entryPlan) {
      // A market order is sized at the higher of the plan and a fresh price, so a gap up
      // can never understate the cost or the risk.
      reference = r2(opts.livePrice); source = "LIVE_PRICE";
    }

    const stop = r2(plan.stop), target = r2(plan.target);
    if (stop >= reference) {
      throw new Error("the stop " + money(stop) + " is not below the entry " + money(reference));
    }
    if (target <= reference) {
      throw new Error("the target " + money(target) + " is not above the entry " + money(reference) +
        ": the move has already happened");
    }
    const risk = reference - stop;
    const reward = target - reference;

    const cashBuffer = cashBufferOf(rules);
    let available = rules.budget - cashBuffer;
    let cashNote = "budget " + money(rules.budget) + " minus the " + money(cashBuffer) + " cash buffer";
    if (opts.cashOnHand != null && opts.cashOnHand - cashBuffer < available) {
      available = opts.cashOnHand - cashBuffer;
      cashNote = "cash " + money(opts.cashOnHand) + " minus the " + money(cashBuffer) + " buffer";
    }
    available = Math.max(0, r2(available));

    const limits = [];
    if (rules.maxLossPerTradeInr != null) {
      limits.push({
        name: "max loss", amount: rules.maxLossPerTradeInr, perShare: risk,
        shares: sharesFor(rules.maxLossPerTradeInr, risk),
        how: money(rules.maxLossPerTradeInr) + " max loss / " + money(risk) + " risk a share",
      });
    }
    const cap = positionCapOf(rules);
    limits.push({
      name: "position cap", amount: cap, perShare: reference, shares: sharesFor(cap, reference),
      how: plain(rules.maxPositionPct) + "% of " + money(rules.budget) + " = " + money(cap) + " / " + money(reference),
    });
    if (rules.maxOrderInr != null) {
      limits.push({
        name: "max order", amount: rules.maxOrderInr, perShare: reference,
        shares: sharesFor(rules.maxOrderInr, reference),
        how: money(rules.maxOrderInr) + " / " + money(reference),
      });
    }
    limits.push({
      name: "cash", amount: available, perShare: reference, shares: sharesFor(available, reference),
      how: cashNote + " = " + money(available) + " / " + money(reference),
    });

    const quantity = Math.max(0, Math.min.apply(null, limits.map((l) => l.shares)));
    const boundBy = limits.filter((l) => l.shares === quantity)[0].name;
    limits.forEach((l) => { l.binds = l.name === boundBy; });

    const cost = r2(quantity * reference);
    const maxLoss = r2(quantity * risk);
    const rewardRisk = r2(reward / risk);

    let refusal = null;
    if (quantity === 0 && boundBy === "max loss") {
      refusal = "one share risks " + money(risk) + " (entry " + money(reference) + " minus stop " + money(stop) +
        "), more than your " + money(rules.maxLossPerTradeInr) + " max loss per trade. No order. " +
        "Skip this trade, or plan a closer stop.";
    } else if (quantity === 0) {
      refusal = "one share of " + sym + " costs " + money(reference) + ", more than your " + boundBy +
        " limit (" + limits.filter((l) => l.name === boundBy)[0].how + "). No order.";
    } else if (rewardRisk < rules.minRewardRisk) {
      refusal = "reward:risk " + plain(rewardRisk) + " (target " + money(target) + ", entry " + money(reference) +
        ", stop " + money(stop) + ") is under your minimum " + plain(rules.minRewardRisk) +
        ", so the risk gate would block it. No order.";
    } else if (cost < rules.minOrderInr) {
      refusal = quantity + " share" + (quantity === 1 ? "" : "s") + " of " + sym + " is " + money(cost) +
        ", under your " + money(rules.minOrderInr) + " minimum order. No order.";
    }

    return {
      symbol: sym, entry: reference, entrySource: source, stop: stop, target: target,
      riskPerShare: r2(risk), rewardRisk: rewardRisk, cashBuffer: cashBuffer, cashAvailable: available,
      limits: limits, quantity: quantity, boundBy: boundBy, cost: cost, maxLoss: maxLoss,
      refusal: refusal, isTradeable: refusal == null && quantity > 0,
    };
  }

  // ------------------------------------------------------------------ pre-trade risk gate

  /**
   * The last gate before an order reaches the broker. Anything with a reason is BLOCKED,
   * never silently resized.
   *
   * order:   {symbol, isBuy, quantity, entry, stop?, target?, isEntry?}
   * account: {cash, equity, holdings:{SYM:qty}, holdingValue:{SYM:inr}}
   * day:     {ordersPlaced, entriesPlaced, committedLoss}
   * nowHHMM: "10:05" (optional; skips the window rules when absent)
   */
  function checkRisk(opts) {
    const rules = withDefaults(opts.rules);
    const o = opts.order || {};
    const account = opts.account || { cash: 0, equity: 0 };
    const day = Object.assign({ ordersPlaced: 0, entriesPlaced: 0, committedLoss: 0 }, opts.day || {});
    const holdings = account.holdings || {};
    const holdingValue = account.holdingValue || {};
    const isEntry = o.isEntry !== false;
    const reasons = [], warnings = [];
    const sym = String(o.symbol || "").trim().toUpperCase();

    if (!sym) reasons.push("format: no symbol");
    if (!(o.quantity > 0) || Math.abs(o.quantity - Math.round(o.quantity)) > 1e-9) {
      reasons.push("format: quantity must be a positive whole number of shares");
    }

    let reference = r2(o.entry);
    if (isEntry && opts.livePrice != null && opts.livePrice > o.entry) reference = r2(opts.livePrice);
    if (!(reference > 0)) reasons.push("format: the entry price must be above Rs 0");

    const qty = Math.max(0, Math.round(o.quantity || 0));
    const value = r2(reference * qty);

    if (rules.blocked.indexOf(sym) >= 0) reasons.push("blocked: " + sym + " is on your blocked list");

    if (!o.isBuy && isEntry && !rules.allowShort) {
      const held = holdings[sym] || 0;
      if (qty > held) {
        reasons.push("short: selling more " + sym + " than you hold (" + held +
          " shares); short selling is off");
      }
    }

    if (isEntry) {
      if (value < rules.minOrderInr) {
        reasons.push("size: " + money(value) + " is under your " + money(rules.minOrderInr) + " minimum order");
      }
      if (rules.maxOrderInr != null && value > rules.maxOrderInr) {
        reasons.push("size: " + money(value) + " is over your " + money(rules.maxOrderInr) + " max order");
      }
    }

    let lossAtStop = null, rewardRisk = null;
    if (isEntry) {
      if (o.stop == null) {
        if (rules.requireStop) reasons.push("stop: a day trade needs a stop; this order has none");
      } else if (o.isBuy && o.stop >= reference) {
        reasons.push("stop: the stop " + money(o.stop) + " is not below the entry " + money(reference));
      } else {
        const risk = Math.abs(reference - o.stop);
        lossAtStop = r2(risk * qty);
        if (rules.maxLossPerTradeInr != null && lossAtStop > rules.maxLossPerTradeInr) {
          reasons.push("loss: " + money(lossAtStop) + " at the stop is over your " +
            money(rules.maxLossPerTradeInr) + " max loss per trade");
        }
        if (o.target != null && risk > 0) {
          rewardRisk = r2((o.target - reference) / risk);
          if (rewardRisk < rules.minRewardRisk) {
            reasons.push("reward:risk " + plain(rewardRisk) + " is under your minimum " +
              plain(rules.minRewardRisk));
          }
        }
      }
    }

    if (o.isBuy && isEntry) {
      const buffer = cashBufferOf(rules);
      if (value > account.cash && !rules.allowMargin) {
        reasons.push("cash: needs " + money(value) + " but only " + money(account.cash) +
          " cash; that would be margin (borrowed money)");
      } else {
        const left = r2(account.cash - value);
        if (left < buffer) {
          reasons.push("cash: leaves " + money(left) + " cash, under your " + plain(rules.cashBufferPct) +
            "% buffer of " + money(buffer));
        }
      }
      const after = r2((holdingValue[sym] || 0) + value);
      if (account.equity > 0) {
        const pct = after / account.equity * HUNDRED;
        if (pct > rules.maxPositionPct) {
          reasons.push("position: " + sym + " would be " + money(after) + ", " + pct.toFixed(1) +
            "% of the " + money(account.equity) + " account, over your " + plain(rules.maxPositionPct) + "% cap");
        }
      }
    }

    if (isEntry) {
      if (day.entriesPlaced >= rules.maxTradesPerDay) {
        reasons.push("daily: " + day.entriesPlaced + " trades already today, at your " +
          rules.maxTradesPerDay + "-trade limit");
      }
      if (rules.maxDailyLossInr != null) {
        const wouldBe = r2(day.committedLoss + (lossAtStop || 0));
        if (wouldBe > rules.maxDailyLossInr) {
          reasons.push("daily: " + money(wouldBe) + " at risk today would pass your " +
            money(rules.maxDailyLossInr) + " daily loss cap (" + money(day.committedLoss) + " already committed)");
        }
      }
    }
    if (day.ordersPlaced >= rules.maxOrdersPerDay) {
      reasons.push("daily: " + day.ordersPlaced + " orders already today, at your " +
        rules.maxOrdersPerDay + "-order limit");
    }

    if (isEntry && opts.nowHHMM) {
      const now = minutesOf(opts.nowHHMM);
      const before = minutesOf(rules.noEntriesBefore), after = minutesOf(rules.noEntriesAfter);
      if (now != null && before != null && now < before) {
        reasons.push("window: no entries before " + rules.noEntriesBefore + " (the open is still settling)");
      }
      if (now != null && after != null && now > after) {
        reasons.push("window: no new entries after " + rules.noEntriesAfter + "; everything is flat by " +
          rules.flatBy);
      }
    }

    if (!isEntry && o.stop == null) {
      warnings.push("this is an exit, so the stop and reward:risk rules were not applied");
    }

    return {
      verdict: reasons.length === 0 ? "PASS" : "BLOCK",
      passed: reasons.length === 0,
      reasons: reasons, warnings: warnings,
      orderValue: value, lossAtStop: lossAtStop, rewardRisk: rewardRisk,
    };
  }

  // ------------------------------------------------------------------ setup scanner

  const DAILY_SETUPS = {
    breakout_20d: "20-day closing-high breakout",
    bull_flag: "bull flag",
    reclaim_ma20: "reclaimed the 20-day average",
    oversold: "oversold (RSI under 30)",
    oversold_bounce: "oversold bounce",
  };
  const MIN_DAILY_CLOSES = 30;

  /** Wilder's RSI over the whole series; null when there is not enough history. */
  function wilderRsi(closes, n) {
    n = n || 14;
    if (closes.length < n + 1) return null;
    let gain = 0, loss = 0;
    for (let i = 1; i <= n; i++) {
      const d = closes[i] - closes[i - 1];
      if (d > 0) gain += d; else loss += -d;
    }
    let ag = gain / n, al = loss / n;
    for (let i = n + 1; i < closes.length; i++) {
      const d = closes[i] - closes[i - 1];
      ag = (ag * (n - 1) + Math.max(0, d)) / n;
      al = (al * (n - 1) + Math.max(0, -d)) / n;
    }
    if (al === 0) return ag === 0 ? 50 : 100;
    return 100 - 100 / (1 + ag / al);
  }

  /** Tag the five daily setups on completed daily closes, oldest first. */
  function findDailySetups(closes) {
    const n = closes.length;
    if (n < MIN_DAILY_CLOSES) return null;
    const last = closes[n - 1];
    if (!(last > 0)) return null;

    const prior20 = closes.slice(n - 21, n - 1);
    const high20 = Math.max.apply(null, prior20);
    const ma20 = closes.slice(n - 20).reduce((a, b) => a + b, 0) / 20;
    const ma20Prev = prior20.reduce((a, b) => a + b, 0) / 20;
    const run = closes[n - 6] / closes[n - 16] - 1;
    const w5 = closes.slice(n - 5);
    const range5 = (Math.max.apply(null, w5) - Math.min.apply(null, w5)) / last;
    const rsi = wilderRsi(closes, 14);
    const rsiPrev = wilderRsi(closes.slice(0, n - 1), 14);

    const setups = [];
    if (last > high20) setups.push("breakout_20d");
    if (run > 0.10 && range5 < 0.05) setups.push("bull_flag");
    if (closes[n - 2] < ma20Prev && last > ma20) setups.push("reclaim_ma20");
    if (rsi != null && rsi < 30) setups.push("oversold");
    if (rsiPrev != null && rsiPrev < 30 && last > closes[n - 2]) setups.push("oversold_bounce");

    return {
      setups: setups, hasSetup: setups.length > 0, rsi: rsi, rsiPrev: rsiPrev,
      high20: high20, ma20: ma20, runPct: run * 100, range5Pct: range5 * 100,
    };
  }

  /** High/low/average volume of the first `rangeBars` bars. */
  function openingRange(bars, rangeBars) {
    if (!(rangeBars > 0) || bars.length < rangeBars) return null;
    const w = bars.slice(0, rangeBars);
    return {
      high: Math.max.apply(null, w.map((b) => b.h)),
      low: Math.min.apply(null, w.map((b) => b.l)),
      bars: rangeBars,
      averageVolume: w.reduce((a, b) => a + (b.v || 0), 0) / rangeBars,
    };
  }

  /** Cheap closes-only prefilter: could this chart have broken out at all? */
  function couldBreakOut(closes, rangeBars) {
    if (!(rangeBars > 0) || closes.length <= rangeBars) return false;
    const top = Math.max.apply(null, closes.slice(0, rangeBars));
    return closes.slice(rangeBars).some((c) => c >= top);
  }

  /**
   * First completed bar to CLOSE above the opening-range high. Closing above is the point:
   * an intrabar spike that falls back is not a breakout.
   */
  function openingRangeBreakout(bars, rangeBars, minVolumeMultiple) {
    const range = openingRange(bars, rangeBars);
    if (!range) return null;
    for (let i = rangeBars; i < bars.length; i++) {
      const b = bars[i];
      if (b.c <= range.high) continue;
      const vx = range.averageVolume > 0 ? (b.v || 0) / range.averageVolume : null;
      if (minVolumeMultiple != null && (vx == null || vx < minVolumeMultiple)) continue;
      return {
        barIndex: i, price: b.c,
        openingRangeHigh: range.high, openingRangeLow: range.low,
        pctOver: (b.c / range.high - 1) * 100, volumeMultiple: vx,
      };
    }
    return null;
  }

  // ------------------------------------------------------------------ outcome simulator

  const SESSION_BARS = 75;

  /** Deterministic PRNG (mulberry32) so a seed always replays the same session. */
  function rngFrom(seed) {
    let a = seed >>> 0;
    return function () {
      a |= 0; a = (a + 0x6D2B79F5) | 0;
      let t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  /** pool[i] = every past session's bar i as (o,h,l,c) ratios against bar i-1's close. */
  function buildPool(sessions, sessionBars) {
    const n = sessionBars || SESSION_BARS;
    const slots = [];
    for (let i = 0; i < n; i++) slots.push([]);
    sessions.forEach(function (s) {
      const len = Math.min(s.length, n);
      for (let i = 1; i < len; i++) {
        const pc = s[i - 1].c;
        if (!(pc > 0)) continue;
        const b = s[i];
        slots[i].push([b.o / pc, b.h / pc, b.l / pc, b.c / pc]);
      }
    });
    return { slots: slots, isEmpty: slots.every((x) => x.length === 0) };
  }

  /**
   * Replay bars fromBar+1..toBar `replays` times by resampling the pool.
   *
   * When one simulated bar spans both the stop and the target the STOP is taken: without
   * tick data the order is unknowable, and assuming the win would inflate every win rate.
   */
  function monteCarlo(opts) {
    const pool = opts.pool;
    if (!pool || pool.isEmpty) return null;
    const replays = opts.replays || 10000;
    const fromBar = opts.fromBar, toBar = opts.toBar;
    if (!(replays > 0) || !(toBar > fromBar)) return null;
    const isLong = opts.isLong !== false;
    const entry = opts.entry, stop = opts.stop, target = opts.target;
    const risk = isLong ? entry - stop : stop - entry;
    if (!(risk > 0)) return null;
    const rAtTarget = (isLong ? target - entry : entry - target) / risk;
    const jitter = opts.jitter == null ? 2 : opts.jitter;
    const slotCount = pool.slots.length;
    const rand = rngFrom(opts.seed == null ? 19 : opts.seed);
    const firstNonEmpty = pool.slots.filter((s) => s.length > 0)[0];

    let nTarget = 0, nStop = 0, nNeither = 0, greens = 0, rSum = 0;
    const closes = new Array(replays);

    for (let k = 0; k < replays; k++) {
      let price = entry, outcome = null;
      for (let i = fromBar + 1; i <= toBar; i++) {
        let idx = i;
        if (jitter > 0) {
          const off = Math.floor(rand() * (2 * jitter + 1)) - jitter;
          idx = Math.min(slotCount - 1, Math.max(1, i + off));
        }
        let candidates = pool.slots[idx];
        if (!candidates || candidates.length === 0) candidates = firstNonEmpty;
        if (!candidates) break;
        const d = candidates[Math.floor(rand() * candidates.length)];
        const high = price * d[1], low = price * d[2], close = price * d[3];
        if (outcome === null) {
          if (isLong) {
            if (low <= stop) outcome = "STOP";
            else if (high >= target) outcome = "TARGET";
          } else {
            if (high >= stop) outcome = "STOP";
            else if (low <= target) outcome = "TARGET";
          }
        }
        price = close;
      }
      const final = outcome || "NEITHER";
      if (final === "TARGET") nTarget++; else if (final === "STOP") nStop++; else nNeither++;
      closes[k] = price;
      const green = final === "TARGET" || (final === "NEITHER" && (isLong ? price > entry : price < entry));
      if (green) greens++;
      rSum += final === "TARGET" ? rAtTarget
        : final === "STOP" ? -1
        : (isLong ? price - entry : entry - price) / risk;
    }

    closes.sort((a, b) => a - b);
    const pctl = (q) => closes[Math.min(closes.length - 1, Math.floor(q * closes.length))];
    return {
      replays: replays, barsLeft: toBar - fromBar,
      probabilityTarget: nTarget / replays,
      probabilityStop: nStop / replays,
      probabilityNeither: nNeither / replays,
      probabilityGreen: greens / replays,
      medianClose: r2(pctl(0.50)), p5Close: r2(pctl(0.05)), p95Close: r2(pctl(0.95)),
      averageR: rSum / replays, rAtTarget: rAtTarget,
    };
  }

  function resolveReplayStop(rule, orHigh, orLow, entry, isLong) {
    if (rule.kind === "or_mid") return (orHigh + orLow) / 2;
    if (rule.kind === "or_low") return isLong ? orLow : orHigh;
    if (rule.kind === "price") return rule.value;
    if (rule.kind === "pct") {
      const f = rule.value / HUNDRED;
      return isLong ? entry * (1 - f) : entry * (1 + f);
    }
    return null; // an ATR stop needs per-entry bars the replay does not carry
  }

  /** Run the opening-range rule over every past session and report what actually happened. */
  function historyReplay(opts) {
    const sessions = opts.sessions || [];
    const rangeBars = opts.rangeBars;
    const targetR = opts.targetR;
    const isLong = opts.isLong !== false;
    const trades = [];

    sessions.forEach(function (session, index) {
      if (session.length <= rangeBars) return;
      const last = Math.min(opts.toBar, session.length - 1);
      if (last <= rangeBars) return;
      const range = session.slice(0, rangeBars);
      const orHigh = Math.max.apply(null, range.map((b) => b.h));
      const orLow = Math.min.apply(null, range.map((b) => b.l));

      let entryBar = -1;
      for (let i = rangeBars; i <= last; i++) {
        if (isLong ? session[i].c > orHigh : session[i].c < orLow) { entryBar = i; break; }
      }
      if (entryBar < 0) return;

      const entry = session[entryBar].c;
      const stop = resolveReplayStop(opts.stopRule, orHigh, orLow, entry, isLong);
      if (stop == null) return;
      const risk = isLong ? entry - stop : stop - entry;
      if (!(risk > 0)) return;
      const target = isLong ? entry + targetR * risk : entry - targetR * risk;

      let outcome = null;
      for (let i = entryBar + 1; i <= last; i++) {
        const b = session[i];
        if (isLong ? b.l <= stop : b.h >= stop) { outcome = "STOP"; break; }
        if (isLong ? b.h >= target : b.l <= target) { outcome = "TARGET"; break; }
      }
      const closeOut = session[last].c;
      const final = outcome || "NEITHER";
      const R = final === "TARGET" ? (isLong ? target - entry : entry - target) / risk
        : final === "STOP" ? -1
        : (isLong ? closeOut - entry : entry - closeOut) / risk;
      trades.push({
        sessionIndex: index, entryBar: entryBar, entry: entry, stop: stop, target: target,
        outcome: final, r: R,
      });
    });

    const count = (o) => trades.filter((t) => t.outcome === o).length;
    const green = trades.filter((t) => t.r > 0).length;
    return {
      sessions: sessions.length, triggered: trades.length,
      target: count("TARGET"), stop: count("STOP"), neither: count("NEITHER"),
      green: green,
      averageR: trades.length ? trades.reduce((a, t) => a + t.r, 0) / trades.length : null,
      winRate: trades.length ? green / trades.length : null,
      trades: trades,
    };
  }


  // ------------------------------------------------------------------ tax lots (India)

  /**
   * Tax lot tracking, retargeted from the US `trader-tax` skill to Indian equity:
   * the short/long boundary is 12 months, and there is no wash-sale rule to model.
   * FIFO is the default because it is what the depositories actually apply; HIFO and
   * specific-lot are planning views for "what would this sale cost me?".
   *
   * lot = {id, acquired: "YYYY-MM-DD", shares, costPerShare}
   */
  function termFor(acquiredIso, soldIso) {
    const a = new Date(acquiredIso + "T00:00:00Z");
    const s = new Date(soldIso + "T00:00:00Z");
    const anniversary = new Date(Date.UTC(a.getUTCFullYear() + 1, a.getUTCMonth(), a.getUTCDate()));
    return s.getTime() > anniversary.getTime() ? "LONG" : "SHORT";
  }

  function pickLots(lots, shares, method, lotIds) {
    method = method || "FIFO";
    if (!(shares > 0)) throw new Error("a sale needs a positive number of shares");
    const held = lots.reduce((a, l) => a + l.shares, 0);
    if (shares > held) throw new Error("selling " + shares + " shares but only " + held + " are held");
    let ordered;
    if (method === "FIFO") {
      ordered = lots.slice().sort((x, y) => (x.acquired < y.acquired ? -1 : x.acquired > y.acquired ? 1 : 0));
    } else if (method === "HIGHEST_COST") {
      ordered = lots.slice().sort((x, y) => y.costPerShare - x.costPerShare);
    } else if (method === "SPECIFIC") {
      if (!lotIds || !lotIds.length) throw new Error("specific-lot selling needs the lot ids");
      const byId = {};
      lots.forEach((l) => { byId[l.id] = l; });
      const unknown = lotIds.filter((i) => !byId[i]);
      if (unknown.length) throw new Error("no such lot: " + unknown.join(", "));
      ordered = lotIds.map((i) => byId[i]);
      if (shares > ordered.reduce((a, l) => a + l.shares, 0)) {
        throw new Error("the named lots hold fewer than " + shares + " shares");
      }
    } else {
      throw new Error("unknown lot method " + method);
    }
    const out = [];
    let left = shares;
    for (const l of ordered) {
      if (left <= 0) break;
      const take = Math.min(left, l.shares);
      out.push([l, take]);
      left -= take;
    }
    return out;
  }

  function simulateSale(opts) {
    const consumed = pickLots(opts.lots, opts.shares, opts.method, opts.lotIds).map(function (pair) {
      const lot = pair[0], take = pair[1];
      const basis = r2(lot.costPerShare * take);
      const proceeds = r2(opts.price * take);
      return {
        lotId: lot.id, acquired: lot.acquired, shares: take, basis: basis, proceeds: proceeds,
        gain: r2(proceeds - basis), term: termFor(lot.acquired, opts.soldOn),
      };
    });
    const sum = (f, pred) => r2(consumed.filter(pred || (() => true)).reduce((a, c) => a + f(c), 0));
    const proceeds = sum((c) => c.proceeds), basis = sum((c) => c.basis);
    return {
      shares: consumed.reduce((a, c) => a + c.shares, 0),
      proceeds: proceeds, basis: basis, gain: r2(proceeds - basis),
      shortTermGain: sum((c) => c.gain, (c) => c.term === "SHORT"),
      longTermGain: sum((c) => c.gain, (c) => c.term === "LONG"),
      consumed: consumed,
    };
  }

  function unrealisedLots(lots, price, asOf) {
    return lots.map(function (lot) {
      const value = r2(price * lot.shares);
      const cost = r2(lot.costPerShare * lot.shares);
      return { lot: lot, marketValue: value, gain: r2(value - cost), term: termFor(lot.acquired, asOf) };
    });
  }

  function harvestCandidates(lots, price, asOf, minLoss) {
    const floor = minLoss == null ? 1000 : minLoss;
    return unrealisedLots(lots, price, asOf)
      .filter((u) => -u.gain >= floor)
      .sort((a, b) => a.gain - b.gain);
  }

  function applySale(lots, shares, method, lotIds) {
    const taken = {};
    pickLots(lots, shares, method, lotIds).forEach(function (p) { taken[p[0].id] = p[1]; });
    const out = [];
    lots.forEach(function (lot) {
      const n = taken[lot.id];
      if (n == null) { out.push(lot); return; }
      const left = lot.shares - n;
      if (left > 0) out.push(Object.assign({}, lot, { shares: left }));
    });
    return out;
  }

  // ------------------------------------------------------------------ rebalancer

  /** Rupee target per symbol, proportional to weight, none over the cap. */
  function cappedTargets(weights, investable, cap) {
    const out = {};
    const active = Object.assign({}, weights);
    let left = investable;
    while (Object.keys(active).length) {
      const wsum = Object.keys(active).reduce((a, k) => a + active[k], 0);
      if (!(wsum > 0)) break;
      const trial = {};
      Object.keys(active).forEach((k) => { trial[k] = r2(left * active[k] / wsum); });
      const over = Object.keys(trial).filter((k) => trial[k] > cap + 1e-9);
      if (!over.length) { Object.assign(out, trial); break; }
      over.forEach(function (k) { out[k] = cap; left -= cap; delete active[k]; });
      if (left <= 1e-9) { Object.keys(active).forEach((k) => { out[k] = 0; }); break; }
    }
    return out;
  }

  /**
   * Measure drift from target weights and propose only the trades that fix the outliers.
   * Positions inside the band are left alone: churning them costs brokerage and STT for
   * no change in exposure. Sells are planned first so their cash funds the buys.
   */
  function rebalance(opts) {
    const rules = withDefaults(opts.rules);
    const bandPct = opts.bandPct == null ? 5 : opts.bandPct;
    const cash = opts.cash || 0;
    const held = {};
    (opts.holdings || []).forEach(function (h) {
      if (h.shares > 0) held[String(h.symbol).toUpperCase()] = h.shares;
    });
    const weights = {};
    Object.keys(opts.targetWeights || {}).forEach(function (k) {
      const w = opts.targetWeights[k];
      if (w > 0) weights[k.toUpperCase()] = w;
    });
    const prices = {};
    Object.keys(opts.prices || {}).forEach((k) => { prices[k.toUpperCase()] = opts.prices[k]; });

    const universe = Array.from(new Set(Object.keys(held).concat(Object.keys(weights))))
      .sort((a, b) => (weights[b] || 0) - (weights[a] || 0) || (a < b ? -1 : a > b ? 1 : 0));
    const missing = universe.filter((s) => !(prices[s] > 0));
    if (missing.length) throw new Error("no price for " + missing.join(", "));

    const value = {};
    universe.forEach((s) => { value[s] = r2(prices[s] * (held[s] || 0)); });
    const total = universe.reduce((a, s) => a + value[s], cash);
    if (!(total > 0)) throw new Error("the account is empty: nothing to rebalance");

    const buffer = r2(total * rules.cashBufferPct / HUNDRED);
    const cap = r2(total * rules.maxPositionPct / HUNDRED);
    const targets = cappedTargets(weights, total - buffer, cap);

    const rows = [], sells = [], buys = [], notes = [], whyOf = {};
    universe.forEach(function (sym) {
      const tracked = Object.prototype.hasOwnProperty.call(weights, sym);
      const tgt = targets[sym] || 0;
      const curPct = r2(value[sym] / total * HUNDRED);
      const tgtPct = r2(tgt / total * HUNDRED);
      const drift = r2(curPct - tgtPct);
      let action = "hold";
      if (!tracked) {
        if (opts.sellUntracked && (held[sym] || 0) > 0) {
          sells.push({ sym: sym, rupees: value[sym], all: true });
          whyOf[sym] = "not in your targets; selling the whole position";
          action = "sell all";
        } else {
          action = "untracked, left alone";
        }
      } else if (Math.abs(drift) > bandPct) {
        const delta = r2(tgt - value[sym]);
        whyOf[sym] = "drifted " + drift.toFixed(1) + " points from its " + tgtPct.toFixed(1) +
          "% target (band " + bandPct + ")";
        if (delta < 0) { sells.push({ sym: sym, rupees: -delta, all: false }); action = "sell"; }
        else { buys.push({ sym: sym, rupees: delta }); action = "buy"; }
      }
      rows.push({
        symbol: sym, shares: held[sym] || 0, price: prices[sym], value: value[sym],
        currentPct: curPct, targetPct: tgtPct, drift: drift, targetValue: tgt,
        tracked: tracked, action: action,
      });
    });

    const trades = [];
    let cashAfter = cash;
    sells.forEach(function (s) {
      const price = prices[s.sym];
      let rupees = s.rupees;
      if (rules.maxOrderInr != null && !s.all && rupees > rules.maxOrderInr) {
        notes.push(s.sym + " sell cut to your max order; run again after it fills");
        rupees = rules.maxOrderInr;
      }
      const qty = s.all ? (held[s.sym] || 0) : Math.min(Math.floor(rupees / price + 1e-9), held[s.sym] || 0);
      const gross = r2(qty * price);
      if (qty <= 0 || gross < rules.minOrderInr) {
        notes.push(s.sym + " sell is under your minimum order: skipped");
        return;
      }
      cashAfter = r2(cashAfter + gross);
      trades.push({ symbol: s.sym, isBuy: false, quantity: qty, price: price, value: gross,
        why: whyOf[s.sym] || "rebalance" });
    });

    const spendable = Math.max(0, r2(cashAfter - buffer));
    const wanted = buys.reduce((a, b) => a + b.rupees, 0);
    let scale = 1;
    if (wanted > spendable && wanted > 0) {
      scale = spendable / wanted;
      notes.push("buys scaled to " + Math.floor(scale * 100) +
        "%: only that much is free above your cash buffer");
    }
    buys.forEach(function (b) {
      const price = prices[b.sym];
      let rupees = b.rupees * scale;
      if (rules.maxOrderInr != null && rupees > rules.maxOrderInr) {
        notes.push(b.sym + " buy cut to your max order; run again after it fills");
        rupees = rules.maxOrderInr;
      }
      const qty = Math.floor(rupees / price + 1e-9);
      const gross = r2(qty * price);
      if (qty < 1 || gross < rules.minOrderInr) {
        notes.push(b.sym + " buy does not reach one whole share or your minimum order: skipped");
        return;
      }
      cashAfter = r2(cashAfter - gross);
      trades.push({ symbol: b.sym, isBuy: true, quantity: qty, price: price, value: gross,
        why: whyOf[b.sym] || "rebalance" });
    });

    return { totalValue: r2(total), cash: r2(cash), cashBuffer: buffer,
      rows: rows, trades: trades, notes: notes };
  }

  // ------------------------------------------------------------------ metric screener

  const SCREEN_OPS = [">", ">=", "<", "<=", "==", "!=", "between", "in", "not in"];

  function fmtMetric(v) {
    return Number.isInteger(v) ? String(v) : v.toFixed(2);
  }

  /** Evaluate one rule {metric, op, value}. passed === null means the metric was absent. */
  function checkRule(company, rule) {
    const have = (company.metrics || {})[rule.metric];
    if (have == null || have === "") return { rule: rule, passed: null, reason: rule.metric + " is missing" };

    if (rule.op === "in" || rule.op === "not in") {
      const bag = (rule.value || []).map((x) => String(x).toUpperCase());
      const inside = bag.indexOf(String(have).toUpperCase()) >= 0;
      const ok = rule.op === "in" ? inside : !inside;
      return { rule: rule, passed: ok,
        reason: rule.metric + " " + have + (inside ? " is in " : " is not in ") + bag.join(", ") };
    }

    const num = typeof have === "number" ? have : (typeof have === "string" && have.trim() !== "" && !isNaN(+have) ? +have : null);
    if (num === null) {
      if (rule.op === "==" || rule.op === "!=") {
        const same = String(have).toUpperCase() === String(rule.value).toUpperCase();
        const ok = rule.op === "==" ? same : !same;
        return { rule: rule, passed: ok, reason: rule.metric + " " + have + " " + rule.op + " " + rule.value };
      }
      return { rule: rule, passed: false, reason: rule.metric + " is '" + have + "', not a number" };
    }

    if (rule.op === "between") {
      const lo = rule.value[0], hi = rule.value[1];
      const ok = num >= lo && num <= hi;
      return { rule: rule, passed: ok,
        reason: rule.metric + " " + fmtMetric(num) + (ok ? " is within " : " is outside ") +
          fmtMetric(lo) + " to " + fmtMetric(hi) };
    }

    const target = +rule.value;
    const ok = rule.op === ">" ? num > target : rule.op === ">=" ? num >= target
      : rule.op === "<" ? num < target : rule.op === "<=" ? num <= target
      : rule.op === "==" ? num === target : rule.op === "!=" ? num !== target : false;
    return { rule: rule, passed: ok,
      reason: ok ? rule.metric + " " + fmtMetric(num) + " " + rule.op + " " + fmtMetric(target)
        : rule.metric + " " + fmtMetric(num) + ", needs " + rule.op + " " + fmtMetric(target) };
  }

  /** Run every rule over every company; passing ones first, ranked and stable. */
  function screen(companies, rules, missingPolicy, rankBy) {
    const policy = missingPolicy || "FAIL";
    const keys = rankBy || [];
    const results = (companies || []).map(function (c) {
      const checks = (rules || []).map((r) => checkRule(c, r));
      const failed = checks.some((k) => k.passed === false) ||
        (policy === "FAIL" && checks.some((k) => k.passed === null));
      const score = keys.length ? keys.reduce(function (a, k) {
        const v = (c.metrics || {})[k];
        return a + (typeof v === "number" ? v : (typeof v === "string" && !isNaN(+v) ? +v : 0));
      }, 0) : null;
      return {
        ticker: c.ticker, name: c.name || null, passed: !failed, checks: checks, score: score,
        failures: checks.filter((k) => k.passed === false),
        skipped: checks.filter((k) => k.passed === null),
      };
    });
    results.sort(function (a, b) {
      if (a.passed !== b.passed) return a.passed ? -1 : 1;
      if ((b.score || 0) !== (a.score || 0)) return (b.score || 0) - (a.score || 0);
      if (a.failures.length !== b.failures.length) return a.failures.length - b.failures.length;
      return a.ticker < b.ticker ? -1 : a.ticker > b.ticker ? 1 : 0;
    });
    return results;
  }

  // ------------------------------------------------------------------ exports

  return {
    DEFAULT_RULES: DEFAULT_RULES,
    DAILY_SETUPS: DAILY_SETUPS,
    MIN_DAILY_CLOSES: MIN_DAILY_CLOSES,
    SESSION_BARS: SESSION_BARS,
    withDefaults: withDefaults,
    planExit: planExit,
    sizePosition: sizePosition,
    checkRisk: checkRisk,
    wilderRsi: wilderRsi,
    findDailySetups: findDailySetups,
    openingRange: openingRange,
    couldBreakOut: couldBreakOut,
    openingRangeBreakout: openingRangeBreakout,
    buildPool: buildPool,
    monteCarlo: monteCarlo,
    historyReplay: historyReplay,
    termFor: termFor,
    pickLots: pickLots,
    simulateSale: simulateSale,
    unrealisedLots: unrealisedLots,
    harvestCandidates: harvestCandidates,
    applySale: applySale,
    cappedTargets: cappedTargets,
    rebalance: rebalance,
    SCREEN_OPS: SCREEN_OPS,
    checkRule: checkRule,
    screen: screen,
  };
});
