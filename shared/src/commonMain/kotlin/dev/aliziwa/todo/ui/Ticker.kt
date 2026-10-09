package dev.aliziwa.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import dev.aliziwa.todo.platform.Clock
import kotlinx.coroutines.delay

/**
 * One-second ticker driving countdown chips. Only alive while the UI is composed, so a closed
 * window costs nothing.
 */
@Composable
fun rememberNowMillis(clock: Clock, periodMillis: Long = 1_000L): State<Long> =
    produceState(initialValue = clock.nowEpochMillis(), clock, periodMillis) {
        while (true) {
            value = clock.nowEpochMillis()
            delay(periodMillis)
        }
    }
