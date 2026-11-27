package com.example.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

data class KeyboardNavigationGuardState(
    val isImeVisible: Boolean = false,
    val focusedInputCount: Int = 0,
    val activeModalCount: Int = 0,
    val isRouteOrModalSuppressed: Boolean = false
) {
    val isInputFocused: Boolean get() = focusedInputCount > 0
    val isOrderModalOpen: Boolean get() = activeModalCount > 0

    /**
     * True whenever the mobile soft keyboard is visible, a price/text field is focused,
     * or an order/risk modification modal is actively open. Prevents tab-switching or
     * view-switching navigation events from interrupting order editing.
     */
    val isNavigationLocked: Boolean
        get() = isImeVisible || isInputFocused || isOrderModalOpen

    /** True whenever single-key hardware hotkeys must be suppressed. */
    val isHotkeysSuppressed: Boolean
        get() = isRouteOrModalSuppressed || isNavigationLocked
}

/**
 * Keyboard-aware navigation and hotkey guard shared across the app shell, modals,
 * order ticket, and SL/Target editors.
 */
class GlobalHotkeyGuard {
    private val focusedInputs = AtomicInteger(0)
    private val activeModals = AtomicInteger(0)

    private val _state = MutableStateFlow(KeyboardNavigationGuardState())
    val state: StateFlow<KeyboardNavigationGuardState> = _state.asStateFlow()

    @Volatile
    var isRouteOrModalSuppressed: Boolean = false
        set(value) {
            field = value
            _state.update { it.copy(isRouteOrModalSuppressed = value) }
        }

    val isSuppressed: Boolean
        get() = _state.value.isHotkeysSuppressed

    val isNavigationLocked: Boolean
        get() = _state.value.isNavigationLocked

    val isImeVisible: Boolean
        get() = _state.value.isImeVisible

    fun setImeVisible(visible: Boolean) {
        if (_state.value.isImeVisible != visible) {
            _state.update { it.copy(isImeVisible = visible) }
        }
    }

    fun acquireInputFocus() {
        val count = focusedInputs.incrementAndGet()
        _state.update { it.copy(focusedInputCount = count) }
    }

    fun releaseInputFocus() {
        val count = focusedInputs.updateAndGet { cur -> if (cur > 0) cur - 1 else 0 }
        _state.update { it.copy(focusedInputCount = count) }
    }

    fun acquireModalLock() {
        val count = activeModals.incrementAndGet()
        _state.update { it.copy(activeModalCount = count) }
    }

    fun releaseModalLock() {
        val count = activeModals.updateAndGet { cur -> if (cur > 0) cur - 1 else 0 }
        _state.update { it.copy(activeModalCount = count) }
    }
}

val LocalGlobalHotkeyGuard = compositionLocalOf<GlobalHotkeyGuard?> { null }

/**
 * Snapshot returned by [rememberKeyboardAwareModalLock] for order and SL/Target modals.
 */
data class KeyboardAwareModalState(
    val isImeVisible: Boolean,
    val isNavigationLocked: Boolean,
    val allowBackdropDismiss: Boolean
)

/**
 * Root-level observer that syncs Compose IME (soft-keyboard) visibility into [GlobalHotkeyGuard]
 * and returns whether the soft keyboard is currently open.
 */
@Composable
fun observeGlobalImeVisibility(guard: GlobalHotkeyGuard): Boolean {
    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0
    SideEffect {
        guard.setImeVisible(isImeVisible)
    }
    return isImeVisible
}

/**
 * Keyboard-aware hook for Order Ticket screens and SL/Target / Order Modification modals.
 * - Registers the modal with [LocalGlobalHotkeyGuard] so tab switches, view switches,
 *   and single-key hotkeys are blocked while the modal or soft keyboard is active.
 * - Disables outside-click backdrop dismissal while the mobile soft keyboard is visible
 *   so tapping near the keyboard edge never prematurely closes the modal.
 */
@Composable
fun rememberKeyboardAwareModalLock(isOpen: Boolean = true): KeyboardAwareModalState {
    val guard = LocalGlobalHotkeyGuard.current
    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0

    SideEffect {
        if (isOpen) {
            guard?.setImeVisible(isImeVisible)
        }
    }

    DisposableEffect(guard, isOpen) {
        if (guard != null && isOpen) {
            guard.acquireModalLock()
            onDispose {
                guard.releaseModalLock()
            }
        } else {
            onDispose { }
        }
    }

    return KeyboardAwareModalState(
        isImeVisible = isImeVisible,
        isNavigationLocked = isOpen || isImeVisible || (guard?.isNavigationLocked == true),
        allowBackdropDismiss = !isImeVisible
    )
}

/**
 * Marks a text field as actively capturing keyboard input while focused so global shortcuts
 * and view-switching events cannot hijack navigation while typing prices, SL, or Target.
 */
fun Modifier.guardGlobalHotkeysOnFocus(): Modifier = composed {
    val guard = LocalGlobalHotkeyGuard.current ?: return@composed this
    var isCurrentlyHeld by remember { mutableStateOf(false) }

    DisposableEffect(guard) {
        onDispose {
            if (isCurrentlyHeld) {
                guard.releaseInputFocus()
                isCurrentlyHeld = false
            }
        }
    }

    this.onFocusChanged { focusState ->
        val focused = focusState.isFocused
        if (focused && !isCurrentlyHeld) {
            guard.acquireInputFocus()
            isCurrentlyHeld = true
        } else if (!focused && isCurrentlyHeld) {
            guard.releaseInputFocus()
            isCurrentlyHeld = false
        }
    }
}
