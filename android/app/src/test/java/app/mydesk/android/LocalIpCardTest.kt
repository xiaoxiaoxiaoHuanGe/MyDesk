package app.mydesk.android

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class,qualifiers="w360dp-h800dp",fontScale=2f)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalIpCardTest {
    @get:Rule val compose=createComposeRule()

    @Test fun threeExitRowsKeepSuccessAndTimeoutVisibleWithLargeText() {
        val exits=localIpEndpoints.mapIndexed {index,endpoint->
            when(index) {
                0->IpExitResult(endpoint,IpExitStatus.SUCCESS,"203.0.113.42","中国 广东 深圳")
                1->IpExitResult(endpoint,IpExitStatus.TIMEOUT)
                else->IpExitResult(endpoint,IpExitStatus.FAILED)
            }
        }
        compose.setContent {
            MaterialTheme(colorScheme=deskColors(false),shapes=deskShapes,typography=deskTypography) {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {LocalIpCard(LocalIpState(exits=exits),"Asia/Shanghai",{})}
            }
        }
        compose.onNodeWithText("本机 IP").assertExists()
        compose.onNodeWithText("国内出口").assertExists()
        compose.onNodeWithText("203.0.113.42").assertExists()
        compose.onNodeWithText("中国 广东 深圳").assertExists()
        compose.onNodeWithText("国外出口").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("超时").assertExists()
        compose.onNodeWithText("Google 出口").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("查询失败").assertExists()
    }
}
