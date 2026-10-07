package app.mydesk.android

import android.app.Application
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class)
class AppUpdateTest {
    private fun manifest(code: Int=BuildConfig.VERSION_CODE+1,path: String?=null,size: Long=16,sha: String="a".repeat(64))=buildJsonObject {
        put("available",true);put("channel","debug");put("version_code",code);put("version_name","test")
        put("min_sdk",26);put("package_name",BuildConfig.APPLICATION_ID);put("artifact_id",sha);put("sha256",sha)
        put("size_bytes",size);put("apk_path",path ?: "/api/app-update/artifacts/$sha.apk");put("notes","更新说明")
    }
    @Test fun metadataRejectsCrossOriginPathsTypesAndOversizedArtifacts() {
        val valid=UpdateInfo.parse(manifest(),"debug")!!;assertTrue(valid.code>BuildConfig.VERSION_CODE)
        for(value in listOf(manifest(path="https://other.test/test.apk"),manifest(path="/api/app-update/artifacts/../x.apk"),manifest(size=MAX_UPDATE_BYTES+1),manifest(sha="invalid"),buildJsonObject {manifest().forEach {(k,v)->put(k,v)};put("version_code","10")},buildJsonObject {manifest().forEach {(k,v)->put(k,v)};put("package_name","other.package")})) {
            assertTrue(runCatching {UpdateInfo.parse(value,"debug")}.isFailure)
        }
        assertNull(UpdateInfo.parse(buildJsonObject {put("available",false)},"release"))
    }
    @Test fun oldBackendAndSameVersionRemainUsableAndAutomaticChecksAreThrottled()=runBlocking {
        val server=MockWebServer();server.start()
        val context=RuntimeEnvironment.getApplication() as Context
        val vault=object: SessionVault {override var cookie="session=test";override var csrf="csrf"}
        val api=MyDeskApi(server.url("/").toString().trimEnd('/'),vault,OkHttpClient())
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val controller=AppUpdateController(context,scope,{api},{})
        try {
            server.enqueue(MockResponse().setResponseCode(404).setBody("{}"));controller.check(true)
            waitUntil {server.requestCount==1&&!controller.state.value.checking}
            assertEquals("服务器暂未提供应用更新",controller.state.value.message)
            controller.check();delay(100);assertEquals(1,server.requestCount)
            server.enqueue(MockResponse().setBody(manifest(BuildConfig.VERSION_CODE).toString()));controller.check(true)
            waitUntil {server.requestCount==2&&!controller.state.value.checking};assertNull(controller.state.value.info)
            controller.check();delay(100);assertEquals(2,server.requestCount)
        } finally {controller.reset();scope.cancel();server.shutdown()}
    }
    @Test fun invalidDigestDoubleClickAndSessionResetNeverLeaveInstallableFiles()=runBlocking {
        val server=MockWebServer();server.start();val context=RuntimeEnvironment.getApplication() as Context
        val vault=object: SessionVault {override var cookie="session=download-test";override var csrf="csrf"}
        val api=MyDeskApi(server.url("/").toString().trimEnd('/'),vault);val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val controller=AppUpdateController(context,scope,{api},{})
        try {
            server.enqueue(MockResponse().setBody(manifest(size=3).toString()));controller.check(true)
            waitUntil {server.requestCount==1&&!controller.state.value.checking}
            server.enqueue(MockResponse().setHeader("Content-Type","application/vnd.android.package-archive").setBody("bad"))
            controller.download();controller.download();waitUntil {server.requestCount==2&&!controller.state.value.downloading}
            assertFalse(controller.state.value.ready);assertTrue(controller.state.value.message.contains("摘要"));assertEquals(2,server.requestCount)
            controller.reset();assertFalse(controller.state.value.ready)
            assertTrue(File(context.filesDir,"app-updates").listFiles().orEmpty().isEmpty())
        } finally {controller.reset();scope.cancel();server.shutdown()}
    }
    @Test fun wrongPackageVersionSignatureAndSdkCannotInstall() {
        val info=UpdateInfo.parse(manifest(),"debug")!!
        fun validate(packageName: String=BuildConfig.APPLICATION_ID,code: Long=info.code.toLong(),name: String=info.name,sdk: Int=26,device: Int=32,signature: Set<String> = setOf("original")) =
            validateUpdatePackage(info,packageName,code,name,sdk,BuildConfig.APPLICATION_ID,BuildConfig.VERSION_CODE,device,setOf("original"),signature)
        validate()
        assertTrue(runCatching {validate(packageName="other.app")}.isFailure)
        assertTrue(runCatching {validate(code=BuildConfig.VERSION_CODE.toLong())}.isFailure)
        assertTrue(runCatching {validate(name="other.version")}.isFailure)
        assertTrue(runCatching {validate(sdk=27)}.isFailure)
        assertTrue(runCatching {validate(device=25)}.isFailure)
        assertTrue(runCatching {validate(signature=setOf("replacement"))}.isFailure)
        assertTrue(runCatching {validate(signature=emptySet())}.isFailure)
    }
    @Test fun redirectJsonAndOversizedDownloadResponsesFailClosed()=runBlocking {
        val server=MockWebServer();server.start();val context=RuntimeEnvironment.getApplication() as Context
        val vault=object: SessionVault {override var cookie="session=bad-responses";override var csrf="csrf"}
        val api=MyDeskApi(server.url("/").toString().trimEnd('/'),vault)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val controller=AppUpdateController(context,scope,{api},{})
        try {
            server.enqueue(MockResponse().setBody(manifest(size=3).toString()));controller.check(true)
            waitUntil {server.requestCount==1&&!controller.state.value.checking}
            val responses=listOf(MockResponse().setResponseCode(302).setHeader("Location",server.url("/redirect")),MockResponse().setHeader("Content-Type","application/json").setBody("{}"),MockResponse().setHeader("Content-Type","application/vnd.android.package-archive").setBody("oversized"))
            for((index,response) in responses.withIndex()) {
                server.enqueue(response);controller.download();waitUntil {server.requestCount==index+2&&!controller.state.value.downloading}
                assertFalse(controller.state.value.ready)
                assertTrue(File(context.filesDir,"app-updates").listFiles().orEmpty().isEmpty())
            }
            assertEquals(4,server.requestCount)
        } finally {controller.reset();scope.cancel();server.shutdown()}
    }
    private suspend fun waitUntil(check: ()->Boolean) {withTimeout(10000) {while(!check())delay(20)}}
}
