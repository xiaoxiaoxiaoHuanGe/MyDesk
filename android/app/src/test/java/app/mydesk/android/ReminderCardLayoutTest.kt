package app.mydesk.android

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class,qualifiers="w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReminderCardLayoutTest {
    @get:Rule val compose=createComposeRule()
    private val reminder=Reminder("one","吃饭","2030-10-28T11:21:00Z","pending","1")

    @Test fun compactCardPlacesMetadataAboveTitleAndKeepsSeparateAccessibleActions() {
        var opened=0;var completed=0
        compose.setContent {
            MaterialTheme(colorScheme=deskColors(false),shapes=deskShapes,typography=deskTypography) {
                Column(Modifier.width(324.dp)) {
                    ReminderCard(reminder,"Asia/Shanghai",false,detail={opened++}) {action,_->if(action=="complete") completed++}
                }
            }
        }
        val title=compose.onNodeWithText("吃饭").fetchSemanticsNode().boundsInRoot
        val badge=compose.onNodeWithText("待提醒").fetchSemanticsNode().boundsInRoot
        assertTrue("Metadata belongs above the title",badge.bottom<=title.top)
        compose.onNodeWithContentDescription("查看提醒详情").performClick()
        assertEquals(1,opened);assertEquals(0,completed)
        compose.onNodeWithContentDescription("完成提醒").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        assertEquals(1,completed);assertEquals(1,opened)
        val card=compose.onNodeWithText("吃饭").fetchSemanticsNode().parent!!.boundsInRoot
        val density=RuntimeEnvironment.getApplication().resources.displayMetrics.density
        assertTrue("Single-line reminders should fit within 120dp",card.height/density<=120f)
    }

    @Test @Config(fontScale=2f) fun longTitleAndLargeTextKeepBothActionsUsableWhileCompletionIsBusy() {
        compose.setContent {
            MaterialTheme(colorScheme=deskColors(true),shapes=deskShapes,typography=deskTypography) {
                Column(Modifier.width(324.dp).verticalScroll(rememberScrollState())) {
                    ReminderCard(reminder.copy(title="一条需要完整查看的很长的提醒事项标题"),"Asia/Shanghai",true,detail={}) {_,_->fail("Busy completion must be disabled")}
                }
            }
        }
        compose.onNodeWithContentDescription("查看提醒详情").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("完成提醒").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("一条需要完整查看的很长的提醒事项标题").assertExists()
        compose.onNodeWithText("10-28 19:21").assertExists()
    }
}
