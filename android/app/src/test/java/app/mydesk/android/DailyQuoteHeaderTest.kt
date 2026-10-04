package app.mydesk.android

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DailyQuoteHeaderTest {
    @get:Rule val compose=createComposeRule()
    @Test fun dailyQuoteShowsAttributionAndOpensItsSource() {
        var opened=false
        compose.setContent {MaterialTheme {DeskHeader("MyDesk","山有木兮木有枝。",true,quoteCredit="诗词 · 一言",onQuoteClick={opened=true})}}
        compose.onNodeWithText("山有木兮木有枝。",useUnmergedTree=true).assertExists()
        compose.onNodeWithText("诗词 · 一言",useUnmergedTree=true).assertExists()
        compose.onNode(hasClickAction()).performClick()
        assertTrue(opened)
    }
    @Test fun defaultHeaderKeepsFallbackWithoutAnEmptyAttribution() {
        compose.setContent {MaterialTheme {DeskHeader("MyDesk","把今天的事情，安静地安排好。",true)}}
        compose.onNodeWithText("把今天的事情，安静地安排好。").assertExists()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }
}
