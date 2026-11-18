package app.mydesk.android

import android.app.Application
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class,qualifiers="w320dp-h760dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class XDigestPageTest {
    @get:Rule val compose=createComposeRule()

    @Test fun darkFeedOpensNativeDetailAndCanSwitchToOriginal() {
        val post=buildJsonObject {
            put("id","987");put("title","新工具发布");put("category","ai");put("author","alice")
            put("summary","一份中文摘要。");put("likes",1007);put("posted_at","2026-11-17T20:00:00Z")
        }
        val detail=JsonObject(post+mapOf("text" to JsonPrimitive("Full original."),
            "translation" to JsonPrimitive("完整译文。"),"read" to JsonPrimitive(true)))
        val page=buildJsonObject {
            put("items",JsonArray(listOf(post)));put("unread",1)
            put("categories",JsonArray(listOf(buildJsonObject {put("id","ai");put("label","AI 人工智能")})))
        }
        val model=XDigestModel({path,method,_->
            when {
                path.startsWith("/api/x-digest/feed") -> page
                path=="/api/x-digest/posts/987/read" && method=="POST" -> detail
                else -> error("Unexpected operation")
            }
        },DigestCache(ApplicationProvider.getApplicationContext(),"test-digest-"+System.nanoTime()))
        compose.setContent {
            MaterialTheme(colorScheme=deskColors(true),shapes=deskShapes,typography=deskTypography) {
                XDigestPage(model,rememberLazyListState(),true,"Asia/Taipei") {}
            }
        }
        compose.waitUntil(5000) {compose.onAllNodesWithText("新工具发布").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("新工具发布").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("看原文").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("完整译文。").assertExists()
        compose.onNodeWithText("看原文").performClick()
        compose.onNodeWithText("Full original.").assertExists()
    }
}
