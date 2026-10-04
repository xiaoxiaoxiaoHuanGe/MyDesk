package app.mydesk.android

import androidx.compose.foundation.rememberScrollState

import android.app.Application
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
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
@Config(sdk=[32],application=Application::class,qualifiers="w360dp-h800dp",fontScale=2f)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibleLayoutTest {
    @get:Rule val compose=createComposeRule()

    private fun largeContent(content: @Composable ()->Unit) {
        compose.setContent {
            MaterialTheme(colorScheme=deskColors(false),shapes=deskShapes,typography=deskTypography) {Box(Modifier.width(320.dp).height(640.dp).testTag("viewport")) {content()}}
        }
    }

    @Test @Config(fontScale=1f) fun mailboxProxyCanBeSavedWithoutReplacingCredentialsOrOtherMailboxes() {
        val settings=deskJson.parseToJsonElement("""{"gmail_accounts":{"one":{"name":"邮箱一","username":"one@gmail.com","credential_set":true},"two":{"name":"邮箱二","username":"two@gmail.com","credential_set":true}}}""").jsonObject
        var saved: JsonObject?=null
        largeContent {MultiServiceSettings(true,settings,false,{value,_->saved=value},{})}
        compose.onNodeWithText("邮箱一").performClick()
        compose.onNodeWithText("使用 HTTP 代理").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("使用 HTTP 代理").performClick()
        compose.onNode(hasSetTextAction() and hasText("代理地址")).performScrollTo().performTextReplacement("127.0.0.1")
        compose.onNode(hasSetTextAction() and hasText("代理端口")).performScrollTo().performTextReplacement("0")
        compose.onNodeWithText("保存邮箱").performScrollTo().performClick()
        compose.onNodeWithText("代理端口需要在 1–65535 之间").assertExists()
        compose.runOnIdle {assertNull(saved)}
        compose.onNode(hasSetTextAction() and hasText("代理端口")).performScrollTo().performTextReplacement("17891")
        compose.onNodeWithText("保存邮箱").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(17891,saved!!.obj("gmail_accounts").obj("one").obj("proxy")["port"]!!.jsonPrimitive.int)
            assertFalse(saved!!.obj("gmail_accounts").obj("one").containsKey("password"))
            assertEquals(settings.obj("gmail_accounts").obj("two"),saved!!.obj("gmail_accounts").obj("two"))
        }
    }


    @Test @Config(fontScale=1f) fun multipleMailboxesCanBeAddedWithIndependentCredentials() {
        val config=mutableStateOf(buildJsonObject {})
        val sent=mutableListOf<JsonObject>()
        largeContent {IntegrationSettings(config.value,false,{value,done->
            sent.add(value)
            config.value=buildJsonObject {config.value.forEach {(k,v)->put(k,v)};value.forEach {(k,v)->put(k,buildJsonObject {v.jsonObject.forEach {(id,row)->put(id,buildJsonObject {
                row.jsonObject.filterKeys {it!="password" && it!="api_key"}.forEach {(f,item)->put(f,item)};put("credential_set",true)
            })}})} };done()
        })}
        compose.onNodeWithText("Gmail 邮件").performClick()
        for((name,address,password) in listOf(Triple("工作邮箱","work@gmail.com","work-secret"),Triple("私人邮箱","home@gmail.com","home-secret"))) {
            compose.onNodeWithText("添加邮箱").performScrollTo().performClick()
            for((label,value) in listOf("邮箱名称" to name,"Gmail 地址" to address,"应用专用密码" to password)) {
                compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)
            }
            compose.onNodeWithText("保存邮箱").performScrollTo().performClick()
        }
        compose.runOnIdle {
            assertEquals(2,sent.size)
            val first=sent.first().obj("gmail_accounts").values.single().jsonObject
            assertEquals("work-secret",first.text("password"))
            val second=sent.last().obj("gmail_accounts")
            assertEquals(2,second.size)
            assertFalse(second.values.single {it.jsonObject.text("name")=="工作邮箱"}.jsonObject.containsKey("password"))
            assertEquals("home-secret",second.values.single {it.jsonObject.text("name")=="私人邮箱"}.jsonObject.text("password"))
        }
    }

    @Test @Config(fontScale=1f) fun serverManagementDefaultsToOnePanelAndSavesItsOwnApiKey() {
        var saved: JsonObject?=null
        largeContent {IntegrationSettings(buildJsonObject {},false,{value,done->saved=value;done()})}
        compose.onNodeWithText("服务器监控").performClick()
        compose.onNodeWithText("添加服务器").performScrollTo().performClick()
        compose.onNodeWithText("1Panel v2").assertExists()
        for((label,value) in listOf("服务器名称" to "东京服务器","面板地址" to "https://panel.example.com:1234","API Key" to "panel-secret")) {
            compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)
        }
        compose.onNodeWithText("保存服务器").performScrollTo().performClick()
        compose.runOnIdle {
            val row=saved!!.obj("server_sources").values.single().jsonObject
            assertEquals("1panel",row.text("provider"))
            assertEquals("panel-secret",row.text("api_key"))
            assertFalse(row.containsKey("password"))
        }
    }

    @Test @Config(fontScale=1f) fun mailCardSwitchesAccountsWithoutAnExternalOpenButton() {
        val snapshot=buildJsonObject {
            put("configured",buildJsonObject {put("mail",true)})
            put("feeds",buildJsonObject {put("mail",buildJsonObject {put("data",buildJsonObject {
                put("accounts",buildJsonArray {for(name in listOf("工作邮箱","私人邮箱")) add(buildJsonObject {
                    put("id",name);put("name",name);put("unread",1);put("url","https://mail.google.com/?account=$name")
                    put("items",buildJsonArray {add(buildJsonObject {put("subject","$name 的邮件");put("sender","发件人");put("received_at","2026-10-03T08:00:00Z")})})
                })})
                put("items",buildJsonArray {});put("unread",2)
            })})})
        }
        largeContent {Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {MailCard(snapshot,"Asia/Shanghai")}}
        compose.onNodeWithText("选择邮箱").performClick()
        compose.onNodeWithText("私人邮箱").performClick()
        compose.onNodeWithText("私人邮箱 的邮件").assertExists()
        compose.onNodeWithText("工作邮箱 的邮件").assertDoesNotExist()
        compose.onNodeWithText("打开 Gmail").assertDoesNotExist()
    }

    @Test @Config(fontScale=1f) fun nodeSearchKeepsHiddenNodesWhenEditingAndSaving() {
        var saved: JsonObject?=null
        val config=buildJsonObject {put("network",buildJsonObject {put("enabled",true);put("nodes",buildJsonArray {
            for((name,host) in listOf("家庭路由器" to "192.168.1.1","日本节点" to "jp.example.com")) add(buildJsonObject {put("name",name);put("host",host)})
        })})}
        largeContent {WorkbenchSettings(config,false,{value,done->saved=value;done()})}
        compose.onNodeWithText("网络连通性").performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNode(hasText("搜索节点名称或地址") and hasSetTextAction()).performTextReplacement("jp.example")
        compose.onNodeWithText("家庭路由器").assertDoesNotExist()
        compose.onNodeWithContentDescription("编辑节点 日本节点").performScrollTo().performClick()
        compose.onNode(hasText("节点名称") and hasSetTextAction()).performScrollTo().performTextReplacement("东京节点")
        compose.onNodeWithText("完成编辑").assertIsDisplayed().performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {assertEquals(listOf("家庭路由器","东京节点"),saved!!.obj("network").rows("nodes").map {it.text("name")})}
    }

    @Test @Config(fontScale=1f) fun timeZoneIsSelectedFromADropdownAndSavesWithRetention() {
        var saved: JsonObject?=null
        largeContent {WorkbenchSettings(buildJsonObject {put("timezone","Asia/Shanghai");put("history_days",90)},false,{value,done->saved=value;done()})}
        compose.onNodeWithText("时区与历史保留").performClick()
        compose.onNodeWithText("工作台设置").assertDoesNotExist()
        compose.onNodeWithText("1–365 天").assertDoesNotExist()
        compose.onNodeWithContentDescription("选择时区").performClick()
        compose.onNodeWithText("协调世界时 · UTC").performScrollTo().performClick()
        compose.onNode(hasText("历史保留天数") and hasSetTextAction()).performTextReplacement("30")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {assertEquals("UTC",saved!!.text("timezone"));assertEquals(30,saved!!["history_days"]!!.jsonPrimitive.int)}
    }

    @Test @Config(fontScale=1f) fun networkNodesCanBeEditedAddedAndRemovedWithoutTextLines() {
        var saved: JsonObject?=null
        val config=buildJsonObject {put("network",buildJsonObject {put("enabled",true);put("nodes",buildJsonArray {add(buildJsonObject {put("name","旧节点");put("host","old.example.com")})})})}
        largeContent {WorkbenchSettings(config,false,{value,done->saved=value;done()})}
        compose.onNodeWithText("网络连通性").performClick()
        fun fill(label: String,value: String) {compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)}
        fun add(name: String,host: String) {
            compose.onNodeWithTag("settings-fields").performScrollToNode(hasText("添加节点"))
            compose.onNodeWithText("添加节点").performClick();fill("节点名称",name);fill("域名或 IP",host)
        }
        compose.onNodeWithContentDescription("编辑节点 旧节点").performClick()
        fill("节点名称","家庭路由器");fill("域名或 IP","192.168.1.1")
        compose.onNodeWithText("完成编辑").performClick()
        add("家庭路由器","1.1.1.1")
        compose.onNodeWithText("完成编辑").performClick()
        compose.onNodeWithTag("settings-fields").performScrollToNode(hasText("节点名称不能重复"))
        compose.onNodeWithText("节点名称不能重复").assertIsDisplayed()
        compose.runOnIdle {assertNull(saved)}
        fill("节点名称","备用 DNS")
        compose.onNodeWithText("完成编辑").performClick()
        add("临时节点","8.8.8.8")
        compose.onNodeWithText("完成编辑").performClick()
        compose.onNodeWithTag("settings-fields").performScrollToNode(hasContentDescription("删除节点 临时节点"))
        compose.onNodeWithContentDescription("删除节点 临时节点").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            val network=saved!!.obj("network")
            assertTrue(network["enabled"]!!.jsonPrimitive.boolean)
            assertEquals(listOf("家庭路由器" to "192.168.1.1","备用 DNS" to "1.1.1.1"),network.rows("nodes").map {it.text("name") to it.text("host")})
        }
    }

    @Test @Config(fontScale=1f) fun workbenchSettingsHasNoGlobalTaskRuleOrEmptyAdvancedEntry() {
        val config=buildJsonObject {put("expected_tasks",buildJsonObject {put("backup",buildJsonObject {put("name","备份");put("max_age_hours",36)})})}
        largeContent {WorkbenchSettings(config,false,{_,_->fail("Removing a settings entry must not write configuration")})}
        compose.onNodeWithText("自动任务预期周期").assertDoesNotExist()
        compose.onNodeWithText("通用任务超期规则").assertDoesNotExist()
        compose.onNodeWithText("高级设置").assertDoesNotExist()
        compose.onNodeWithText("时区与历史保留").assertExists()
        compose.onNodeWithText("网络连通性").assertExists()
    }

    @Test @Config(fontScale=1f) fun eachGitHubTaskHasAVisibleTimeoutAndSavingPreservesOtherTasks() {
        var saved: JsonObject?=null
        val config=buildJsonObject {
            put("github",buildJsonObject {put("repo","WxStepCustom");put("credential_set",true)})
            put("github_tasks",buildJsonObject {
                for((id,hours) in listOf("52fzwg" to 36,"glados" to 72)) put(id,buildJsonObject {
                    put("name",id);put("owner","owner");put("repo",id);put("workflow","checkin.yml");put("ref","main")
                    put("adapter","workflow");put("enabled",true);put("credential_set",true);put("max_age_hours",hours)
                })
            })
        }
        largeContent {GitHubTaskSettings(config,false,{value,done->saved=value;done()},{},{},"")}
        compose.onNodeWithText("超期提醒 · 72 小时").assertExists()
        compose.onNodeWithText("glados").performScrollTo().performClick()
        val timeout=compose.onNode(hasText("超期时间（小时）") and hasSetTextAction())
        timeout.performScrollTo().assertIsDisplayed().performTextReplacement("0")
        compose.onNodeWithText("收起高级设置").assertDoesNotExist()
        compose.onNodeWithText("保存任务").performScrollTo().performClick()
        compose.onNodeWithText("超期时间需要为 1–8760 小时").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {assertNull(saved)}
        timeout.performScrollTo().performTextReplacement("48")
        compose.onNodeWithText("保存任务").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(saved!!.containsKey("github"))
            assertEquals(config.obj("github_tasks").obj("52fzwg"),saved!!.obj("github_tasks").obj("52fzwg"))
            val updated=saved!!.obj("github_tasks").obj("glados")
            assertEquals(48.0,updated["max_age_hours"]!!.jsonPrimitive.double,0.001)
            assertFalse(updated.containsKey("token"))
        }
    }

    @Test @Config(fontScale=1f) fun newCustomGitHubTaskDefaultsTo36HoursAndBlankRestoresDefault() {
        var saved: JsonObject?=null
        largeContent {GitHubTaskSettings(buildJsonObject {},false,{value,done->saved=value;done()},{},{},"")}
        compose.onNodeWithText("添加任务").performScrollTo().performClick()
        compose.onNodeWithText("自定义任务").performScrollTo().performClick()
        val timeout=compose.onNode(hasText("超期时间（小时）") and hasSetTextAction())
        timeout.performScrollTo().assertTextContains("36").performTextClearance()
        for((label,value) in listOf("任务名称" to "每日备份","仓库所有者" to "owner","仓库名" to "backup","工作流文件名" to "backup.yml","访问 Token" to "backup-token")) {
            compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)
        }
        compose.onNodeWithText("保存任务").performScrollTo().performClick()
        compose.runOnIdle {
            val task=saved!!.obj("github_tasks").values.single().jsonObject
            assertEquals(36.0,task["max_age_hours"]!!.jsonPrimitive.double,0.001)
            assertEquals("workflow",task.text("adapter"))
        }
    }

    @Test fun compactAccountActionsStayReachableAndProtectSyncInProgress() {
        val busy=mutableStateOf(false)
        val syncing=mutableStateOf(false)
        var syncs=0
        var exits=0
        largeContent {Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            AccountSettingsCard("https://mydesk.example.com","Example Phone",busy.value,syncing.value,
                {syncs++},{exits++},{_,_->},{_,_->})
        }}
        val viewport=compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        for(label in listOf("立即同步","修改密码","退出账号","编辑设备名称")) {
            val node=compose.onNodeWithContentDescription(label)
            node.performScrollTo().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            val bounds=node.fetchSemanticsNode().boundsInRoot
            assertTrue("$label must fit a narrow screen",bounds.left>=viewport.left && bounds.right<=viewport.right)
        }
        compose.onNodeWithContentDescription("立即同步").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(1,syncs);syncing.value=true}
        compose.onNodeWithContentDescription("立即同步").assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"同步中"))
        compose.onNodeWithContentDescription("退出账号").assertIsNotEnabled()
        compose.runOnIdle {syncing.value=false;busy.value=true}
        compose.onNodeWithContentDescription("修改密码").assertIsNotEnabled()
        compose.onNodeWithContentDescription("编辑设备名称").assertIsNotEnabled()
        compose.runOnIdle {busy.value=false}
        compose.onNodeWithContentDescription("退出账号").performScrollTo().performClick()
        compose.onNodeWithContentDescription("编辑设备名称").performScrollTo().assertIsEnabled().assertHasClickAction()
        compose.runOnIdle {assertEquals(1,exits)}
        compose.onNodeWithText("账号密码").assertDoesNotExist()
    }

    @Test fun compactAppearanceKeepsAllThreeSelectionsAvailableAtLargeFont() {
        val theme=mutableStateOf("system")
        largeContent {Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            NotificationSettingsCard(true,true,true,false,{},{},{}) {ThemePreference(theme.value) {theme.value=it}}
        }}
        for((label,value) in listOf("浅色" to "light","深色" to "dark","跟随系统" to "system")) {
            val node=compose.onNodeWithContentDescription(label)
            node.performScrollTo().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
            node.assertIsSelected()
            compose.runOnIdle {assertEquals(value,theme.value)}
        }
        compose.onNodeWithText("定时提醒已就绪").assertExists()
        compose.onNodeWithText("即时告警未启用").assertExists()
    }

    @Test fun passwordIconRetainsAccessibleActionAndBusyProtection() {
        val busy=mutableStateOf(true)
        var changes=0
        largeContent {PasswordSettings(busy.value,{_,_->changes++})}
        val action=compose.onNodeWithContentDescription("修改密码")
        action.assertIsNotEnabled().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithText("账号密码").assertDoesNotExist()
        compose.runOnIdle {busy.value=false}
        action.assertIsEnabled().assertHasClickAction()
        compose.runOnIdle {assertEquals(0,changes)}
    }

    @Test fun notificationCardKeepsOnlyNecessaryActionsAndSettingsStayReachableAtLargeFont() {
        val allowed=mutableStateOf(true)
        var notificationClicks=0
        var exactClicks=0
        var settingsClicks=0
        largeContent {Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            NotificationSettingsCard(allowed.value,allowed.value,true,false,{notificationClicks++},{exactClicks++},{settingsClicks++})
        }}
        compose.onNodeWithText("后台设置").assertDoesNotExist()
        compose.onNodeWithText("开启通知").assertDoesNotExist()
        compose.onNodeWithText("允许准时提醒").assertDoesNotExist()
        compose.onNodeWithContentDescription("通知设置").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle {assertEquals(1,settingsClicks);allowed.value=false}
        compose.onNodeWithText("开启通知").performScrollTo().performClick()
        compose.onNodeWithText("允许准时提醒").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(1,notificationClicks);assertEquals(1,exactClicks)}
    }

    @Test @Config(fontScale=1f) fun headerHidesHealthyConnectionAndKeepsTitleCenteredWhenOffline() {
        val connected=mutableStateOf(true)
        largeContent {DeskHeader("MyDesk","",connected.value)}
        compose.onNodeWithText("已连接").assertDoesNotExist()
        compose.onNodeWithText("未连接").assertDoesNotExist()
        val viewport=compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        var title=compose.onNodeWithText("MyDesk").fetchSemanticsNode().boundsInRoot
        assertEquals(viewport.center.x,title.center.x,1f)
        compose.runOnIdle {connected.value=false}
        compose.onNodeWithText("未连接").assertIsDisplayed()
        title=compose.onNodeWithText("MyDesk").fetchSemanticsNode().boundsInRoot
        assertEquals(viewport.center.x,title.center.x,1f)
    }

    @Test fun attentionCardStaysTheSameSizeAndItsLastItemIsReachableByScrolling() {
        val entries=mutableStateOf(emptyList<JsonObject>())
        largeContent {Column {WorkbenchAttentionCard(entries.value,true);androidx.compose.material3.Text("下方卡片")}}
        val before=compose.onNodeWithTag("attention-card").fetchSemanticsNode().boundsInRoot.height
        val nextBefore=compose.onNodeWithText("下方卡片").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle {entries.value=(0..19).map {buildJsonObject {put("title","事项 $it");put("message","需要确认这个任务的执行状态")}}}
        assertEquals(before,compose.onNodeWithTag("attention-card").fetchSemanticsNode().boundsInRoot.height,1f)
        assertEquals(nextBefore,compose.onNodeWithText("下方卡片").fetchSemanticsNode().boundsInRoot.top,1f)
        compose.onNodeWithTag("attention-list").performScrollToNode(hasText("事项 19"))
        compose.onNodeWithText("事项 19").assertIsDisplayed()
    }

    @Test @Config(fontScale=1f) fun allMailboxesShowLatestThreeWithSourcesAndSelectionKeepsItsOwnWindow() {
        val mails=(1..8).map {hour->buildJsonObject {
            val work=hour%2==1
            put("id",hour.toString());put("subject","邮件$hour");put("sender","sender@example.com")
            put("received_at","2026-10-04T${hour.toString().padStart(2,'0')}:00:00Z")
            put("account_id",if(work) "work" else "home");put("account_name",if(work) "工作" else "私人")
            put("account_email",if(work) "work@gmail.com" else "home@gmail.com")
        }}
        val snapshot=buildJsonObject {
            put("configured",buildJsonObject {put("mail",true)})
            put("feeds",buildJsonObject {put("mail",buildJsonObject {put("data",buildJsonObject {
                put("items",JsonArray(mails));put("unread",0)
                put("accounts",JsonArray(listOf("work" to "工作","home" to "私人").map {(id,name)->buildJsonObject {
                    put("id",id);put("name",name);put("username","$id@gmail.com");put("unread",0)
                    put("items",JsonArray(mails.filter {it.text("account_id")==id}))
                }}))
            })})})
        }
        largeContent {Column(Modifier.verticalScroll(rememberScrollState())) {MailCard(snapshot,"Asia/Shanghai")}}
        for(hour in 6..8) compose.onNodeWithText("邮件$hour").assertExists()
        for(hour in 1..5) compose.onNodeWithText("邮件$hour").assertDoesNotExist()
        compose.onAllNodesWithText("工作 · work@gmail.com").assertCountEquals(1)
        compose.onAllNodesWithText("私人 · home@gmail.com").assertCountEquals(2)
        compose.onNodeWithText("选择邮箱").performScrollTo().performClick()
        compose.onNodeWithText("工作").performClick()
        for(hour in listOf(1,3,5,7)) compose.onNodeWithText("邮件$hour").assertExists()
        for(hour in listOf(2,4,6,8)) compose.onNodeWithText("邮件$hour").assertDoesNotExist()
    }

    @Test fun stepSubmissionStartsEmptyAndPreventsAnotherDispatchWhileTheJobIsActive() {
        val snapshot=mutableStateOf(buildJsonObject {put("configured",buildJsonObject {put("github",true)})})
        val sent=mutableListOf<Pair<String,JsonObject>>()
        largeContent {StepsCard(snapshot.value,false,{action,payload->sent.add(action to payload)})}
        compose.onNodeWithText("30000 步以内，且不能低于当前微信运动步数").assertExists()
        val input=compose.onNode(hasText("目标步数") and hasSetTextAction())
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithContentDescription("提交步数").assertIsNotEnabled()
        input.performTextInput("10899")
        compose.onNodeWithContentDescription("提交步数").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle {
            assertEquals(listOf("wxstep/submit" to buildJsonObject {put("steps",10899)}),sent)
            snapshot.value=buildJsonObject {
                put("configured",buildJsonObject {put("github",true)})
                put("wxstep",buildJsonObject {put("steps",10899);put("status","running");put("message","in_progress")})
            }
        }
        compose.onNodeWithText("目标步数").assertIsNotEnabled()
        compose.onNodeWithContentDescription("提交步数").assertIsNotEnabled()
        compose.runOnIdle {assertEquals(1,sent.size)}
    }

    @Test @Config(fontScale=1f) fun githubManagerPreservesSeparateTokensAndCanAddACustomWorkflowWithoutTemplates() {
        val sent=mutableListOf<JsonObject>()
        val settings=mutableStateOf(buildJsonObject {put("github",buildJsonObject {put("credential_set",true);put("repo","WxStepCustom")})})
        largeContent {IntegrationSettings(settings.value,false,{value,done->
            sent.add(value)
            val previous=settings.value
            settings.value=buildJsonObject {previous.forEach {(k,v)->put(k,v)};value.forEach {(k,v)->
                put(k,if(k=="github_tasks") buildJsonObject {v.jsonObject.forEach {(id,item)->put(id,buildJsonObject {
                    item.jsonObject.filterKeys {it!="token"}.forEach {(f,t)->put(f,t)}
                    put("credential_set",item.jsonObject.text("token","").isNotBlank() || previous.obj("github_tasks").obj(id).text("credential_set")=="true")
                })}} else v)
            }};done()
        })}
        compose.onNodeWithText("GitHub 任务").assertExists().performClick()
        compose.onNodeWithText("统一接入模板").assertDoesNotExist()
        for((name,token) in listOf("52 辅助论坛" to "forum-token","GLaDOS" to "glados-token")) {
            compose.onNodeWithText("添加任务").performScrollTo().performClick()
            compose.onNodeWithText(name).performScrollTo().performClick()
            compose.onNode(hasText("仓库所有者") and hasSetTextAction()).performScrollTo().performTextInput("example-user")
            compose.onNode(hasText("访问 Token") and hasSetTextAction()).performScrollTo().performTextInput(token)
            compose.onNodeWithText("保存任务").performScrollTo().performClick()
        }
        compose.onNodeWithText("添加任务").performScrollTo().performClick()
        compose.onNodeWithText("自定义任务").performScrollTo().performClick()
        for((label,value) in listOf("任务名称" to "每日备份","仓库所有者" to "owner","仓库名" to "backup","工作流文件名" to "backup.yml","访问 Token" to "backup-token")) {
            val field=compose.onNode(hasText(label) and hasSetTextAction())
            field.performScrollTo().performTextReplacement(value)
        }
        compose.onNodeWithText("保存任务").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(3,sent.size)
            assertFalse(sent.any {it.containsKey("github")})
            val tasks=sent.last().obj("github_tasks")
            assertEquals("forum-token",sent.first().obj("github_tasks").obj("52fzwg").text("token"))
            assertFalse(tasks.obj("52fzwg").containsKey("token"))
            assertEquals("glados-token",sent[1].obj("github_tasks").obj("glados").text("token"))
            assertFalse(tasks.obj("glados").containsKey("token"))
            val custom=tasks.entries.single {it.key !in setOf("52fzwg","glados")}.value.jsonObject
            assertEquals("workflow",custom.text("adapter"))
            assertEquals("backup.yml",custom.text("workflow"))
            assertEquals("backup-token",custom.text("token"))
            assertFalse(tasks.values.any {it.jsonObject.containsKey("use_steps_token")})
        }
    }

    @Test fun reminderActionsRemainReadableAndReachableAtDoubleFontSize() {
        val actions=mutableListOf<Pair<String,Int>>()
        largeContent {ReminderCard(Reminder("r1","喝水","2099-01-01T00:00:00Z","pending","1"),"Asia/Shanghai",false) {action,minutes->actions.add(action to minutes)}}
        val viewport=compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        for(label in listOf("完成")) {
            val text=compose.onNodeWithText(label,useUnmergedTree=true)
            text.assertIsDisplayed()
            val results=mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(results)}
            val layout=results.single()
            // The semantics result can retain a wider paragraph box than the placed Text.
            // Check actual line extents and characters; allow half a pixel for integer bounds.
            assertTrue(layout.lineCount > 0)
            assertEquals(label.length,layout.getLineEnd(layout.lineCount-1,visibleEnd=true))
            for(line in 0 until layout.lineCount) {
                assertFalse("$label is ellipsized",layout.isLineEllipsized(line))
                assertTrue("$label is clipped horizontally",layout.getLineLeft(line) >= -0.5f && layout.getLineRight(line) <= layout.size.width+0.5f)
                assertTrue("$label is clipped vertically",layout.getLineBottom(line) <= layout.size.height+0.5f)
            }
            val button=compose.onNode(hasText(label) and hasClickAction())
            button.assertHeightIsAtLeast(48.dp)
            val bounds=button.fetchSemanticsNode().boundsInRoot
            assertTrue("$label is outside the viewport",bounds.left >= viewport.left && bounds.right <= viewport.right && bounds.width > 0)
        }
        compose.onNodeWithText("稍后提醒").assertDoesNotExist()
        compose.onNodeWithContentDescription("更多提醒操作").assertDoesNotExist()
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle {assertEquals(listOf("complete" to 10),actions)}
    }

    @Test fun networkToggleHasOneLabelledActionAndUpdatesItsValue() {
        val enabled=mutableStateOf(false)
        var changes=0
        largeContent {NetworkToggle(enabled.value,{enabled.value=it;changes++})}
        val toggle=compose.onNode(hasText("启用网络检测") and isToggleable())
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Switch))
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState,ToggleableState.Off))
        toggle.assertHeightIsAtLeast(48.dp).performClick()
        toggle.assertIsOn()
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.runOnIdle {assertTrue(enabled.value);assertEquals(1,changes)}
    }

    @Test fun cardTitlesExposeHeadingNavigationWithoutHidingChildActions() {
        largeContent {DeskCard("即时提醒") {androidx.compose.material3.TextButton({}) {androidx.compose.material3.Text("查看提醒")}}}
        compose.onNode(hasText("即时提醒") and isHeading()).assertIsDisplayed()
        compose.onNode(hasText("查看提醒") and hasClickAction()).assertIsDisplayed()
    }

    @Test fun passwordFieldsAllowScrollingInLimitedSpaceAtDoubleFontSize() {
        largeContent {Box(Modifier.height(240.dp)) {PasswordFields("","","",{_,_->},"")}}
        val confirm=hasText("再次输入新密码") and hasSetTextAction()
        val current=hasText("当前密码") and hasSetTextAction()
        compose.onNode(hasScrollAction()).performScrollToNode(confirm)
        compose.onNode(confirm).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(current)
        compose.onNode(current).assertIsDisplayed()
    }

    @Test fun reminderCardOpensAReadableDetailSheetAndCanCloseWithoutAnOperation() {
        val opened=mutableStateOf(false)
        val reminder=Reminder("r1","续费 VPS","2099-01-01T00:00:00Z","pending","1")
        var operations=0
        largeContent {
            ReminderCard(reminder,"Asia/Shanghai",false,detail={opened.value=true}) {_,_->operations++}
            if(opened.value) ReminderDetailSheet(reminder,"Asia/Shanghai",false,{opened.value=false}) {_,_,_->operations++}
        }
        compose.onNodeWithContentDescription("查看提醒详情").performClick()
        compose.onNode(hasText("提醒详情") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("2099-01-01 08:00").assertIsDisplayed()
        compose.onNodeWithText("操作先保存到本机，联网后同步。").assertDoesNotExist()
        compose.onNodeWithContentDescription("关闭详情").performClick()
        compose.onNodeWithText("提醒详情").assertDoesNotExist()
        compose.runOnIdle {assertFalse(opened.value);assertEquals(0,operations)}
    }

    @Test fun reminderDetailUsesTheLatestRevisionForActionsAndRespectsBusyState() {
        val reminder=mutableStateOf(Reminder("r1","续费 VPS","2099-01-01T00:00:00Z","pending","1"))
        val busy=mutableStateOf(true)
        var result: Triple<Reminder,String,Int>?=null
        largeContent {ReminderDetailSheet(reminder.value,"Asia/Shanghai",busy.value,{}) {value,action,minutes->result=Triple(value,action,minutes)}}
        compose.onNodeWithContentDescription("延后 30 分钟").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {reminder.value=reminder.value.copy(remindAt="2099-01-02T00:00:00Z",revision="2");busy.value=false}
        compose.onNodeWithText("2099-01-02 08:00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("延后 30 分钟").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle {assertEquals(Triple(reminder.value,"snooze",30),result)}
    }

    private fun assertContentDragDoesNotMoveSheet(closeDescription: String) {
        compose.waitForIdle()
        compose.onNodeWithContentDescription(closeDescription).assertExists()
        val close=compose.onNodeWithContentDescription("下拉关闭窗口")
        val before=close.fetchSemanticsNode().boundsInRoot.top
        val scroll=compose.onNode(hasScrollAction())
        compose.mainClock.autoAdvance=false
        var during=before
        try {
            scroll.performTouchInput {
                down(center)
                moveBy(androidx.compose.ui.geometry.Offset(0f,-70f),80)
                moveBy(androidx.compose.ui.geometry.Offset(0f,-70f),80)
            }
            compose.mainClock.advanceTimeBy(32)
            during=close.fetchSemanticsNode().boundsInRoot.top
            scroll.performTouchInput {up()}
        } finally {compose.mainClock.autoAdvance=true}
        assertEquals("Upward scrolling must not move the sheet",before,during,1f)
        compose.waitForIdle()
        assertEquals("Content inertia must not move the sheet",before,close.fetchSemanticsNode().boundsInRoot.top,1f)
    }

    @Test fun settingsUpwardScrollRemainsAnchoredAcrossAllSheetTypes() {
        val page=mutableStateOf("github")
        val settings=buildJsonObject {put("network",buildJsonObject {put("enabled",true);put("nodes",JsonArray(emptyList()))})}
        largeContent {when(page.value) {
            "github" -> GitHubTaskSettings(settings,false,{_,_->},{},{},"")
            "services" -> MultiServiceSettings(true,settings,false,{_,_->},{})
            "network" -> NetworkSettings(settings,false,{_,_->},{},"")
            else -> WorkbenchSettings(settings,false,{_,_->})
        }}
        for((kind,close) in listOf("github" to "关闭 GitHub 任务","services" to "关闭接入管理","network" to "关闭设置","basic" to "关闭设置")) {
            compose.runOnIdle {page.value=kind}
            if(kind=="basic") compose.onNodeWithText("时区与历史保留").performClick()
            assertContentDragDoesNotMoveSheet(close)
        }
    }

    @Test fun headerPullReturnsForShortDragAndDismissesAfterLongDrag() {
        val visible=mutableStateOf(true)
        var closes=0
        val server=buildJsonObject {put("name","测试服务器");put("status","up")}
        largeContent {if(visible.value) ServerDetail(server) {closes++;visible.value=false}}
        val handle=compose.onNodeWithContentDescription("下拉关闭窗口")
        handle.assertExists()
        val before=compose.onNodeWithContentDescription("关闭服务器详情").fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.autoAdvance=false
        try {
            handle.performTouchInput {
                down(center)
                moveBy(androidx.compose.ui.geometry.Offset(0f,25f),100)
                moveBy(androidx.compose.ui.geometry.Offset(0f,20f),100)
            }
            compose.mainClock.advanceTimeBy(32)
            val dragged=compose.onNodeWithContentDescription("关闭服务器详情").fetchSemanticsNode().boundsInRoot.top
            assertTrue("Header drag should follow the finger",dragged>before+5f)
            handle.performTouchInput {up()}
            compose.mainClock.advanceTimeBy(500)
        } finally {compose.mainClock.autoAdvance=true}
        compose.waitForIdle()
        compose.runOnIdle {assertTrue(visible.value);assertEquals(0,closes)}
        assertEquals(before,compose.onNodeWithContentDescription("关闭服务器详情").fetchSemanticsNode().boundsInRoot.top,1f)
        handle.performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f,80f),100)
            moveBy(androidx.compose.ui.geometry.Offset(0f,80f),100)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {assertFalse(visible.value);assertEquals(1,closes)}
        compose.onNodeWithContentDescription("关闭服务器详情").assertDoesNotExist()
    }

    @Test fun bodyPullReturnsForShortDragAndDismissesAfterLongDrag() {
        val visible=mutableStateOf(true)
        var closes=0
        largeContent {if(visible.value) ServerDetail(buildJsonObject {put("name","正文下拉测试");put("status","up")}) {closes++;visible.value=false}}
        val scroll=compose.onNode(hasScrollAction())
        val close=compose.onNodeWithContentDescription("关闭服务器详情")
        val before=close.fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.autoAdvance=false
        try {
            scroll.performTouchInput {
                down(center)
                moveBy(androidx.compose.ui.geometry.Offset(0f,25f),100)
                moveBy(androidx.compose.ui.geometry.Offset(0f,20f),100)
            }
            compose.mainClock.advanceTimeBy(32)
            assertTrue("Body at the top must pull the sheet",close.fetchSemanticsNode().boundsInRoot.top>before+5f)
            scroll.performTouchInput {up()}
            compose.mainClock.advanceTimeBy(500)
        } finally {compose.mainClock.autoAdvance=true}
        compose.waitForIdle()
        compose.runOnIdle {assertTrue(visible.value);assertEquals(0,closes)}
        assertEquals(before,close.fetchSemanticsNode().boundsInRoot.top,1f)
        compose.mainClock.autoAdvance=false
        try {
            scroll.performTouchInput {
                down(center)
                moveBy(androidx.compose.ui.geometry.Offset(0f,25f),100)
                moveBy(androidx.compose.ui.geometry.Offset(0f,20f),100)
                cancel()
            }
            compose.mainClock.advanceTimeBy(500)
        } finally {compose.mainClock.autoAdvance=true}
        compose.waitForIdle()
        assertEquals(before,close.fetchSemanticsNode().boundsInRoot.top,1f)
        compose.runOnIdle {assertEquals(0,closes)}
        scroll.performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f,80f),100)
            moveBy(androidx.compose.ui.geometry.Offset(0f,80f),100)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {assertFalse(visible.value);assertEquals(1,closes)}
    }

    @Test fun downwardBodyScrollReturnsToTopBeforePullingTheSheet() {
        var closes=0
        largeContent {ServerDetail(buildJsonObject {put("name","长内容滚动测试");put("status","up")}) {closes++}}
        val scroll=compose.onNode(hasScrollAction())
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) {it(0f,300f)}
        compose.waitForIdle()
        val initialScroll=scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("Fixture must start away from the top",initialScroll>80f)
        val close=compose.onNodeWithContentDescription("下拉关闭窗口")
        val before=close.fetchSemanticsNode().boundsInRoot.top
        scroll.performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f,30f),300)
            moveBy(androidx.compose.ui.geometry.Offset(0f,30f),300)
            up()
        }
        compose.waitForIdle()
        val afterScroll=scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("Downward movement must scroll the body first",afterScroll<initialScroll && afterScroll>0f)
        assertEquals(before,close.fetchSemanticsNode().boundsInRoot.top,1f)
        compose.runOnIdle {assertEquals(0,closes)}
        scroll.performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f,180f),100)
            moveBy(androidx.compose.ui.geometry.Offset(0f,180f),100)
            moveBy(androidx.compose.ui.geometry.Offset(0f,180f),100)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {assertEquals(1,closes)}
    }

    @Test fun bodyPullDismissesEachSettingsSheetFromItsContent() {
        val page=mutableStateOf("github")
        val visible=mutableStateOf(true)
        val settings=buildJsonObject {put("network",buildJsonObject {put("enabled",true);put("nodes",JsonArray(emptyList()))})}
        largeContent {if(visible.value) when(page.value) {
            "github" -> GitHubTaskSettings(settings,false,{_,_->},{visible.value=false},{},"")
            "services" -> MultiServiceSettings(true,settings,false,{_,_->},{visible.value=false})
            "network" -> NetworkSettings(settings,false,{_,_->},{visible.value=false},"")
            else -> WorkbenchSettings(settings,false,{_,_->})
        }}
        for((kind,close) in listOf("github" to "关闭 GitHub 任务","services" to "关闭接入管理","network" to "关闭设置","basic" to "关闭设置")) {
            compose.runOnIdle {page.value=kind;visible.value=true}
            if(kind=="basic") compose.onNodeWithText("时区与历史保留").performClick()
            compose.onNode(hasScrollAction()).performTouchInput {
                down(center)
                moveBy(androidx.compose.ui.geometry.Offset(0f,80f),100)
                moveBy(androidx.compose.ui.geometry.Offset(0f,80f),100)
                up()
            }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(close).assertDoesNotExist()
        }
    }

    @Test fun busyServiceSettingsPreventHeaderAndBodyDragDismissal() {
        var closes=0
        largeContent {MultiServiceSettings(true,buildJsonObject {},true,{_,_->},{closes++})}
        val handle=compose.onNodeWithContentDescription("下拉关闭窗口")
        handle.assertIsNotEnabled()
        val before=compose.onNodeWithContentDescription("关闭接入管理").fetchSemanticsNode().boundsInRoot.top
        handle.performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f,80f),100)
            moveBy(androidx.compose.ui.geometry.Offset(0f,80f),100)
            up()
        }
        compose.onNode(hasScrollAction()).performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f,80f),100)
            moveBy(androidx.compose.ui.geometry.Offset(0f,80f),100)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {assertEquals(0,closes)}
        assertEquals(before,compose.onNodeWithContentDescription("关闭接入管理").fetchSemanticsNode().boundsInRoot.top,1f)
    }

    @Test fun serverUpwardScrollDoesNotMoveTheEntireSheet() {
        val server=buildJsonObject {put("name","测试服务器");put("status","up");put("cpu",18);put("ram",42);put("disk",61);put("updated_at","2099-01-01T00:00:00Z")}
        largeContent {ServerDetail(server,"Asia/Shanghai") {}}
        assertContentDragDoesNotMoveSheet("关闭服务器详情")
        compose.onNodeWithText("更新于 01-01 08:00").performScrollTo().assertIsDisplayed()
    }

    @Test fun reminderUpwardScrollDoesNotMoveTheEntireSheet() {
        largeContent {ReminderDetailSheet(Reminder("r1","测试提醒","2099-01-01T00:00:00Z","pending","1"),"Asia/Shanghai",false,{}) {_,_,_->}}
        assertContentDragDoesNotMoveSheet("关闭详情")
        compose.onNodeWithText("取消提醒").performScrollTo().assertIsDisplayed()
    }

    @Test @Config(fontScale=1f) fun onePanelShowsReadableCumulativeTrafficAndExplainsUnavailableTemperature() {
        val server=buildJsonObject {
            put("name","测试服务器");put("status","up");put("provider","1panel");put("cpu",18);put("ram",42);put("disk",61)
            put("network",buildJsonObject {put("sent_bytes",1572864);put("received_bytes",2415919104L)})
            put("temperature",JsonNull);put("updated_at","2099-01-01T00:00:00Z")
        }
        largeContent {ServerDetail(server,"Asia/Shanghai") {}}
        compose.onNodeWithText("累计上传").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1.50 MiB").assertExists()
        compose.onNodeWithText("累计下载").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2.25 GiB").assertExists()
        compose.onNodeWithText("接口未提供").performScrollTo().assertIsDisplayed()
    }

    @Test fun serverDetailIncludesCollectedLoadNetworkAndMissingMetricStates() {
        val server=buildJsonObject {
            put("name","VPS-US");put("status","up");put("cpu",18.123456);put("ram",42);put("disk",61)
            put("load",buildJsonArray {add(0.123456);add(0.234567);add(0.345678)})
            put("network",12.56789);put("temperature",JsonNull)
            put("updated_at","2099-01-01T00:00:00Z")
        }
        largeContent {ServerDetail(server,"Asia/Shanghai") {}}
        compose.onNodeWithText("18.12%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("42.00%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0.12 / 0.23 / 0.35").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("12.57").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("未知").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("更新于 01-01 08:00").performScrollTo().assertIsDisplayed()
    }

    @Test fun dateAndClockWheelsSubmitAnAbsoluteInstantInTheWorkbenchTimezone() {
        var submitted: String?=null
        val zone=java.time.ZoneId.of("Pacific/Kiritimati")
        val expected=java.time.ZonedDateTime.now(zone).toLocalDate().plusDays(2).atTime(9,30).atZone(zone).toInstant().toString()
        compose.setContent {MaterialTheme {androidx.compose.foundation.layout.Column(Modifier.height(780.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {ReminderForm(false,zone.id) {_,time->submitted=time}}}}
        compose.onNode(hasSetTextAction()).performTextInput("喝水")
        compose.onNodeWithContentDescription("日期选择").performSemanticsAction(SemanticsActions.SetProgress) {it(2f)}
        compose.onNodeWithContentDescription("小时选择").performSemanticsAction(SemanticsActions.SetProgress) {it(9f)}
        compose.onNodeWithContentDescription("分钟选择").performSemanticsAction(SemanticsActions.SetProgress) {it(30f)}
        compose.onNodeWithText("创建提醒").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(expected,submitted)}
        compose.onNodeWithText("新建提醒").assertDoesNotExist()
        compose.onNodeWithText("多久后").assertDoesNotExist()
        compose.onNodeWithText("指定日期").assertDoesNotExist()
        compose.onNode(hasText("提醒事项") and hasSetTextAction()).assertExists()
    }

    @Test fun dateAndClockWheelsRejectPastTodayButAcceptTomorrowMidnight() {
        var result: String?=null
        val zone=java.time.ZoneId.of("Asia/Shanghai")
        val expected=java.time.ZonedDateTime.now(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toString()
        compose.setContent {MaterialTheme {androidx.compose.foundation.layout.Column(Modifier.height(780.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {ReminderForm(false,zone.id) {_,time->result=time}}}}
        compose.onNode(hasSetTextAction()).performTextInput("续费")
        compose.onNodeWithContentDescription("日期选择").performSemanticsAction(SemanticsActions.SetProgress) {it(0f)}
        compose.onNodeWithContentDescription("小时选择").performSemanticsAction(SemanticsActions.SetProgress) {it(0f)}
        compose.onNodeWithContentDescription("分钟选择").performSemanticsAction(SemanticsActions.SetProgress) {it(0f)}
        compose.onNodeWithText("创建提醒").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("日期选择").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) {it(1f)}
        compose.onNodeWithText("创建提醒").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(expected,result)}
    }

    @Test fun draggingTheMinuteWheelChangesTheAbsoluteTimeSubmittedByTheForm() {
        var submitted: String?=null
        val zone=java.time.ZoneId.of("Asia/Shanghai")
        val tomorrow=java.time.ZonedDateTime.now(zone).toLocalDate().plusDays(1)
        compose.setContent {MaterialTheme {androidx.compose.foundation.layout.Column(Modifier.height(780.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {ReminderForm(false,zone.id) {_,time->submitted=time}}}}
        compose.onNode(hasSetTextAction()).performTextInput("伸展")
        compose.onNodeWithContentDescription("日期选择").performSemanticsAction(SemanticsActions.SetProgress) {it(1f)}
        compose.onNodeWithContentDescription("小时选择").performSemanticsAction(SemanticsActions.SetProgress) {it(22f)}
        val wheel=compose.onNodeWithContentDescription("分钟选择")
        wheel.performSemanticsAction(SemanticsActions.SetProgress) {it(10f)}
        wheel.performScrollTo().performTouchInput {swipeUp(startY=centerY+30f,endY=centerY-80f,durationMillis=500)}
        compose.waitForIdle()
        val selected=wheel.fetchSemanticsNode().config[SemanticsProperties.StateDescription].substringBefore(' ').toInt()
        assertTrue("Dragging must change the selected minutes",selected > 10 && selected <= 59)
        compose.onNodeWithText("创建提醒").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(tomorrow.atTime(22,selected).atZone(zone).toInstant().toString(),submitted)}
    }

    @Test fun countdownStartsAtCreationPreservesBothModesAndRejectsZeroDuration() {
        var submitted: String?=null
        compose.setContent {MaterialTheme {Column(Modifier.height(780.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {ReminderForm(false,"Asia/Shanghai") {_,time->submitted=time}}}}
        compose.onNode(hasSetTextAction()).performTextInput("休息")
        compose.onNodeWithContentDescription("日期选择").performSemanticsAction(SemanticsActions.SetProgress) {it(1f)}
        compose.onNodeWithContentDescription("小时选择").performSemanticsAction(SemanticsActions.SetProgress) {it(9f)}
        compose.onNodeWithContentDescription("分钟选择").performSemanticsAction(SemanticsActions.SetProgress) {it(30f)}
        compose.onNodeWithText("切换倒计时").performScrollTo().performClick()
        compose.onNodeWithContentDescription("日期选择").assertDoesNotExist()
        for((label,value) in listOf("小时选择" to 0f,"分钟选择" to 0f,"秒钟选择" to 0f))
            compose.onNodeWithContentDescription(label).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) {it(value)}
        compose.onNodeWithText("创建提醒").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("秒钟选择").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) {it(45f)}
        compose.onNodeWithText("切换日期").performScrollTo().performClick()
        compose.onNodeWithContentDescription("小时选择").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"9 小时"))
        compose.onNodeWithContentDescription("分钟选择").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"30 分钟"))
        compose.onNodeWithText("切换倒计时").performScrollTo().performClick()
        compose.onNodeWithContentDescription("秒钟选择").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"45 秒钟"))
        val before=java.time.Instant.now()
        compose.onNodeWithText("创建提醒").performScrollTo().performClick()
        val after=java.time.Instant.now()
        compose.runOnIdle {
            val target=java.time.Instant.parse(submitted!!)
            assertFalse(target.isBefore(before.plusSeconds(45)))
            assertFalse(target.isAfter(after.plusSeconds(45)))
        }
    }
}
