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
class DeskDateTimePickerTest {
    @get:Rule val compose=createComposeRule()

    @Test fun confirmationPreservesTheSelectedCalendarDayAndWorkbenchWallTime() {
        var result: String?=null
        compose.setContent {
            MaterialTheme(colorScheme=deskColors(false),shapes=deskShapes,typography=deskTypography) {
                DeskDateTimePicker("2099-01-01 08:35","Asia/Shanghai",{}) {result=it}
            }
        }
        compose.onNodeWithText("2099-01-01").assertIsDisplayed()
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithText("2099-01-01 · Asia/Shanghai").assertIsDisplayed()
        compose.onNodeWithText("确定").performClick()
        compose.runOnIdle {assertEquals("2099-01-01 08:35",result)}
    }

    @Test fun cancellingTheCalendarDoesNotChangeTheReminderTime() {
        var result: String?=null
        var dismissed=false
        compose.setContent {
            MaterialTheme(colorScheme=deskColors(true),shapes=deskShapes,typography=deskTypography) {
                DeskDateTimePicker("2099-06-15 23:50","UTC",{dismissed=true}) {result=it}
            }
        }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {assertTrue(dismissed);assertNull(result)}
    }
}
