package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject

@Composable internal fun NotificationAccessCard(unread: String,onSources: ()->Unit,onInbox: ()->Unit) {
    DeskCard("通知接入") {
        DeskSettingRow("通知来源","管理接收地址与来源",onSources,icon=SettingsGlyphs.Bell)
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        DeskSettingRow("通知收件箱",if(unread.toLongOrNull()?.let {it>0}==true) "$unread 条未读" else "查看收到的消息",onInbox,icon=SettingsGlyphs.Mail)
    }
}

@Composable internal fun InboxSheetHeader(title: String,close: ()->Unit,back: (()->Unit)?=null,busy: Boolean=false) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        if(back!=null) IconButton(back,enabled=!busy) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回列表")}
        Text(title,Modifier.weight(1f).semantics {heading()},style=MaterialTheme.typography.titleLarge)
        IconButton(close,enabled=!busy) {Icon(Icons.Default.Close,"关闭$title")}
    }
}

@Composable internal fun InboxHint(text: String) {
    Text(text,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable internal fun InboxMessageCard(row: JsonObject,zone: String,onOpen: ()->Unit) {
    Surface(onClick=onOpen,shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(row.text("title"),Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(row.text("read_at","").isBlank()) DeskStateBadge("未读")
            }
            val summary=row.text("summary","")
            if(summary.isNotBlank()) Text(summary,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    InboxHint(row.text("source_name"))
                    InboxHint(date(row.text("received_at"),zone))
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable internal fun NotificationSourceCard(row: JsonObject,zone: String,onOpen: ()->Unit,enabled: Boolean) {
    Surface(onClick=onOpen,enabled=enabled,shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(row.text("name"),Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                DeskStateBadge(if(row.text("enabled")=="1") "已启用" else "已停用")
                Icon(Icons.AutoMirrored.Filled.ArrowForward,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            InboxHint(sourceReceivedLabel(row,zone))
        }
    }
}

internal fun sourceReceivedLabel(row: JsonObject,zone: String): String {
    val received=row.text("last_received_at","")
    return if(received.isBlank() || received=="null") "尚未收到消息" else "最近接收 · ${date(received,zone)}"
}
