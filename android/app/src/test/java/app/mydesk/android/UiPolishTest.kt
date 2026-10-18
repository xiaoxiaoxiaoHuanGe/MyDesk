package app.mydesk.android

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class,qualifiers="w360dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiPolishTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun capture(name: String) {compose.runOnIdle {
        val view=compose.activity.window.decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val directory=File(System.getProperty("user.home"),"ui-review").apply {mkdirs()}
        File(directory,"polish-$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }}
    @Test fun minuteUpdatesFromTheClockDoNotCountAsManualInput() {
        var minute by mutableIntStateOf(10);var changes=0
        compose.setContent {MaterialTheme {DeskReminderTimePicker(java.time.LocalDate.of(2026,10,18),0,15,minute,true,{}, {},{changes++})}}
        compose.runOnIdle {minute=11}
        compose.waitForIdle()
        compose.runOnIdle {assertEquals(0,changes)}
        compose.onNodeWithContentDescription("分钟选择").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"11 分钟"))
        compose.onNodeWithContentDescription("分钟选择").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) {it(20f)}
        compose.runOnIdle {assertTrue(changes>0)}
    }
    @Test fun recentInboxUsesSeparateTitleSourceAndUnreadBadge() {
        var opened: String?=null
        val snapshot=deskJson.parseToJsonElement("""{"inbox":{"unread":2,"recent":[{"id":"one","title":"锁屏通知测试","source_name":"Codex 重置","received_at":"2026-10-18T07:00:00Z","read_at":null}],"sources":[]}}""").jsonObject
        compose.setContent {MaterialTheme(colorScheme=deskColors(false),shapes=deskShapes,typography=deskTypography) {Surface {Column(Modifier.width(360.dp).padding(18.dp)) {RecentInbox(snapshot) {opened=it}}}}}
        compose.onNodeWithText("最近通知").assertExists()
        compose.onNodeWithText("2 未读").assertExists()
        compose.onNodeWithText("锁屏通知测试").performClick()
        compose.runOnIdle {assertEquals("one",opened)}
        capture("inbox")
    }
    @Test fun reminderFrequencyAndTimeShareOneRow() {
        compose.setContent {MaterialTheme(colorScheme=deskColors(false),shapes=deskShapes,typography=deskTypography) {Surface {Column(Modifier.width(360.dp).verticalScroll(rememberScrollState()).padding(18.dp)) {ReminderForm(false,"Asia/Shanghai") {_,_->}}}}}
        val frequency=compose.onNodeWithText("重复 · 仅一次").fetchSemanticsNode().boundsInRoot
        val time=compose.onNode(hasText("2026-",substring=true)).fetchSemanticsNode().boundsInRoot
        assertTrue(time.center.y in frequency.top..frequency.bottom)
        assertTrue(time.left>=frequency.right)
        compose.onNodeWithText("创建提醒").assertIsEnabled()
        capture("reminder")
    }
    @Test fun stepHeadingsAndTheirActionsShareOneRow() {
        val plan=deskJson.parseToJsonElement("""{"settings":{"start":1000,"increment":30,"interval_minutes":3,"target":1090},"presets":[],"revision":"rev"}""").jsonObject
        compose.setContent {MaterialTheme(colorScheme=deskColors(false),shapes=deskShapes,typography=deskTypography) {Surface {Column(Modifier.width(360.dp).verticalScroll(rememberScrollState()).padding(18.dp)) {DeskCard("") {StepPlanEditor(plan,false,true,{_,_->},onClose={})}}}}}
        val title=compose.onNodeWithText("自动任务").fetchSemanticsNode().boundsInRoot
        for(label in listOf("快捷配置","管理","关闭")) {
            val rect=compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertEquals(title.center.y,rect.center.y,2f)
        }
        capture("steps")
    }
}
