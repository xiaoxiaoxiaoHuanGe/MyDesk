package app.mydesk.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

private object DigestPhotos {
    private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private val cache=object:LruCache<String,Bitmap>(20*1024*1024) {
        override fun sizeOf(key: String,value: Bitmap)=value.allocationByteCount
    }
    suspend fun load(raw: String, large: Boolean): Bitmap?=withContext(Dispatchers.IO) {
        val url=raw.toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.port==443 && it.host=="pbs.twimg.com" && it.username.isEmpty() && it.password.isEmpty() &&
                it.encodedPath.matches(Regex("/media/[A-Za-z0-9_.-]+"))
        } ?: return@withContext null
        val key="$raw:$large"
        cache.get(key)?.let {return@withContext it}
        val sized=url.newBuilder().setQueryParameter("name",if(large) "large" else "small").build()
        val bytes=runCatching {
            client.newCall(Request.Builder().url(sized).build()).execute().use { response ->
                val body=response.body ?: return@use null
                val max=8L*1024*1024
                if(!response.isSuccessful || body.contentLength()>max || body.contentType()?.type!="image") return@use null
                val source=body.source()
                val buffer=Buffer()
                while(buffer.size<=max && source.read(buffer,minOf(16384,max+1-buffer.size))!=-1L) {}
                if(buffer.size>max) null else buffer.readByteArray()
            }
        }.getOrNull() ?: return@withContext null
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth<1 || bounds.outHeight<1 || maxOf(bounds.outWidth,bounds.outHeight)>32768) return@withContext null
        val target=if(large) 1920 else 720
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>target) sample*=2
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample})
            ?.also {cache.put(key,it)}
    }
}

@Composable internal fun DigestPhoto(url: String, label: String, modifier: Modifier=Modifier, large: Boolean=false,
    imageModifier: Modifier=Modifier, scale: ContentScale=ContentScale.Crop) {
    var attempt by remember(url) {mutableIntStateOf(0)}
    val image by produceState<Pair<Bitmap?,Boolean>>(null to true,url,large,attempt) {
        value=null to true
        value=DigestPhotos.load(url,large) to false
    }
    Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=MaterialTheme.shapes.medium,modifier=modifier) {
        Box(contentAlignment=Alignment.Center) {
            val bitmap=image.first
            when {
                bitmap!=null -> Image(bitmap.asImageBitmap(),label,Modifier.fillMaxSize().then(imageModifier),contentScale=scale)
                image.second -> CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)
                else -> TextButton(onClick={attempt++}) {Text("图片加载失败 · 重试")}
            }
        }
    }
}
