package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Small, themeable vector glyphs; no bitmap icons or additional icon library. */
internal object SettingsGlyphs {
    private fun glyph(name: String, data: String)=ImageVector.Builder(name,24.dp,24.dp,24f,24f)
        .addPath(PathParser().parsePathString(data).toNodes(),stroke=SolidColor(Color.Black),
            strokeLineWidth=1.8f,strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round).build()
    val Moon=glyph("Moon","M20.5 14.4A8.8 8.8 0 0 1 9.6 3.5A9 9 0 1 0 20.5 14.4Z")
    val Monitor=glyph("Monitor","M4 3H20Q21 3 21 4V15Q21 16 20 16H4Q3 16 3 15V4Q3 3 4 3Z M12 16V21 M8 21H16")
    val Sun=glyph("Sun","M17 12A5 5 0 1 1 7 12A5 5 0 1 1 17 12 M12 1V3 M12 21V23 M1 12H3 M21 12H23 M4.2 4.2L5.6 5.6 M18.4 18.4L19.8 19.8 M4.2 19.8L5.6 18.4 M18.4 5.6L19.8 4.2")
    val Sync=glyph("Sync","M20 7V3L17 6C13 2 6 4 4 9 M4 17V21L7 18C11 22 18 20 20 15 M20 3H16 M4 21H8")
    val Password=glyph("Password","M14 3C10.7 3 8 5.7 8 9C8 10 8.2 10.8 8.6 11.6L3 17.2V21H7V18H10V15L12.4 12.6C13 12.9 13.5 13 14 13C17.3 13 20 10.8 20 8C20 5.2 17.3 3 14 3Z M15 7H15.01")
    val Logout=glyph("Logout","M10 3H5V21H10 M14 3H20V21H14 M3 12H15 M11 8L15 12L11 16")
    val Phone=glyph("Phone","M7 2H17Q19 2 19 4V20Q19 22 17 22H7Q5 22 5 20V4Q5 2 7 2Z M11 18H13")
    val Bell=glyph("Bell","M18 8C18 4.7 15.3 2 12 2C8.7 2 6 4.7 6 8V14L4 17H20L18 14Z M10 21H14")
    val Clock=glyph("Clock","M22 12A10 10 0 1 1 2 12A10 10 0 1 1 22 12 M12 6V12L16 14")
    val Network=glyph("Network","M22 12A10 10 0 1 1 2 12A10 10 0 1 1 22 12 M2 12H22 M4 6H20 M4 18H20 M12 2C6 8 6 16 12 22C18 16 18 8 12 2Z")
    val Mail=glyph("Mail","M3 4H21V20H3Z M3 5L12 12L21 5")
    val Backup=glyph("Backup","M3 4H21V20H3Z M7 4V10H17V4 M7 20V14H17V20 M14 7H15")
    val Server=glyph("Server","M3 3H21V10H3Z M3 14H21V21H3Z M7 6.5H7.01 M7 17.5H7.01 M12 6.5H17 M12 17.5H17")
    val GitHub=glyph("GitHub","M9 21V17C6 18 5 16 4 15 M15 21V17C15 16 14.7 15.3 14 15C18 14.5 20 13 20 9C20 7.5 19.5 6.2 18.5 5.3L18 2L14.5 3.5C13 3 11 3 9.5 3.5L6 2L5.5 5.3C4.5 6.2 4 7.5 4 9C4 13 6 14.5 10 15C9.3 15.3 9 16 9 17")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DeskIconAction(label: String,icon: ImageVector,onClick: ()->Unit,
    modifier: Modifier=Modifier,enabled: Boolean=true,loading: Boolean=false) {
    TooltipBox(positionProvider=TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip={PlainTooltip {Text(if(loading) "同步中…" else label)}},state=rememberTooltipState(),enableUserInput=enabled) {
        FilledTonalIconButton(onClick,enabled=enabled,modifier=modifier.size(48.dp).semantics {
            contentDescription=label
            if(loading) stateDescription="同步中"
        },colors=IconButtonDefaults.filledTonalIconButtonColors(
            containerColor=MaterialTheme.colorScheme.surfaceContainerLow,contentColor=MaterialTheme.colorScheme.primary)) {
            if(loading) CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp,color=MaterialTheme.colorScheme.primary)
            else Icon(icon,contentDescription=null,modifier=Modifier.size(24.dp))
        }
    }
}

@Composable internal fun AccountSettingsCard(server: String,deviceName: String,busy: Boolean,syncing: Boolean,
    sync: ()->Unit,logout: ()->Unit,rename: (String,()->Unit)->Unit,changePassword: (String,String)->Unit,
    error: String="",passwordError: String="") {
    DeskCard("") {
        val fontScale=LocalDensity.current.fontScale
        val actions: @Composable ()->Unit={
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                DeskIconAction("立即同步",SettingsGlyphs.Sync,sync,enabled=!busy && !syncing,loading=syncing)
                PasswordSettings(busy || syncing,changePassword,passwordError)
                DeskIconAction("退出账号",SettingsGlyphs.Logout,logout,enabled=!busy && !syncing)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if(maxWidth >= (110f*fontScale+164f).dp) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("连接与账号",Modifier.weight(1f).semantics {heading()},style=MaterialTheme.typography.titleMedium)
                    actions()
                }
            } else Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("连接与账号",Modifier.semantics {heading()},style=MaterialTheme.typography.titleMedium)
                Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.CenterEnd) {actions()}
            }
        }
        Text(server,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        DeviceNameSettings(deviceName,busy || syncing,rename,error,embedded=true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ThemePreference(theme: String,onSelect: (String)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("外观",style=MaterialTheme.typography.titleSmall)
        Surface(shape=CircleShape,color=MaterialTheme.colorScheme.surface,
            border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth()) {
            Row(Modifier.padding(4.dp).selectableGroup(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                val choices=listOf(Triple("dark","深色",SettingsGlyphs.Moon),
                    Triple("system","跟随系统",SettingsGlyphs.Monitor),Triple("light","浅色",SettingsGlyphs.Sun))
                choices.forEach {(value,label,icon)->
                    val selected=theme==value
                    Box(Modifier.weight(1f)) {
                        TooltipBox(positionProvider=TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                            tooltip={PlainTooltip {Text(label)}},state=rememberTooltipState()) {
                            Surface(shape=CircleShape,
                                color=if(selected) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
                                border=if(selected) BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant) else null) {
                                Box(Modifier.fillMaxWidth().heightIn(min=48.dp)
                                    .selectable(selected=selected,role=Role.RadioButton,onClick={onSelect(value)})
                                    .semantics {contentDescription=label},contentAlignment=Alignment.Center) {
                                    Icon(icon,contentDescription=null,modifier=Modifier.size(24.dp),
                                        tint=if(selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
