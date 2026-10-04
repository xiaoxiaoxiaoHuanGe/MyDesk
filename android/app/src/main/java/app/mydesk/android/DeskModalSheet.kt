package app.mydesk.android

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Pull the header or unused downward body scroll; inertial scrolling never settles the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeskModalSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    dragDismissEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope=rememberCoroutineScope()
    val density=LocalDensity.current
    val configuration=LocalConfiguration.current
    val closeDistance by rememberUpdatedState(with(density) {72.dp.toPx()})
    val maxDistance by rememberUpdatedState(with(density) {configuration.screenHeightDp.coerceAtLeast(72).dp.toPx()})
    val enabled by rememberUpdatedState(dragDismissEnabled)
    val dismiss by rememberUpdatedState(onDismissRequest)
    var dragOffset by remember {mutableFloatStateOf(0f)}
    var closing by remember {mutableStateOf(false)}
    var motion by remember {mutableStateOf<Job?>(null)}

    fun returnToRest() {
        if(closing) return
        motion?.cancel()
        motion=scope.launch {
            animate(dragOffset,0f,animationSpec=tween(180)) {value,_->dragOffset=value}
        }
    }
    fun closeSheet() {
        if(!enabled || closing) return
        motion?.cancel()
        closing=true
        motion=scope.launch {
            sheetState.hide()
            if(!sheetState.isVisible) dismiss()
            else {closing=false;dragOffset=0f}
        }
    }

    fun finishPull() {
        if(enabled && !closing && dragOffset>=closeDistance) closeSheet()
        else returnToRest()
    }
    val bodyScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if(source!=NestedScrollSource.UserInput || !enabled || closing || dragOffset<=0f) return Offset.Zero
                motion?.cancel()
                val before=dragOffset
                dragOffset=(before+available.y).coerceIn(0f,maxDistance)
                return Offset(0f,dragOffset-before)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // A child consumes downward motion first. Only its remainder at the top pulls us.
                if(source!=NestedScrollSource.UserInput || !enabled || closing || available.y<=0f) return Offset.Zero
                motion?.cancel()
                val before=dragOffset
                dragOffset=(before+available.y).coerceIn(0f,maxDistance)
                return Offset(0f,dragOffset-before)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if(dragOffset<=0f || closing) return Velocity.Zero
                finishPull()
                return Velocity(0f,available.y)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.offset {IntOffset(0,dragOffset.roundToInt())}.nestedScroll(bodyScroll),
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = null,
    ) {
        // Keep this outside Material's dragHandle slot, whose parent adds its own click handler.
        Box(Modifier.fillMaxWidth().height(48.dp).semantics(mergeDescendants=true) {
            contentDescription="下拉关闭窗口"
            if(!dragDismissEnabled || closing) disabled()
            else onClick("关闭窗口") {closeSheet();true}
        }.pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart={if(enabled && !closing) motion?.cancel()},
                onVerticalDrag={change,amount->
                    if(enabled && !closing) {
                        change.consume()
                        dragOffset=(dragOffset+amount).coerceIn(0f,maxDistance)
                    }
                },
                onDragEnd={finishPull()},
                onDragCancel={returnToRest()},
            )
        },contentAlignment=Alignment.Center) {BottomSheetDefaults.DragHandle()}
        content()
    }
}
