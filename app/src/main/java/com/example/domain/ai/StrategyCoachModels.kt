package com.example.domain.ai

enum class AiStrategyCoachPersona(
    val title: String,
    val shortTitle: String,
    val subtitle: String,
    val specialtyBadge: String,
    val focusAreas: List<String>,
    val quickPrompts: List<String>,
    val personaInstruction: String
) {
    INTRADAY_SCALPER(
        title = "Intraday & Renko Coach",
        shortTitle = "Intraday",
        subtitle = "AI Renko Supertrend, Pullback Sniper & tight risk execution",
        specialtyBadge = "SCALP / MIS",
        focusAreas = listOf("Renko Supertrend", "Pullback Sniper", "VWAP/ORB", "15:15 Square-Off"),
        quickPrompts = listOf(
            "Audit active intraday signals and rank the top 2 setups by R:R",
            "How should I tighten Trailing Stop Loss for today's volatility?",
            "Build an NSE pre-15:15 intraday exit checklist for my open trades",
            "How do I avoid false breakouts on 5m Renko & Pullback Sniper?"
        ),
        personaInstruction = """
            You are an elite Intraday & Momentum Trading Coach specializing in NSE/MCX/Crypto short-term execution, AI Renko Supertrend, Pullback Sniper, order-book liquidity awareness, and strict 15:15 IST intraday square-off discipline.
            Give crisp, tactical entry/invalidation/TSL advice with concrete numbers from the user's live portfolio and signals.
        """.trimIndent()
    ),
    SWING_BREAKOUT(
        title = "Swing & Pattern Coach",
        shortTitle = "Swing",
        subtitle = "Chart patterns, Golden Crosses, 52W breakouts & multi-day holds",
        specialtyBadge = "CNC / SWING",
        focusAreas = listOf("Flags & Wedges", "Double Bottoms", "Golden Cross", "52W Breakouts"),
        quickPrompts = listOf(
            "Which active setups have the cleanest multi-day swing R:R?",
            "How should I size a CNC swing trade with trailing targets?",
            "How do I confirm a Flag or Wedge breakout using RSI & volume?",
            "Evaluate sector rotation from today's global cues & FII flows"
        ),
        personaInstruction = """
            You are a seasoned Swing & Chart Pattern Coach specializing in multi-day breakouts (Bull Flags, Wedges, Double Bottoms), EMA20/SMA50/SMA200 crossovers, 52-week high momentum, and trailing profit locks.
            Focus on avoiding premature stops, scaling out at T1/T2, and aligning swings with broader institutional trend.
        """.trimIndent()
    ),
    OPTIONS_HEDGER(
        title = "Options & Greeks Coach",
        shortTitle = "Options",
        subtitle = "NIFTY/BANKNIFTY OI, PCR, IV skew & defined-risk spreads",
        specialtyBadge = "F&O / GREEKS",
        focusAreas = listOf("OI & PCR", "Delta/Theta", "Bull Call / Put Spreads", "VIX Regimes"),
        quickPrompts = listOf(
            "Should I favor option buying or credit spreads at current India VIX?",
            "How do I hedge an open NIFTY or equity position using options?",
            "Explain ideal strike selection (Delta 0.4-0.6) for weekly expiry",
            "Review F&O margin & 15:40 IST intraday expiry risk rules"
        ),
        personaInstruction = """
            You are a quantitative Derivatives & Options Coach specializing in NIFTY, BANKNIFTY, and SENSEX options, Open Interest (OI) shifts, Put-Call Ratio (PCR), Greeks (Delta, Gamma, Theta, Vega), and defined-risk hedging strategies.
            Always emphasize defined max loss, theta decay awareness, and lot-size discipline.
        """.trimIndent()
    ),
    VALUE_FUNDAMENTAL(
        title = "Fundamental & Flow Coach",
        shortTitle = "Fundamentals",
        subtitle = "P/E, ROE/ROCE, quarterly earnings & FII/DII institutional flows",
        specialtyBadge = "VALUE / FLOW",
        focusAreas = listOf("P/E & ROE", "Quarterly PAT", "FII/DII Flows", "Global Macro"),
        quickPrompts = listOf(
            "Interpret today's FII & DII cash flow data for Indian equities",
            "What P/E, ROE, and Debt/Equity thresholds define quality compounders?",
            "How should US/Global indices & GIFT NIFTY shape today's bias?",
            "How do I combine quarterly earnings momentum with RSI oversold?"
        ),
        personaInstruction = """
            You are an institutional Equity Research & Fundamental Strategy Coach specializing in Indian & global macro regimes, FII/DII institutional cash turnover, valuation ratios (P/E, P/B, EV/EBITDA), profitability (ROE, ROCE, OPM), and quarterly earnings quality.
            Connect macro/FII-DII data directly to actionable stock selection and risk discipline.
        """.trimIndent()
    ),
    RISK_PSYCHOLOGY(
        title = "Risk & Journal Coach",
        shortTitle = "Risk & Psych",
        subtitle = "Drawdown guardrails, position sizing & trade journal diagnostics",
        specialtyBadge = "RISK / AUDIT",
        focusAreas = listOf("Position Sizing", "Drawdown Control", "Win-Rate Tuning", "Behavior Audit"),
        quickPrompts = listOf(
            "Audit my recent trade journal for win-rate & profit-factor leaks",
            "Calculate optimal risk-per-trade for my current free margin",
            "Review my open positions for missing Stop Losses or over-leverage",
            "Give me a 3-rule anti-revenge-trading protocol after a red trade"
        ),
        personaInstruction = """
            You are a disciplined Trading Risk & Performance Psychology Coach. Analyze the trader's exact account capital, open exposure, missing SL/Targets, and closed journal history to uncover behavioral leaks, sizing mistakes, and expectancy improvements.
            Be candid, structured, and constructive.
        """.trimIndent()
    )
}

data class StrategyCoachMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val persona: AiStrategyCoachPersona,
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    val timestampMillis: Long = System.currentTimeMillis()
)
