package com.example.ui.screens.heatmaps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.market.MarketIntelligenceRepository
import com.example.data.repository.MarketDataRepository
import com.example.domain.engine.RiskMath
import com.example.domain.market.CryptoMarketAsset
import com.example.domain.market.HeatmapAssetNode
import com.example.domain.market.HeatmapMarketType
import com.example.domain.market.HeatmapScaleMetric
import com.example.domain.market.LiveFundamentalDetails
import com.example.domain.market.StockMarketSnapshot
import com.example.domain.model.MarketSegment
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class HeatmapsUiState(
    val marketType: HeatmapMarketType = HeatmapMarketType.NSE,
    val scaleMetric: HeatmapScaleMetric = HeatmapScaleMetric.MARKET_CAP,
    val selectedGroup: String? = null,
    val availableGroups: List<String> = emptyList(),
    val nodes: List<HeatmapAssetNode> = emptyList(),
    val advancingCount: Int = 0,
    val decliningCount: Int = 0,
    val avgChangePercent: Double = 0.0,
    val topGainer: HeatmapAssetNode? = null,
    val topLoser: HeatmapAssetNode? = null,
    val selectedNode: HeatmapAssetNode? = null,
    val selectedFundamentalStock: StockMarketSnapshot? = null,
    val selectedFundamentalDetails: LiveFundamentalDetails? = null,
    val isLoadingFundamentalDetails: Boolean = false,
    val isRefreshing: Boolean = false,
    val lastSyncLabel: String = ""
)

@HiltViewModel
class HeatmapsViewModel @Inject constructor(
    private val intelligenceRepository: MarketIntelligenceRepository,
    private val marketDataRepository: MarketDataRepository
) : ViewModel() {

    private val _marketType = MutableStateFlow(HeatmapMarketType.NSE)
    private val _scaleMetric = MutableStateFlow(HeatmapScaleMetric.MARKET_CAP)
    private val _selectedGroup = MutableStateFlow<String?>(null)
    private val _selectedNodeId = MutableStateFlow<String?>(null)
    private val _fundamentalSymbol = MutableStateFlow<String?>(null)
    private val _fundamentalDetailsMap = MutableStateFlow<Map<String, LiveFundamentalDetails>>(emptyMap())
    private val _isLoadingFundamentalDetails = MutableStateFlow(false)

    private val selectionFlow = combine(
        _marketType,
        _scaleMetric,
        _selectedGroup,
        _selectedNodeId,
        _fundamentalSymbol
    ) { market, scale, group, nodeId, fundSym ->
        SelectionState(market, scale, group, nodeId, fundSym)
    }

    private val dataFlow = combine(
        intelligenceRepository.stocks,
        intelligenceRepository.cryptoAssets,
        intelligenceRepository.isRefreshing,
        intelligenceRepository.lastSyncLabel
    ) { stocks, crypto, refreshing, syncLabel ->
        MarketDataBundle(stocks, crypto, refreshing, syncLabel)
    }

    val uiState: StateFlow<HeatmapsUiState> = combine(
        selectionFlow,
        dataFlow,
        _fundamentalDetailsMap,
        _isLoadingFundamentalDetails
    ) { sel, data, detailsMap, loadingDetails ->
        val nseNodes = data.stocks.map { it.toHeatmapNode() }
        val cryptoNodes = data.crypto.map { it.toHeatmapNode() }

        val baseNodes = when (sel.marketType) {
            HeatmapMarketType.NSE -> nseNodes
            HeatmapMarketType.CRYPTO -> cryptoNodes
            HeatmapMarketType.COMBINED -> nseNodes + cryptoNodes
        }

        val groups = baseNodes.map { it.groupLabel }.distinct().sorted()
        val activeGroup = sel.selectedGroup?.takeIf { it in groups }
        val visibleNodes = if (activeGroup == null) {
            baseNodes
        } else {
            baseNodes.filter { it.groupLabel == activeGroup }
        }

        val advancing = visibleNodes.count { it.changePercent >= 0.0 }
        val declining = visibleNodes.size - advancing
        val avgChg = if (visibleNodes.isNotEmpty()) {
            visibleNodes.map { it.changePercent }.average()
        } else 0.0

        val selectedNode = sel.selectedNodeId?.let { id ->
            baseNodes.firstOrNull { it.id == id }
        }
        val fundStock = sel.fundamentalSymbol?.let { sym ->
            data.stocks.firstOrNull { it.symbol.equals(sym, ignoreCase = true) }
        }

        HeatmapsUiState(
            marketType = sel.marketType,
            scaleMetric = sel.scaleMetric,
            selectedGroup = activeGroup,
            availableGroups = groups,
            nodes = visibleNodes,
            advancingCount = advancing,
            decliningCount = declining,
            avgChangePercent = avgChg,
            topGainer = visibleNodes.maxByOrNull { it.changePercent },
            topLoser = visibleNodes.minByOrNull { it.changePercent },
            selectedNode = selectedNode,
            selectedFundamentalStock = fundStock,
            selectedFundamentalDetails = fundStock?.let { detailsMap[it.symbol.uppercase(Locale.US)] },
            isLoadingFundamentalDetails = loadingDetails,
            isRefreshing = data.isRefreshing,
            lastSyncLabel = data.lastSyncLabel
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HeatmapsUiState()
    )

    init {
        viewModelScope.launch {
            marketDataRepository.instruments.collect { liveList ->
                intelligenceRepository.mergeWatchlistInstruments(liveList)
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            intelligenceRepository.refreshAll()
        }
    }

    fun selectMarketType(type: HeatmapMarketType) {
        _marketType.value = type
        _selectedGroup.value = null
        _selectedNodeId.value = null
    }

    fun selectScaleMetric(metric: HeatmapScaleMetric) {
        _scaleMetric.value = metric
    }

    fun selectGroup(group: String?) {
        _selectedGroup.value = group
    }

    fun selectNode(node: HeatmapAssetNode) {
        _selectedNodeId.value = node.id
    }

    fun dismissNode() {
        _selectedNodeId.value = null
    }

    fun openFundamentals(symbol: String) {
        _selectedNodeId.value = null
        _fundamentalSymbol.value = symbol
        loadFundamentalDetails(symbol, forceRefresh = false)
    }

    fun refreshFundamentalDetails() {
        val sym = _fundamentalSymbol.value ?: return
        loadFundamentalDetails(sym, forceRefresh = true)
    }

    private fun loadFundamentalDetails(symbol: String, forceRefresh: Boolean) {
        val snap = intelligenceRepository.findSnapshotBySymbol(symbol) ?: return
        val key = snap.symbol.uppercase(Locale.US)
        if (!forceRefresh && _fundamentalDetailsMap.value.containsKey(key)) return
        viewModelScope.launch {
            _isLoadingFundamentalDetails.value = true
            try {
                val details = intelligenceRepository.fetchFundamentalDetails(snap, forceRefresh = forceRefresh)
                _fundamentalDetailsMap.value = _fundamentalDetailsMap.value + (key to details)
            } finally {
                _isLoadingFundamentalDetails.value = false
            }
        }
    }

    fun closeFundamentals() {
        _fundamentalSymbol.value = null
    }

    fun prepareAndNavigate(node: HeatmapAssetNode, onReady: (String) -> Unit) {
        viewModelScope.launch {
            if (node.isCrypto) {
                val asset = intelligenceRepository.cryptoAssets.value
                    .firstOrNull { it.symbol.equals(node.symbol, ignoreCase = true) }
                if (asset != null) {
                    intelligenceRepository.ensureTradeableCrypto(asset)
                }
            } else {
                val stock = intelligenceRepository.stocks.value
                    .firstOrNull { it.symbol.equals(node.symbol, ignoreCase = true) }
                if (stock != null) {
                    intelligenceRepository.ensureTradeableStock(stock)
                }
            }
            _selectedNodeId.value = null
            _fundamentalSymbol.value = null
            onReady(node.symbol)
        }
    }

    fun prepareStockAndNavigate(stock: StockMarketSnapshot, onReady: (String) -> Unit) {
        viewModelScope.launch {
            intelligenceRepository.ensureTradeableStock(stock)
            _fundamentalSymbol.value = null
            onReady(stock.symbol)
        }
    }

    private fun StockMarketSnapshot.toHeatmapNode(): HeatmapAssetNode {
        // 1 Crore INR = 10,000,000 INR; normalize to USD (~84.5 INR/USD) for Combined NSE + Crypto scale parity
        val capUsd = (profile.marketCapCr * 10_000_000.0) / USD_INR_RATE
        val volUsd = (volume.toDouble() * ltp.toDouble()) / USD_INR_RATE
        return HeatmapAssetNode(
            id = "NSE:$symbol",
            symbol = symbol,
            displaySymbol = symbol,
            name = companyName,
            groupLabel = sector,
            isCrypto = false,
            priceFormatted = RiskMath.formatPrice(ltp, MarketSegment.NSE),
            rawPrice = ltp.toDouble(),
            changePercent = changePercent,
            marketCapNormalizedUsd = capUsd.coerceAtLeast(1_000_000.0),
            volumeNormalizedUsd = volUsd.coerceAtLeast(100_000.0),
            marketCapLabel = formatInrCap(profile.marketCapCr),
            volumeLabel = formatShareVol(volume),
            rsi14 = technical.rsi14,
            maSignal = technical.maCrossover
        )
    }

    private fun CryptoMarketAsset.toHeatmapNode(): HeatmapAssetNode {
        val priceStr = when {
            priceUsd >= 1000.0 -> String.format(Locale.US, "$%,.0f", priceUsd)
            priceUsd >= 10.0 -> String.format(Locale.US, "$%.2f", priceUsd)
            priceUsd >= 1.0 -> String.format(Locale.US, "$%.3f", priceUsd)
            else -> String.format(Locale.US, "$%.4f", priceUsd)
        }
        return HeatmapAssetNode(
            id = "CRYPTO:$symbol",
            symbol = symbol,
            displaySymbol = ticker,
            name = name,
            groupLabel = category,
            isCrypto = true,
            priceFormatted = priceStr,
            rawPrice = priceUsd,
            changePercent = changePercent24h,
            marketCapNormalizedUsd = marketCapUsd.coerceAtLeast(1_000_000.0),
            volumeNormalizedUsd = volume24hUsd.coerceAtLeast(100_000.0),
            marketCapLabel = formatUsdCompact(marketCapUsd),
            volumeLabel = formatUsdCompact(volume24hUsd),
            rsi14 = null,
            maSignal = null
        )
    }

    private data class SelectionState(
        val marketType: HeatmapMarketType,
        val scaleMetric: HeatmapScaleMetric,
        val selectedGroup: String?,
        val selectedNodeId: String?,
        val fundamentalSymbol: String?
    )

    private data class MarketDataBundle(
        val stocks: List<StockMarketSnapshot>,
        val crypto: List<CryptoMarketAsset>,
        val isRefreshing: Boolean,
        val lastSyncLabel: String
    )

    private companion object {
        const val USD_INR_RATE = 84.5

        fun formatInrCap(cr: Double): String = if (cr >= 100_000.0) {
            String.format(Locale.US, "₹%.2fL Cr", cr / 100_000.0)
        } else {
            String.format(Locale.US, "₹%.0f Cr", cr)
        }

        fun formatShareVol(vol: Long): String = when {
            vol >= 10_000_000L -> String.format(Locale.US, "%.1fM sh", vol / 1_000_000.0)
            vol >= 1_000_000L -> String.format(Locale.US, "%.2fM sh", vol / 1_000_000.0)
            else -> String.format(Locale.US, "%.0fK sh", vol / 1_000.0)
        }

        fun formatUsdCompact(usd: Double): String = when {
            usd >= 1_000_000_000_000.0 -> String.format(Locale.US, "$%.2fT", usd / 1_000_000_000_000.0)
            usd >= 1_000_000_000.0 -> String.format(Locale.US, "$%.1fB", usd / 1_000_000_000.0)
            usd >= 1_000_000.0 -> String.format(Locale.US, "$%.0fM", usd / 1_000_000.0)
            else -> String.format(Locale.US, "$%.0f", usd)
        }
    }
}
