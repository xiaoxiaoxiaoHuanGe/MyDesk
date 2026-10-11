package app.mydesk.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val MAX_UPDATE_BYTES=200L*1024*1024
internal fun sha256(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
internal data class UpdateInfo(val code: Int,val name: String,val minSdk: Int,val artifact: String,val size: Long,val sha: String,val notes: String,val channel: String) {
    val path="/api/app-update/artifacts/$artifact.apk"
    companion object {
        fun parse(value: JsonObject,channel: String): UpdateInfo? {
            require(value["available"] is JsonPrimitive && !value["available"]!!.jsonPrimitive.isString && value["available"]!!.jsonPrimitive.booleanOrNull!=null)
            if(!value["available"]!!.jsonPrimitive.boolean)return null
            fun integer(key: String)=value[key]?.jsonPrimitive?.takeUnless {it.isString}?.longOrNull ?: error("更新元数据无效")
            val code=integer("version_code");val sdk=integer("min_sdk");val size=integer("size_bytes")
            require(code in 1..Int.MAX_VALUE.toLong() && sdk in 26..10000 && size in 1..MAX_UPDATE_BYTES)
            require(value.text("package_name")==BuildConfig.APPLICATION_ID && value.text("channel")==channel)
            require(value["version_name"]?.jsonPrimitive?.isString==true && value.text("version_name").length in 1..100 && value["notes"]?.jsonPrimitive?.isString==true && value.text("notes").length<=20000)
            val artifact=value.text("artifact_id");val sha=value.text("sha256")
            require(artifact.matches(Regex("[a-f0-9]{64}")) && sha.matches(Regex("[a-f0-9]{64}")) && artifact==sha)
            require(value.text("apk_path")=="/api/app-update/artifacts/$artifact.apk")
            return UpdateInfo(code.toInt(),value.text("version_name"),sdk.toInt(),artifact,size,sha,value.text("notes"),channel)
        }
    }
}
internal fun validateUpdatePackage(info: UpdateInfo,packageName: String,version: Long,versionName: String?,minSdk: Int?,installedPackage: String,installedCode: Int,deviceSdk: Int,oldSignatures: Set<String>,newSignatures: Set<String>) {
    require(packageName==installedPackage && version==info.code.toLong() && version>installedCode && versionName==info.name && minSdk==info.minSdk && info.minSdk<=deviceSdk) {"APK 包名、版本或系统要求不符"}
    require(oldSignatures.isNotEmpty() && oldSignatures==newSignatures) {"APK 签名不兼容，不能覆盖安装"}
}
internal data class UpdateState(val info: UpdateInfo?=null,val checking: Boolean=false,val downloading: Boolean=false,val bytes: Long=0,
    val ready: Boolean=false,val message: String="",val prompt: Boolean=false)

/** Owned by AppGraph, independent of composable/page lifetimes and the global busy flag. */
internal class AppUpdateController(private val context: Context,private val scope: CoroutineScope,private val api: ()->MyDeskApi?,private val expired: suspend (ApiError)->Unit) {
    private val mutable=MutableStateFlow(UpdateState());val state=mutable.asStateFlow()
    private val directory=File(context.filesDir,"app-updates").apply {mkdirs();listFiles()?.forEach {it.delete()}}
    private val preferences=context.getSharedPreferences("app-update-checks",Context.MODE_PRIVATE)
    private var checkJob: Job?=null;private var downloadJob: Job?=null
    @Volatile private var generation=0;private var failures=0;private var nextCheck=0L
    private val prompted=mutableSetOf<Int>()
    private val channel=if(BuildConfig.DEBUG) "debug" else "release"
    private fun token(client: MyDeskApi)=sha256((client.base+"|"+client.vault.cookie+"|"+channel).toByteArray())
    private fun current(client: MyDeskApi,key: String,epoch: Int)=epoch==generation && api()===client && token(client)==key && client.vault.cookie.isNotEmpty()
    @Synchronized fun reset() {generation++;checkJob?.cancel();downloadJob?.cancel();directory.listFiles()?.forEach {it.delete()};mutable.value=UpdateState();prompted.clear();failures=0;nextCheck=0}
    fun dismissPrompt() {mutable.update {it.copy(prompt=false)}}
    @Synchronized fun check(manual: Boolean=false) {
        val client=api() ?: return
        if(client.vault.cookie.isEmpty() || checkJob?.isActive==true || downloadJob?.isActive==true)return
        val now=System.currentTimeMillis();val key=token(client)
        if(!manual && (now<nextCheck || now-preferences.getLong(key,0)<6*3600*1000L))return
        val epoch=generation
        checkJob=scope.launch {
            mutable.update {it.copy(checking=true,message="正在检查更新")}
            try {
                val info=UpdateInfo.parse(client.request("/api/app-update?channel=$channel").jsonObject,channel)
                if(!current(client,key,epoch))return@launch
                preferences.edit().putLong(key,System.currentTimeMillis()).apply();failures=0;nextCheck=0
                val newer=info?.takeIf {it.code>BuildConfig.VERSION_CODE}
                if(newer?.artifact!=mutable.value.info?.artifact) {directory.listFiles()?.forEach {it.delete()};mutable.value=UpdateState()}
                mutable.update {it.copy(info=newer,message=when {newer==null->"当前没有可用更新";newer.minSdk>Build.VERSION.SDK_INT->"新版本需要更高的 Android 系统";else->"发现新版本 ${newer.name}"},prompt=newer!=null && newer.minSdk<=Build.VERSION.SDK_INT && prompted.add(newer.code))}
            } catch(cancel: CancellationException) {throw cancel}
            catch(error: Exception) {
                if(error is ApiError && error.status in setOf(401,403)) {expired(error);return@launch}
                if(epoch==generation) {
                    failures++;nextCheck=System.currentTimeMillis()+minOf(6*3600_000L,60_000L*(1L shl minOf(failures,8)))
                    mutable.update {it.copy(message=if(error is ApiError && error.status==404) "服务器暂未提供应用更新" else error.message ?: "检查失败，可手动重试")}
                }
            } finally {if(epoch==generation) mutable.update {it.copy(checking=false)}}
        }
    }
    @Synchronized fun download() {
        val info=mutable.value.info ?: return;val client=api() ?: return
        if(info.code<=BuildConfig.VERSION_CODE || info.minSdk>Build.VERSION.SDK_INT || downloadJob?.isActive==true || checkJob?.isActive==true)return
        val key=token(client);val epoch=generation
        downloadJob=scope.launch {
            mutable.update {it.copy(downloading=true,ready=false,bytes=0,message="正在下载")}
            val temp=File(directory,"${info.artifact}.$epoch.part");val target=File(directory,"${info.artifact}.apk")
            try {
                require(directory.usableSpace>info.size+10*1024*1024) {"磁盘空间不足"}
                stream(client,info,temp) {bytes->if(current(client,key,epoch))mutable.update {it.copy(bytes=bytes)}}
                ensureActive()
                withContext(Dispatchers.IO) {verifyPackage(temp,info)}
                ensureActive();check(current(client,key,epoch)) {"会话已变化，请重新检查更新"}
                check(temp.renameTo(target)) {"无法保存已校验更新"}
                mutable.update {it.copy(ready=true,message="文件已校验，点击安装由系统确认")}
            } catch(cancel: CancellationException) {throw cancel}
            catch(error: Exception) {
                if(error is ApiError && error.status in setOf(401,403)) {expired(error);return@launch}
                if(epoch==generation) {target.delete();mutable.update {it.copy(ready=false,message=error.message ?: "下载失败，请重试")}}
            } finally {temp.delete();if(epoch==generation)mutable.update {it.copy(downloading=false)}}
        }
    }
    private suspend fun stream(client: MyDeskApi,info: UpdateInfo,file: File,progress: (Long)->Unit)=suspendCancellableCoroutine<Unit> {continuation->
        val transport=client.client.newBuilder().callTimeout(10,TimeUnit.MINUTES).readTimeout(30,TimeUnit.SECONDS).build()
        val request=Request.Builder().url(client.base+info.path).header("Cookie",client.vault.cookie).header("Accept","application/vnd.android.package-archive").build()
        val call=transport.newCall(request);continuation.invokeOnCancellation {call.cancel()}
        call.enqueue(object: Callback {
            override fun onFailure(call: Call,error: IOException) {if(continuation.isActive)continuation.resumeWithException(error)}
            override fun onResponse(call: Call,response: Response) {
                try {
                    response.use {
                        if(response.code!=200)throw ApiError(response.code,"APK 下载失败 (${response.code})")
                        require(response.header("Content-Type")?.substringBefore(';')=="application/vnd.android.package-archive") {"服务器未返回 APK"}
                        val body=response.body ?: error("下载内容为空")
                        require(body.contentLength()==-1L||body.contentLength()==info.size) {"下载大小不符"}
                        val digest=MessageDigest.getInstance("SHA-256");var total=0L
                        body.byteStream().use {input->file.outputStream().use {output->
                            val buffer=ByteArray(64*1024)
                            while(true) {
                                if(!continuation.isActive)throw IOException("下载已取消")
                                val count=input.read(buffer);if(count<0)break
                                total+=count;require(total<=info.size && total<=MAX_UPDATE_BYTES) {"下载超过大小限制"}
                                output.write(buffer,0,count);digest.update(buffer,0,count);progress(total)
                            }
                            output.fd.sync()
                        }}
                        require(total==info.size && digest.digest().joinToString("") {"%02x".format(it)}==info.sha) {"APK 大小或摘要不符"}
                    }
                    if(continuation.isActive)continuation.resume(Unit) else file.delete()
                } catch(error: Exception) {file.delete();if(continuation.isActive)continuation.resumeWithException(error)}
            }
        })
    }
    @Suppress("DEPRECATION") private fun verifyPackage(file: File,info: UpdateInfo) {
        val flags=if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive=context.packageManager.getPackageArchiveInfo(file.absolutePath,flags) ?: error("APK 无法解析")
        val installed=context.packageManager.getPackageInfo(context.packageName,flags)
        val version=if(Build.VERSION.SDK_INT>=28) archive.longVersionCode else archive.versionCode.toLong()
        fun certificates(value: android.content.pm.PackageInfo)=if(Build.VERSION.SDK_INT>=28) value.signingInfo?.apkContentsSigners else value.signatures
        val old=certificates(installed)?.map {sha256(it.toByteArray())}?.toSet() ?: error("无法读取当前签名")
        val new=certificates(archive)?.map {sha256(it.toByteArray())}?.toSet() ?: error("无法读取更新签名")
        validateUpdatePackage(info,archive.packageName,version,archive.versionName,archive.applicationInfo?.minSdkVersion,context.packageName,BuildConfig.VERSION_CODE,Build.VERSION.SDK_INT,old,new)
    }
    fun install() {
        val info=mutable.value.info ?: return;val client=api() ?: return
        if(!mutable.value.ready || client.vault.cookie.isEmpty())return
        val file=File(directory,"${info.artifact}.apk")
        try {
            if(!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                mutable.update {it.copy(message="允许安装未知应用后返回，继续点击安装")};return
            }
            require(file.isFile && file.length()==info.size) {"更新文件已失效，请重新下载"}
            verifyPackage(file,info)
            val uri=FileProvider.getUriForFile(context,context.packageName+".updates",file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch(error: Exception) {mutable.update {it.copy(message=error.message ?: "无法打开系统安装器")}}
    }
}
