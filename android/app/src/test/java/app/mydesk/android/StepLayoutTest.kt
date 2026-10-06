package app.mydesk.android

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
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
class StepLayoutTest {
    @get:Rule val compose=createComposeRule()
    private fun showEditor() {
        val plan=deskJson.parseToJsonElement("""{"settings":{"start":1000,"increment":30,"interval_minutes":3,"target":1090},"presets":[],"revision":"rev"}""").jsonObject
        compose.setContent {MaterialTheme {Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
            StepPlanEditor(plan,false,true,{_,_->})
        }}}
    }
    @Test fun emptyFocusedNameDoesNotGrowOrDisplayExampleText() {
        showEditor()
        val field=compose.onNodeWithTag("plan-name")
        field.performClick()
        compose.onNodeWithText("散步、跑步、逛街").assertDoesNotExist()
        val emptyHeight=field.fetchSemanticsNode().boundsInRoot.height
        field.performTextReplacement("散步")
        assertEquals(emptyHeight,field.fetchSemanticsNode().boundsInRoot.height,1f)
    }
    @Test fun saveAndStartHaveEqualWidthsOnANarrowForm() {
        showEditor()
        val save=compose.onNodeWithText("保存配置").performScrollTo().fetchSemanticsNode().boundsInRoot
        val start=compose.onNodeWithText("立即开始").fetchSemanticsNode().boundsInRoot
        assertEquals(save.width,start.width,1f)
        assertEquals(save.height,start.height,1f)
    }
    @Test fun workflowLinkBelongsToTheSubmissionStatusCardAndOpensItsUrl() {
        val url="https://github.com/example/steps/actions/runs/123"
        val job=buildJsonObject {put("status","failed");put("message","failure");put("steps",1000);put("url",url)}
        var opened=""
        compose.setContent {MaterialTheme {Column {StepSubmissionIssue(job,false,{_,_->},{opened=it})}}}
        val status=compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion),useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val link=compose.onNodeWithText("查看工作流原因")
        val bounds=link.fetchSemanticsNode().boundsInRoot
        assertTrue("Workflow reason must be inside the submission status card",bounds.top>=status.top && bounds.bottom<=status.bottom)
        link.performClick()
        compose.runOnIdle {assertEquals(url,opened)}
    }
}
