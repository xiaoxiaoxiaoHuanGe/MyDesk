package app.mydesk.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

private fun JsonObject.photos()=(get("images") as? JsonArray).orEmpty().mapNotNull {(it as? JsonPrimitive)?.contentOrNull}
private fun JsonObject.photoCopy(source: String)=obj("media").text(source)
private fun JsonObject.headline()=text("title").ifBlank {text("summary").ifBlank {text("translated_preview").ifBlank {text("preview")}}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun XDigestPage(model: XDigestModel, list: LazyListState, active: Boolean, zone: String, open: (String)->Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var search by rememberSaveable {mutableStateOf(false)}
    var keyword by rememberSaveable {mutableStateOf("")}
    var dates by rememberSaveable {mutableStateOf(false)}
    LaunchedEffect(active) {if(active) model.start()}
    LaunchedEffect(list,state.before,state.working,state.error,active) {
        if(active && state.before!=null && !state.working && state.error.isBlank()) {
            snapshotFlow {
                val visible=list.layoutInfo.visibleItemsInfo
                visible.isNotEmpty() && visible.last().index>=list.layoutInfo.totalItemsCount-3
            }.distinctUntilChanged().collect { if(it) model.load(append=true) }
        }
    }
    fun filter(value: DigestFilter) {
        model.load(value)
        scope.launch {list.scrollToItem(0)}
    }
    PullToRefreshBox(isRefreshing=state.working && state.items.isNotEmpty() && list.firstVisibleItemIndex==0,
        onRefresh={model.load()},modifier=Modifier.fillMaxSize()) {
        LazyColumn(state=list,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(18.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item("header") {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("X·速览",style=MaterialTheme.typography.headlineMedium)
                        val updated=state.updated.takeIf {it.isNotBlank()}?.let { date(it,zone) }.orEmpty()
                        val subtitle=listOfNotNull(updated.takeIf {it.isNotBlank()}?.let {"更新于 $it"},
                            state.unread.takeIf {it>0}?.let {"$it 条未读"}).joinToString(" · ")
                        if(subtitle.isNotEmpty()) Text(subtitle,style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick={model.load()},enabled=!state.working) {Icon(Icons.Default.Refresh,"刷新资讯")}
                }
            }
            item("filters") {
                Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(state.filter.category.isEmpty(),{filter(state.filter.copy(category=""))},{Text("全部")}) }
                        items(state.categories,key={it.text("id")}) { category ->
                            val id=category.text("id")
                            FilterChip(state.filter.category==id,{filter(state.filter.copy(category=id))},{Text(category.text("label"))})
                        }
                    }
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(state.filter.saved,{filter(state.filter.copy(saved=!state.filter.saved))},{Text("收藏")}) }
                        item { FilterChip(state.filter.date.isNotEmpty(),{dates=true},{Text(state.filter.date.ifBlank {"日期"})}) }
                        item { FilterChip(search || state.filter.query.isNotBlank(),{search=!search},{Text("搜索")}) }
                    }
                    if(search) Row(verticalAlignment=Alignment.CenterVertically) {
                        OutlinedTextField(keyword,{keyword=it.take(120)},Modifier.weight(1f),singleLine=true,
                            label={Text("标题、摘要或原文")})
                        TextButton({filter(state.filter.copy(query=keyword)); search=false}) {Text(if(keyword.isBlank()) "清除" else "搜索")}
                    } else if(state.filter.query.isNotBlank()) TextButton({
                        keyword="";filter(state.filter.copy(query=""))
                    }) {Text("清除搜索：${state.filter.query}")}
                }
            }
            if(state.error.isNotEmpty()) item("error") {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(if(state.items.isEmpty()) state.error else "离线浏览 · ${state.error}",
                        Modifier.weight(1f),color=MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton({model.load()},enabled=!state.working){Text("重试")}
                }
            }
            if(state.working && state.items.isEmpty()) item("loading") {LinearProgressIndicator(Modifier.fillMaxWidth())}
            if(!state.working && state.items.isEmpty() && state.error.isEmpty() && active) item("empty") {
                DeskCard(if(state.filter==DigestFilter()) "暂无资讯" else "没有符合条件的帖子") {}
            }
            items(state.items,key={it.text("id")}) { post ->
                val category=state.categories.firstOrNull {it.text("id")==post.text("category")}?.text("label").orEmpty()
                DigestFeedCard(post,category,zone) {model.open(post)}
            }
            if(state.before!=null) item("more") {
                if(state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                else TextButton({model.load(append=true)}) {Text("加载更早资讯")}
            }
        }
    }
    if(active && dates) {
        val picker=rememberDatePickerState(initialSelectedDateMillis=runCatching {
            LocalDate.parse(state.filter.date).toEpochDay()*86_400_000
        }.getOrNull())
        DatePickerDialog(onDismissRequest={dates=false},confirmButton={TextButton({
            val selected=picker.selectedDateMillis?.let {LocalDate.ofEpochDay(Math.floorDiv(it,86_400_000L)).toString()}.orEmpty()
            dates=false;filter(state.filter.copy(date=selected))
        }) {Text("确定")}},dismissButton={TextButton({dates=false;filter(state.filter.copy(date=""))}) {Text("全部日期")}}) {
            DatePicker(picker)
        }
    }
    if(active) state.detail?.let { DigestDetail(it,state.detailBusy,state.detailError,zone,model::close,model::bookmark,{model.open(it)},open) }
}

@Composable private fun DigestFeedCard(post: JsonObject, category: String, zone: String, onClick: ()->Unit) {
    val seen=post.text("read")=="true"
    Card(onClick=onClick,modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(listOfNotNull(category.takeIf {it.isNotBlank()}, "未读".takeUnless {seen},
                "已收藏".takeIf {post.text("saved")=="true"}).joinToString(" · "),
                style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            Text(post.headline(),style=MaterialTheme.typography.titleMedium,maxLines=4,overflow=TextOverflow.Ellipsis)
            post.photos().firstOrNull()?.let { DigestPhoto(it,"帖子配图",Modifier.fillMaxWidth().aspectRatio(1.95f),saved=post.photoCopy(it)) }
            DigestByline(post,zone)
        }
    }
}

@Composable private fun DigestByline(post: JsonObject,zone: String) {
    val author=post.text("author")
    val name=post.text("name")
    Text(listOf(if(name.isBlank()) "@$author" else "$name @$author",date(post.text("posted_at"),zone),
        "${post.text("likes")} 赞").joinToString(" · "),style=MaterialTheme.typography.bodySmall,
        color=MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DigestDetail(post: JsonObject, busy: Boolean, error: String, zone: String,
    close: ()->Unit, bookmark: ()->Unit, retry: ()->Unit, open: (String)->Unit) {
    val id=post.text("id")
    var original by rememberSaveable(id) {mutableStateOf(false)}
    var photo by rememberSaveable(id) {mutableIntStateOf(-1)}
    val images=post.photos()
    DeskModalSheet(onDismissRequest=close) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.9f),contentPadding=PaddingValues(20.dp),
            verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(post.text("title").ifBlank {"帖子详情"},Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    TextButton(bookmark,enabled=!busy) {Text(if(post.text("saved")=="true") "取消收藏" else "收藏")}
                }
                DigestByline(post,zone)
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=8.dp))
                if(error.isNotBlank()) {Text(error,color=MaterialTheme.colorScheme.error);TextButton(retry) {Text("重试加载")}}
            }
            if(post.text("summary").isNotBlank()) item {
                Text("AI 摘要",style=MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp)); SelectionContainer {Text(post.text("summary"))}
            }
            item {
                val translation=post.text("translation").takeUnless {it==post.text("text")}.orEmpty()
                val showOriginal=original || translation.isBlank()
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(if(showOriginal) "原帖正文" else "中文翻译",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    if(translation.isNotBlank()) TextButton({original=!original}) {Text(if(showOriginal) "看翻译" else "看原文")}
                }
                SelectionContainer {Text(if(showOriginal) post.text("text").ifBlank {post.text("preview")} else translation)}
            }
            items(images.size,key={it}) { i ->
                DigestPhoto(images[i],"第 ${i+1} 张配图",Modifier.fillMaxWidth().aspectRatio(1.5f)
                    .clickable(onClickLabel="放大第 ${i+1} 张配图") {photo=i},scale=ContentScale.Fit,saved=post.photoCopy(images[i]))
            }
            if(post.text("video")=="true") item {Text("原帖含视频",style=MaterialTheme.typography.bodySmall)}
            item {
                val author=post.text("author")
                if(id.matches(Regex("[0-9]{1,24}")) && author.matches(Regex("[A-Za-z0-9_]{1,30}"))) {
                    OutlinedButton({open("https://x.com/$author/status/$id")}) {Text("在 X 查看原帖")}
                }
            }
        }
    }
    if(photo in images.indices) DigestGallery(images,images.map(post::photoCopy),photo) {photo=-1}
}

@Composable private fun DigestGallery(images: List<String>, saved: List<String>, start: Int, close: ()->Unit) {
    val pager=rememberPagerState(initialPage=start,pageCount={images.size})
    var zoomed by remember {mutableStateOf(false)}
    LaunchedEffect(pager.currentPage) {zoomed=false}
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("${pager.currentPage+1} / ${images.size}",Modifier.weight(1f))
                    TextButton(close) {Text("关闭")}
                }
                HorizontalPager(pager,Modifier.weight(1f).fillMaxWidth(),userScrollEnabled=!zoomed) { index ->
                    var zoom by remember {mutableFloatStateOf(1f)}
                    var offset by remember {mutableStateOf(Offset.Zero)}
                    val transform=rememberTransformableState { change,pan,_ ->
                        zoom=(zoom*change).coerceIn(1f,4f)
                        offset=if(zoom==1f) Offset.Zero else offset+pan
                        zoomed=zoom>1f
                    }
                    DigestPhoto(images[index],"第 ${index+1} 张配图",Modifier.fillMaxSize().transformable(transform,canPan={zoom>1f}),
                        large=true,saved=saved[index],scale=ContentScale.Fit,imageModifier=Modifier.graphicsLayer {
                            scaleX=zoom;scaleY=zoom;translationX=offset.x;translationY=offset.y
                        })
                }
            }
        }
    }
}
