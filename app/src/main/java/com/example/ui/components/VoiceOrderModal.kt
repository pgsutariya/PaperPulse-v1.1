package com.example.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.domain.engine.RiskMath
import com.example.domain.model.Instrument
import com.example.domain.model.InstrumentType
import com.example.domain.model.OrderSide
import com.example.domain.model.ProductType
import com.example.domain.voice.ParsedVoiceOrder
import com.example.domain.voice.VoiceOrderCommandParser
import com.example.domain.voice.VoiceOrderParseResult
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import java.math.BigDecimal
import java.util.Locale

sealed interface VoiceOrderExecutionFeedback {
    data object Idle : VoiceOrderExecutionFeedback
    data object Submitting : VoiceOrderExecutionFeedback
    data class Confirmed(val message: String) : VoiceOrderExecutionFeedback
    data class Rejected(val message: String) : VoiceOrderExecutionFeedback
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceOrderBottomSheet(
    instruments: List<Instrument>,
    activeSymbol: String?,
    executionFeedback: VoiceOrderExecutionFeedback,
    onConfirmOrder: (ParsedVoiceOrder) -> Unit,
    onOpenInOrderTicket: (ParsedVoiceOrder) -> Unit,
    onResetFeedback: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val tradingColors = LocalTradingColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var transcript by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var speechStatusMessage by remember {
        mutableStateOf("Tap the microphone and say e.g. \"Buy 5 lots of Reliance\"")
    }

    val speechAvailable = remember(context) {
        SpeechRecognizer.isRecognitionAvailable(context)
    }

    val recognizer = remember(context, speechAvailable) {
        if (speechAvailable) runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() else null
    }

    fun startListening() {
        val sr = recognizer
        if (sr == null) {
            speechStatusMessage = "On-device Speech Recognizer unavailable here — type or tap an example command below."
            return
        }
        onResetFeedback()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                speechStatusMessage = "Listening... Speak your order now"
            }

            override fun onBeginningOfSpeech() {
                speechStatusMessage = "Hearing speech..."
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                isListening = false
                speechStatusMessage = "Processing command..."
            }

            override fun onError(error: Int) {
                isListening = false
                speechStatusMessage = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that. Tap mic to try again or edit below."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Tap mic to retry or type command."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for voice orders."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Speech network unavailable. You can still type or tap an example command."
                    else -> "Voice input paused (code $error). Tap mic to retry or edit below."
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (spoken.isNotBlank()) {
                    transcript = spoken
                    speechStatusMessage = "Heard: \"$spoken\" — review & confirm below"
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (partial.isNotBlank()) {
                    transcript = partial
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        runCatching {
            sr.startListening(intent)
        }.onFailure {
            isListening = false
            speechStatusMessage = "Could not start microphone (${it.message ?: "unavailable"}). Type command below."
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startListening()
        } else {
            speechStatusMessage = "Microphone permission denied. Type or tap a quick command below."
        }
    }

    DisposableEffect(recognizer) {
        onDispose {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
        }
    }

    val parseResult = remember(transcript, instruments, activeSymbol) {
        if (transcript.isBlank()) null else VoiceOrderCommandParser.parse(transcript, instruments, activeSymbol)
    }

    val pulseTransition = rememberInfiniteTransition(label = "voice_pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic_scale"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Voice Command Order",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Speak orders hands-free with live preview & confirmation",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "KEY [V]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Central Mic Trigger + Live Status
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isListening) MaterialTheme.colorScheme.primary else tradingColors.border
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isListening) {
                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .scale(pulseScale)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                            )
                        }
                        Button(
                            onClick = {
                                if (isListening) {
                                    isListening = false
                                    runCatching { recognizer?.stopListening() }
                                    speechStatusMessage = "Stopped listening. Review or edit your command."
                                } else {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        startListening()
                                    } else {
                                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            },
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isListening) tradingColors.bearish else MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier
                                .size(60.dp)
                                .testTag("voice_order_mic_button")
                        ) {
                            Icon(
                                imageVector = if (isListening) Icons.Rounded.GraphicEq else if (speechAvailable) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                                contentDescription = if (isListening) "Stop listening" else "Start voice command",
                                modifier = Modifier.size(28.dp),
                                tint = Color.White
                            )
                        }
                    }

                    Text(
                        text = speechStatusMessage,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Editable Transcript / Command Input
            OutlinedTextField(
                value = transcript,
                onValueChange = {
                    transcript = it
                    if (executionFeedback !is VoiceOrderExecutionFeedback.Idle) onResetFeedback()
                },
                label = { Text("Spoken or Typed Command") },
                placeholder = { Text("e.g. Buy 5 lots of Reliance") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("voice_order_transcript_input")
            )

            // Quick Sample Voice Phrases
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "Buy 5 lots of Reliance",
                    "Sell 2 lots of Nifty",
                    "Buy 10 shares of TCS intraday",
                    "Buy 1 Gold carry forward"
                ).forEach { sample ->
                    AssistChip(
                        onClick = {
                            transcript = sample
                            onResetFeedback()
                        },
                        label = { Text(sample, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            // Parsed Order Preview Card
            when (val res = parseResult) {
                is VoiceOrderParseResult.Failure -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = tradingColors.bearishContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = tradingColors.bearish)
                            Text(res.reason, style = MaterialTheme.typography.bodySmall, color = tradingColors.bearish)
                        }
                    }
                }
                is VoiceOrderParseResult.Success -> {
                    val order = res.order
                    val inst = order.instrument
                    val isBuy = order.side == OrderSide.BUY
                    val sideColor = if (isBuy) tradingColors.bullish else tradingColors.bearish
                    val sideBg = if (isBuy) tradingColors.bullishContainer else tradingColors.bearishContainer
                    val estPrice = order.limitPrice ?: inst.ltp
                    val notional = estPrice.multiply(BigDecimal(order.quantity))
                    val estMargin = if (order.productType == ProductType.INTRADAY && inst.type != InstrumentType.OPTIONS) {
                        notional.multiply(BigDecimal("0.25"))
                    } else {
                        notional
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, sideColor.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("voice_order_preview_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(shape = RoundedCornerShape(6.dp), color = sideBg) {
                                        Text(
                                            text = order.side.name,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = sideColor,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                    Text(
                                        text = inst.symbol,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    SegmentBadge(inst.segment)
                                }
                                Text(
                                    text = RiskMath.formatPrice(estPrice, inst.segment),
                                    style = MonoPriceMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Quantity", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = RiskMath.formatQtyWithLots(order.quantity, inst.lotSize),
                                    style = MonoPriceSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Order / Product", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = "${order.orderType.label} • ${order.productType.label}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            order.stopLossPrice?.let { sl ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Stop Loss", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(RiskMath.formatPrice(sl, inst.segment), style = MonoPriceSmall, color = tradingColors.bearish)
                                }
                            }
                            order.targetPrice?.let { tgt ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Target", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(RiskMath.formatPrice(tgt, inst.segment), style = MonoPriceSmall, color = tradingColors.bullish)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Est. Paper Margin", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(RiskMath.formatCurrency(estMargin, inst.segment), style = MonoPriceSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    // Execution Confirmation Feedback Banner inside the Modal
                    when (executionFeedback) {
                        is VoiceOrderExecutionFeedback.Confirmed -> {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = tradingColors.bullishContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("voice_order_confirmed_banner")
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = tradingColors.bullish)
                                    Text(
                                        text = executionFeedback.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tradingColors.bullish,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                        is VoiceOrderExecutionFeedback.Rejected -> {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = tradingColors.bearishContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("voice_order_rejected_banner")
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = tradingColors.bearish)
                                    Text(
                                        text = executionFeedback.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tradingColors.bearish,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                        else -> Unit
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onOpenInOrderTicket(order) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("voice_order_open_ticket_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Edit Ticket")
                        }

                        Button(
                            onClick = { onConfirmOrder(order) },
                            enabled = executionFeedback !is VoiceOrderExecutionFeedback.Submitting,
                            colors = ButtonDefaults.buttonColors(containerColor = sideColor, contentColor = Color.White),
                            modifier = Modifier
                                .weight(1.35f)
                                .height(48.dp)
                                .testTag("voice_order_confirm_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (executionFeedback is VoiceOrderExecutionFeedback.Submitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Placing...")
                            } else {
                                Text(
                                    text = "Confirm ${order.side.name}",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                null -> Unit
            }
        }
    }
}

@Composable
fun KeyboardHotkeysDialog(
    activeSymbol: String,
    onDismiss: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("hotkeys_dialog"),
        icon = { Icon(Icons.Rounded.Keyboard, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Trading Keyboard Hotkeys", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Active symbol: $activeSymbol (ignored while typing inside input fields)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HotkeyRow("B", "Open BUY order ticket for $activeSymbol", tradingColors.bullish)
                HotkeyRow("S", "Open SELL order ticket for $activeSymbol", tradingColors.bearish)
                HotkeyRow("V", "Open Voice Command order modal", MaterialTheme.colorScheme.primary)
                HotkeyRow("C", "Open Chart & SMC Analysis for $activeSymbol", MaterialTheme.colorScheme.primary)
                HotkeyRow("1 - 5", "Switch tabs (Watchlist, Signals, Positions, Orders, Journal)", MaterialTheme.colorScheme.onSurface)
                HotkeyRow("? or H", "Show / hide this shortcut guide", MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Got it") }
        }
    )
}

@Composable
private fun HotkeyRow(keyLabel: String, description: String, keyColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Text(
                text = keyLabel,
                style = MonoPriceSmall,
                color = keyColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}
