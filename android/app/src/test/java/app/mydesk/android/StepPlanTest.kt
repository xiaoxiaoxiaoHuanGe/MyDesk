package app.mydesk.android

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class,qualifiers="w400dp-h900dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StepPlanTest {
    @get:Rule val compose=createComposeRule()
    @Test fun strictCrossingPreviewAndIntegerValidation() {
        val p=StepPlanInput.parse("1000","30","3","1090")
        assertEquals(1120,p.final);assertEquals(5,p.count);assertEquals(12,p.duration)
        for(v in listOf("","1.5","true")) assertTrue(runCatching {StepPlanInput.parse(v,"30","3","1090")}.isFailure)
        assertTrue(runCatching {StepPlanInput.parse("1000","30","3","30000")}.isFailure)
    }
    @Test fun attentionRowsAreClickableAndReturnTheStableDestination() {
        var chosen: JsonObject?=null
        val entry=buildJsonObject {put("title","邮箱同步异常");put("message","连接超时");put("destination",buildJsonObject {put("type","settings");put("section","mail");put("id","work")})}
        compose.setContent {MaterialTheme {WorkbenchAttentionCard(listOf(entry),true) {chosen=it}}}
        compose.onNodeWithText("邮箱同步异常").performClick()
        compose.runOnIdle {assertEquals("work",chosen!!.obj("destination").text("id"))}
    }
    @Test fun selectingPresetFillsFieldsWithoutStartingOrSaving() {
        val plan=deskJson.parseToJsonElement("""{"settings":{},"presets":[{"id":"walk","name":"散步","start":1000,"increment":30,"interval_minutes":3,"target":1090}],"revision":"rev"}""").jsonObject
        var calls=0
        compose.setContent {MaterialTheme {StepPlanEditor(plan,false,true,{_,_->calls++})}}
        compose.onNodeWithText("散步").performClick()
        compose.onNodeWithTag("plan-start").assertTextContains("1000")
        compose.onNodeWithTag("plan-increment").assertTextContains("30")
        compose.onNodeWithTag("plan-interval").assertTextContains("3")
        compose.onNodeWithTag("plan-target").assertTextContains("1090")
        compose.runOnIdle {assertEquals(0,calls)}
    }
    @Test fun stopStaysEnabledDuringAnInFlightSubmission() {
        val run=deskJson.parseToJsonElement("""{"id":"round","name":"散步","status":"running","params":{"target":1090},"success_count":1,"current_job":{"steps":1030,"status":"running"}}""").jsonObject
        var action=""
        compose.setContent {MaterialTheme {StepPlanStatus(run,"Asia/Shanghai",true,{action=it},{})}}
        compose.onNodeWithText("终止本轮").assertIsEnabled().performClick()
        compose.runOnIdle {assertEquals("stop",action)}
    }
    @Test fun uncertainManualResultIsActionableInsideThePlanSheet() {
        val snapshot=deskJson.parseToJsonElement("""{"wxstep_plan":{"settings":{},"presets":[]},"wxstep":{"steps":1000,"status":"tracking_error","message":"提交结果不确定"}}""").jsonObject
        var action=""
        compose.setContent {MaterialTheme {StepPlanSheet(snapshot,true,{a,_->action=a},{})}}
        compose.onNodeWithText("提交结果不确定").assertExists()
        compose.onNodeWithText("检查后结束本地跟踪").assertIsEnabled().performClick()
        compose.runOnIdle {assertEquals("",action)}
        compose.onNodeWithText("已检查，结束跟踪").performClick()
        compose.runOnIdle {assertEquals("wxstep/release",action)}
    }
}
