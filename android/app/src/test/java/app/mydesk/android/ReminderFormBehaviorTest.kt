package app.mydesk.android

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class,qualifiers="w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReminderFormBehaviorTest {
    @get:Rule val compose=createComposeRule()

    @Test @Config(fontScale=2f,qualifiers="w360dp-h640dp") fun repeatConfirmationRemainsReachableOnSmallScreensWithLargeText() {
        var chosen: List<Int>?=null
        compose.setContent {MaterialTheme {ReminderRepeatPicker(listOf(1,3,5),{}, {chosen=it})}}
        compose.onNodeWithText("确定").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {assertEquals(listOf(1,3,5),chosen)}
    }

    @Test fun reenteringResetsClockButRecompositionPreservesTheSelection() {
        var active by mutableStateOf(true)
        var busy by mutableStateOf(false)
        compose.setContent {MaterialTheme {Column(Modifier.height(760.dp).verticalScroll(rememberScrollState())) {ReminderForm(busy,"Asia/Shanghai",active=active) {_,_->}}}}
        val wheel=compose.onNodeWithContentDescription("分钟选择")
        val target=(java.time.Instant.now().atZone(java.time.ZoneId.of("Asia/Shanghai")).minute+20)%60
        wheel.performSemanticsAction(SemanticsActions.SetProgress) {it(target.toFloat())}
        compose.runOnIdle {busy=true}
        wheel.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"$target 分钟"))
        compose.runOnIdle {active=false}
        compose.runOnIdle {active=true;busy=false}
        val minute=java.time.Instant.now().plusSeconds(60).atZone(java.time.ZoneId.of("Asia/Shanghai")).minute
        wheel.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"$minute 分钟"))
    }

    @Test fun selectedRepeatDaysAreSubmittedAndCountdownRemainsSingleUse() {
        var repeat: ReminderRepeat?=null
        compose.setContent {MaterialTheme {Column(Modifier.height(760.dp).verticalScroll(rememberScrollState())) {ReminderForm(false,"Asia/Shanghai",createRepeat={_,_,rule->repeat=rule}) {_,_->}}}}
        compose.onNodeWithText("重复 · 仅一次").performScrollTo().performClick()
        compose.onNodeWithText("周一至周五").performClick()
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithContentDescription("日期选择").assertDoesNotExist()
        compose.onNodeWithText("创建提醒").performScrollTo().performClick()
        compose.runOnIdle {assertEquals((1..5).toList(),repeat!!.days)}
        compose.onNodeWithText("切换倒计时").performScrollTo().performClick()
        compose.onNodeWithText("重复 · 周一至周五").assertDoesNotExist()
    }

    @Test fun unnamedReminderCanBeCreated() {
        var name: String?=null
        compose.setContent {MaterialTheme {Column(Modifier.height(760.dp).verticalScroll(rememberScrollState())) {ReminderForm(false,"Asia/Shanghai") {title,_->name=title}}}}
        compose.onNodeWithText("创建提醒").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {assertEquals("提醒",name)}
    }

    @Test fun minutesWrapInBothDirections() {
        compose.setContent {MaterialTheme {Column(Modifier.height(760.dp)) {DeskCountdownPicker(0,59,0,true,{}, {}, {})}}}
        val wheel=compose.onNodeWithContentDescription("分钟选择")
        wheel.performTouchInput {swipeUp(startY=centerY+25f,endY=centerY-35f,durationMillis=800)}
        compose.waitForIdle()
        val value=wheel.fetchSemanticsNode().config[SemanticsProperties.StateDescription].substringBefore(' ').toInt()
        assertTrue("The wheel must cross 59 into the next cycle",value < 10)
    }
}
