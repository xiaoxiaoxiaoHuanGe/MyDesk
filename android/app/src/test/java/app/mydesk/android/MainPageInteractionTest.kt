package app.mydesk.android

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class,qualifiers="w400dp-h900dp",fontScale=1f)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainPageInteractionTest {
    @get:Rule val compose=createComposeRule()
    private var settled=0
    private var listIndex=0
    private var listOffset=0
    private val enabled=mutableStateOf(true)

    private fun showPages() {
        compose.setContent {
            MaterialTheme(colorScheme=deskColors(false),shapes=deskShapes,typography=deskTypography) {
                val pager=rememberPagerState(pageCount={4})
                val scope=rememberCoroutineScope()
                val lists=listOf(rememberLazyListState(),rememberLazyListState(),rememberLazyListState(),rememberLazyListState())
                val finishedPage=pager.settledPage
                val firstIndex=lists[0].firstVisibleItemIndex
                val firstOffset=lists[0].firstVisibleItemScrollOffset
                SideEffect {settled=finishedPage;listIndex=firstIndex;listOffset=firstOffset}
                Column(Modifier.width(360.dp).height(720.dp)) {
                    DeskMainPages(pager,Modifier.weight(1f).fillMaxWidth().testTag("pages"),enabled.value) {page->
                        LazyColumn(Modifier.fillMaxSize().testTag("list$page"),state=lists[page],contentPadding=PaddingValues(18.dp)) {
                            item {Text("页面$page")}
                            if(page==1) item {ReminderForm(false,"Asia/Shanghai") {_,_->fail("UI fixture must not create reminders")}}
                            items(24) {Text("内容$page-$it",Modifier.fillMaxWidth().height(64.dp))}
                        }
                    }
                    DeskMainTabs(pager.currentPage,{scope.launch {pager.animateScrollToPage(it)}})
                }
            }
        }
    }
    private fun swipe(left: Boolean) {
        compose.onNodeWithTag("pages").performTouchInput {if(left) swipeLeft() else swipeRight()}
        compose.waitForIdle()
    }
    private fun assertPage(page: Int) {
        compose.runOnIdle {assertEquals(page,settled)}
        compose.onNodeWithText(listOf("工作台","提醒","步数","设置")[page]).assertIsSelected()
    }

    @Test fun swipesAndBottomNavigationStayInSyncWithoutWrapping() {
        showPages();assertPage(0)
        swipe(false);assertPage(0)
        swipe(true);assertPage(1)
        swipe(true);assertPage(2)
        swipe(true);assertPage(3)
        swipe(true);assertPage(3)
        swipe(false);assertPage(2)
        swipe(false);assertPage(1)
        swipe(false);assertPage(0)
        compose.onNodeWithText("设置").performClick();compose.waitForIdle();assertPage(3)
        compose.onNodeWithText("工作台").performClick();compose.waitForIdle();assertPage(0)
    }

    @Test fun verticalScrollAndReminderDraftSurvivePageChanges() {
        showPages()
        compose.onNodeWithTag("list0").performTouchInput {swipeUp()}
        compose.waitForIdle();assertPage(0)
        var index=0;var offset=0
        compose.runOnIdle {index=listIndex;offset=listOffset;assertTrue(index>0 || offset>0)}
        swipe(true);assertPage(1)
        compose.onNode(hasText("提醒事项") and hasSetTextAction()).performTextInput("保留提醒草稿")
        val minuteWheel=compose.onNodeWithContentDescription("分钟选择")
        minuteWheel.performSemanticsAction(SemanticsActions.SetProgress) {it(30f)}
        compose.waitForIdle()
        val beforeMinute=minuteWheel.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        minuteWheel.performTouchInput {swipeUp()}
        compose.waitForIdle();assertPage(1)
        val selectedMinute=minuteWheel.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertNotEquals(beforeMinute,selectedMinute)
        swipe(true);assertPage(2)
        compose.onNodeWithText("工作台").performClick();compose.waitForIdle();assertPage(0)
        compose.runOnIdle {assertEquals(index,listIndex);assertEquals(offset,listOffset)}
        swipe(true);assertPage(1)
        compose.onNode(hasSetTextAction() and hasText("保留提醒草稿")).assertExists()
        assertEquals(selectedMinute,compose.onNodeWithContentDescription("分钟选择").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }

    @Test fun disabledSwipingAndShortDragDoNotChangePages() {
        showPages()
        compose.runOnIdle {enabled.value=false}
        swipe(true);assertPage(0)
        compose.onNodeWithText("设置").performClick();compose.waitForIdle();assertPage(3)
        compose.runOnIdle {enabled.value=true}
        compose.onNodeWithTag("pages").performTouchInput {
            swipe(center,center.copy(x=center.x+35f),durationMillis=600)
        }
        compose.waitForIdle();assertPage(3)
    }
}
