/*
DrawAnywhere: An Android application that lets you draw on top of other apps.
Copyright (C) 2025 shezik

This program is free software: you can redistribute it and/or modify it under the
terms of the GNU Affero General Public License as published by the Free Software
Foundation, either version 3 of the License, or any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License along
with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.shezik.drawanywhere

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlinx.coroutines.withTimeoutOrNull

enum class StrokeModifier {
    None, PrimaryButton, SecondaryButton, Both
}

// How long the stylus must stay still before the stroke snaps to a straight line.
private const val SNAP_HOLD_DELAY_MS = 500L

// Per-frame step size below which movement is treated as digitizer jitter and does NOT
// reset the hold timer. S-Pen noise is typically ~0.5–1 px/frame; intentional slow
// drawing is usually well above 2 px/frame.
private const val MOVEMENT_THRESHOLD_PX = 2f

fun Modifier.stylusAwareDrawing(
    viewModel: DrawViewModel
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val initialEvent = awaitPointerEvent()
        val initialChange = initialEvent.changes.firstOrNull()

        if (initialChange == null || !initialChange.pressed)
            return@awaitEachGesture

        if (viewModel.uiState.value.stylusOnly && initialChange.type != PointerType.Stylus)
            return@awaitEachGesture

        val strokeModifier = when {
            initialChange.type != PointerType.Stylus -> StrokeModifier.None
            initialEvent.buttons.isPrimaryPressed && initialEvent.buttons.isSecondaryPressed -> StrokeModifier.Both
            initialEvent.buttons.isPrimaryPressed -> StrokeModifier.PrimaryButton
            initialEvent.buttons.isSecondaryPressed -> StrokeModifier.SecondaryButton
            else -> StrokeModifier.None
        }

        viewModel.startStroke(initialChange.position, strokeModifier)
        initialChange.consume()

        // Eraser has no meaningful straight-line semantics.
        val canSnap = viewModel.uiState.value.currentPenType != PenType.StrokeEraser
        var isSnapped = false
        var lastPosition: Offset = initialChange.position

        // Only reset when a frame's step exceeds MOVEMENT_THRESHOLD_PX, so digitizer
        // jitter events don't prevent the hold timer from firing.
        var lastMoveTime = System.currentTimeMillis()

        try {
            while (true) {
                val snapEnabled = canSnap && viewModel.uiState.value.straightLineSnap && !isSnapped

                // Path A — no events arrive for SNAP_HOLD_DELAY_MS: handles devices where
                // the digitizer goes truly silent when held still (touch, mouse, etc.).
                val event = if (snapEnabled) {
                    withTimeoutOrNull(SNAP_HOLD_DELAY_MS) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }

                if (event == null) {
                    viewModel.snapToStraightLine(lastPosition)
                    isSnapped = true
                    continue
                }

                val change = event.changes.firstOrNull { it.id == initialChange.id }
                if (change == null || !change.pressed) break

                if (change.positionChanged()) {
                    val currentPos = change.position

                    // Path B — S-Pen keeps flooding events even when held still: use elapsed
                    // time since last above-threshold step as the hold timer instead.
                    if (distance(currentPos, lastPosition) > MOVEMENT_THRESHOLD_PX)
                        lastMoveTime = System.currentTimeMillis()

                    lastPosition = currentPos

                    if (snapEnabled && System.currentTimeMillis() - lastMoveTime >= SNAP_HOLD_DELAY_MS) {
                        viewModel.snapToStraightLine(currentPos)
                        isSnapped = true
                    }

                    if (isSnapped)
                        viewModel.updateSnappedEndpoint(currentPos)
                    else
                        viewModel.updateStroke(currentPos)

                    change.consume()
                }
            }
        } finally {
            viewModel.finishStroke()
        }
    }
}
