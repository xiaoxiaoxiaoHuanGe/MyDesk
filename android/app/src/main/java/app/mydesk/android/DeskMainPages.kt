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
import androidx.compose.ui.unit.dp

@Composable internal fun DeskMainPages(state: PagerState,modifier: Modifier=Modifier,
    userScrollEnabled: Boolean=true,content: @Composable (Int)->Unit) {
    HorizontalPager(state=state,modifier=modifier,key={it},beyondViewportPageCount=1,
        userScrollEnabled=userScrollEnabled) {page->content(page)}
}

@Composable internal fun DeskMainTabs(selectedPage: Int,onSelect: (Int)->Unit,modifier: Modifier=Modifier) {
    NavigationBar(modifier=modifier,containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
        listOf("工作台","提醒","设置").forEachIndexed {index,title->
            NavigationBarItem(selected=selectedPage==index,onClick={onSelect(index)},label={Text(title)},
                icon={Icon(listOf(Icons.Default.Home,Icons.AutoMirrored.Filled.List,Icons.Default.Settings)[index],contentDescription=null)})
        }
    }
}
