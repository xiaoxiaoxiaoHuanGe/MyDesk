package app.mydesk.android

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
@Config(sdk=[32],application=Application::class,qualifiers="w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InboxPresentationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun lightCardsKeepSourceAndInboxActionsDistinct()=render(false)
    @Test @Config(fontScale=2f) fun darkCardsKeepLongMessageAndActionsAccessibleWithLargeText()=render(true)

    @Test fun readAndQueuedReadMessagesDoNotShowAnUnreadBadge() {
        compose.setContent {
            MaterialTheme(colorScheme=deskColors(false),shapes=deskShapes,typography=deskTypography) {
                Column(Modifier.width(324.dp)) {
                    listOf("pending","2026-10-12T11:22:00Z").forEach {readAt->
                        InboxMessageCard(buildJsonObject {
                            put("title","已读通知");put("source_name","CPE 短信")
                            put("received_at","2026-10-12T11:21:00Z");put("read_at",readAt)
                        },"Asia/Shanghai") {}
                    }
                }
            }
        }
        compose.onNodeWithText("未读").assertDoesNotExist()
    }

    private fun render(dark: Boolean) {
        var openedSources=0;var openedInbox=0;var openedMessage=0
        val row=buildJsonObject {
            put("name","CPE 短信");put("enabled",1);put("last_received_at",JsonNull)
            put("source_name","CPE 短信");put("title","收到一条新的提醒消息")
            put("summary","消息内容预览，点开即可查看完整正文。")
            put("received_at","2026-10-12T11:21:00Z");put("read_at",JsonNull)
        }
        compose.setContent {
            MaterialTheme(colorScheme=deskColors(dark),shapes=deskShapes,typography=deskTypography) {
                Surface(color=MaterialTheme.colorScheme.background) {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        NotificationAccessCard("2",{openedSources++},{openedInbox++})
                        ReminderCard(Reminder("one","吃饭","2030-10-28T11:21:00Z","pending","1"),"Asia/Shanghai",false,detail={}) {_,_->}
                        NotificationSourceCard(row,"Asia/Shanghai",{},true)
                        InboxMessageCard(row,"Asia/Shanghai") {openedMessage++}
                    }
                }
            }
        }
        compose.onNodeWithText("通知来源").performClick()
        assertEquals(1,openedSources);assertEquals(0,openedInbox)
        compose.onNodeWithText("通知收件箱").performClick()
        assertEquals(1,openedInbox)
        compose.onNodeWithText("2 条未读").assertExists()
        compose.onNodeWithText("收到一条新的提醒消息").performScrollTo().performClick()
        assertEquals(1,openedMessage)
        compose.onNodeWithText("尚未收到消息").assertExists()
        compose.onNodeWithText("未读").assertExists()
        compose.onNodeWithText("通知接入").performScrollTo()
        val directory=File(System.getProperty("user.home"),"ui-review").apply {mkdirs()}
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory,if(dark) "notifications-dark-large.png" else "notifications-light.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
            }
            bitmap.recycle()
        }
    }
}
