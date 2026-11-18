package app.mydesk.android

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal object DeskPage {
    const val WORKBENCH=0
    const val DIGEST=1
    const val REMINDERS=2
    const val STEPS=3
    const val SETTINGS=4
    const val COUNT=5
    val titles=listOf("MyDesk","X·速览","即时提醒","步数","MyDesk 设置")
}

@Composable internal fun DeskMainPages(state: PagerState,modifier: Modifier=Modifier,
    userScrollEnabled: Boolean=true,content: @Composable (Int)->Unit) {
    HorizontalPager(state=state,modifier=modifier,key={it},beyondViewportPageCount=1,
        userScrollEnabled=userScrollEnabled) {page->content(page)}
}

@Composable internal fun DeskMainTabs(selectedPage: Int,onSelect: (Int)->Unit,modifier: Modifier=Modifier) {
    NavigationBar(modifier=modifier,containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
        listOf("工作台","X·速览","提醒","步数","设置").forEachIndexed {index,title->
            NavigationBarItem(selected=selectedPage==index,onClick={onSelect(index)},
                label={Text(title,maxLines=1,overflow=TextOverflow.Ellipsis)},
                icon={Icon(listOf(Icons.Default.Home,DigestIcon,Icons.AutoMirrored.Filled.List,StepsIcon,Icons.Default.Settings)[index],contentDescription=null)})
        }
    }
}
