package app.mydesk.android

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
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
    @Test fun savingAnExistingLegacyConfigurationUpdatesItsPresetWithoutSelectingItAgain() {
        val plan=deskJson.parseToJsonElement("""{"settings":{"name":"散步","start":1000,"increment":30,"interval_minutes":3,"target":1090},"presets":[{"id":"walk","name":"散步","start":1000,"increment":30,"interval_minutes":3,"target":1090}],"revision":"rev"}""").jsonObject
        var sent=buildJsonObject {}
        compose.setContent {MaterialTheme {Column(Modifier.verticalScroll(rememberScrollState())) {StepPlanEditor(plan,false,true,{_,p->sent=p})}}}
        compose.onNodeWithText("保存配置").performScrollTo().performClick()
        compose.runOnIdle {assertEquals("walk",sent.text("preset_id"))}
    }
    @Test fun aDeletedSelectedConfigurationCanBeSavedAsANewConfiguration() {
        val plan=deskJson.parseToJsonElement("""{"settings":{"preset_id":"deleted","name":"散步","start":1000,"increment":30,"interval_minutes":3,"target":1090},"presets":[],"revision":"rev"}""").jsonObject
        var sent=buildJsonObject {}
        compose.setContent {MaterialTheme {Column(Modifier.verticalScroll(rememberScrollState())) {StepPlanEditor(plan,false,true,{_,p->sent=p})}}}
        compose.onNodeWithText("保存配置").performScrollTo().performClick()
        compose.runOnIdle {assertFalse(sent.containsKey("preset_id"))}
    }
    @Test fun saveConfigurationUpdatesTheSelectedTemplateAndDailyArrangementInOneCommand() {
        val plan=deskJson.parseToJsonElement("""{"settings":{},"presets":[{"id":"walk","name":"散步","start":1000,"increment":30,"interval_minutes":3,"target":1090,"daily":true,"start_time":"09:00"}],"revision":"rev"}""").jsonObject
        var action="";var payload=buildJsonObject {}
        compose.setContent {MaterialTheme {Column(Modifier.verticalScroll(rememberScrollState())) {StepPlanEditor(plan,false,true,{a,p->action=a;payload=p})}}}
        compose.onNodeWithText("散步").performClick()
        compose.onNodeWithText("保存配置").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("wxstep/plan/save",action)
            assertEquals("walk",payload.text("preset_id"))
            assertEquals("true",payload.text("save_preset"))
            assertEquals("true",payload.text("daily"))
            assertEquals("09:00",payload.text("start_time"))
        }
        compose.onNodeWithText("更新此预设").assertDoesNotExist()
        compose.onNodeWithText("保存设置").assertDoesNotExist()
    }
    @Test fun stepFailureProvidesAcknowledgementAlongsideTheDetailEntry() {
        val entry=buildJsonObject {put("kind","steps");put("id","job");put("title","步数提交失败");put("message","failure");put("ack_token","token")}
        var acknowledged="";var opened=false
        compose.setContent {MaterialTheme {WorkbenchAttentionCard(listOf(entry),true,onAcknowledge={acknowledged=it.text("ack_token")},onOpen={opened=true})}}
        compose.onNodeWithText("已知晓").assertExists().assertIsEnabled().performClick()
        compose.runOnIdle {assertEquals("token",acknowledged);assertFalse(opened)}
    }
    @Test fun recordsStayGroupedUntilOpeningOneRoundAndOnlyQueryThatRound() {
        val snapshot=deskJson.parseToJsonElement("""{"wxstep_plan":{"settings":{},"run":{"id":"new","name":"散步","status":"completed","params":{"start":1000,"target":1090,"increment":30,"interval_minutes":3},"last_success":1120,"success_count":5,"records":[{"seq":2,"steps":1120,"status":"success"}]},"recent_runs":[{"id":"new","name":"散步","status":"completed","started_at":"2026-10-06T08:00:00Z","success_count":5},{"id":"old","name":"跑步","status":"stopped","started_at":"2026-10-05T08:00:00Z","success_count":1,"last_success":900,"params":{"start":900,"target":1200,"increment":30,"interval_minutes":3}}]}}""").jsonObject
        val queried=mutableListOf<String>()
        val old=deskJson.parseToJsonElement("""{"seq":1,"steps":900,"status":"success","created_at":"2026-10-05T08:00:00Z","source":"plan"}""").jsonObject
        compose.setContent {MaterialTheme {StepsPage(snapshot,false,"",2,{}, {_,_->}, {id,_->queried.add(id);listOf(old)}, {})}}
        compose.onNodeWithText("1120 步").assertDoesNotExist()
        compose.onNodeWithTag("step-run-old").performScrollTo().performClick()
        compose.onNodeWithText("本轮提交记录").assertExists()
        compose.onAllNodesWithText("900 步").assertCountEquals(2)
        compose.onNodeWithText("1120 步").assertDoesNotExist()
        compose.runOnIdle {assertEquals(listOf("old"),queried)}
    }
    @Test fun randomNewConfigurationDefaultsOnAndLegacyPresetRemainsFixed() {
        val plan=deskJson.parseToJsonElement("""{"capabilities":{"random_steps":true},"settings":{},"presets":[{"id":"walk","name":"旧预设","start":80,"increment":80,"interval_minutes":3,"target":975}],"revision":"rev"}""").jsonObject
        var sent=buildJsonObject {}
        compose.setContent {MaterialTheme {Column(Modifier.verticalScroll(rememberScrollState())) {StepPlanEditor(plan,false,true,{_,p->sent=p})}}}
        compose.onNodeWithTag("plan-random").assertIsOn()
        compose.onNodeWithText("旧预设").performClick()
        compose.onNodeWithTag("plan-random").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText("每次增加 72～88 步；末次可能因 30000 封顶减少").assertExists()
        compose.onNodeWithText("保存配置").performScrollTo().performClick()
        compose.runOnIdle {assertEquals("10",sent.text("random_percent"))}
    }
    @Test fun oldServerDisablesRandomWithoutBreakingFixedPlans() {
        val plan=deskJson.parseToJsonElement("""{"settings":{"start":80,"increment":80,"interval_minutes":3,"target":975},"presets":[],"revision":"rev"}""").jsonObject
        var sent=buildJsonObject {}
        compose.setContent {MaterialTheme {Column(Modifier.verticalScroll(rememberScrollState())) {StepPlanEditor(plan,false,true,{_,p->sent=p})}}}
        compose.onNodeWithTag("plan-random").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText("保存配置").performScrollTo().performClick()
        compose.runOnIdle {assertEquals("0",sent.text("random_percent"))}
    }
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
        compose.onNodeWithText("已知晓").assertDoesNotExist()
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
    @Test fun disablingDailyRepeatDoesNotSubmitAnInvalidHiddenClock() {
        val plan=deskJson.parseToJsonElement("""{"settings":{"start":1000,"increment":30,"interval_minutes":3,"target":1090,"daily":false,"start_time":"08:00"},"presets":[],"revision":"rev"}""").jsonObject
        var sent: JsonObject?=null
        compose.setContent {MaterialTheme {Column(Modifier.verticalScroll(rememberScrollState())) {StepPlanEditor(plan,false,true,{_,p->sent=p})}}}
        compose.onAllNodes(isToggleable()).onLast().performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("每日开始时间")).performScrollTo().performTextReplacement("invalid")
        compose.onAllNodes(isToggleable()).onLast().performScrollTo().performClick()
        compose.onNodeWithText("每日开始时间").assertDoesNotExist()
        compose.onNodeWithText("保存配置").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("false",sent!!.text("daily"))
            assertEquals("08:00",sent!!.text("start_time"))
            assertEquals("自动任务",sent!!.text("name"))
        }
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
        compose.setContent {MaterialTheme {StepSubmissionIssue(snapshot.obj("wxstep"),false,{a,_->action=a},{})}}
        compose.onNodeWithText("提交结果不确定").assertExists()
        compose.onNodeWithText("检查后结束本地跟踪").assertIsEnabled().performClick()
        compose.runOnIdle {assertEquals("",action)}
        compose.onNodeWithText("已检查，结束跟踪").performClick()
        compose.runOnIdle {assertEquals("wxstep/release",action)}
    }
}
