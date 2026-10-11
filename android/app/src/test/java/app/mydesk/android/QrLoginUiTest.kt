package app.mydesk.android

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class,qualifiers="w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QrLoginUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun scanActionAppearsImmediatelyAfterDeviceEdit() {
        var scans=0
        compose.setContent {MaterialTheme {DeviceNameSettings("测试手机",false,{_,_->},"",embedded=true,scan={scans++})}}
        val edit=compose.onNodeWithContentDescription("编辑设备名称").fetchSemanticsNode().boundsInRoot
        val scan=compose.onNodeWithContentDescription("扫一扫").assertHasClickAction().fetchSemanticsNode().boundsInRoot
        assertTrue(scan.left>=edit.right)
        assertTrue(scan.width>=edit.width)
        compose.onNodeWithContentDescription("扫一扫").performClick()
        compose.runOnIdle {assertEquals(1,scans)}
    }
    @Test fun scanIsDisabledWhileAccountOperationIsBusy() {
        compose.setContent {MaterialTheme {DeviceNameSettings("测试手机",true,{_,_->},"",embedded=true)}}
        compose.onNodeWithContentDescription("扫一扫").assertIsNotEnabled()
    }
}
