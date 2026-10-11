package app.mydesk.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class QrCaptureActivity: CaptureActivity()

@Composable internal fun rememberQrLoginScanner(server: String,preview: suspend (String)->QrLoginPreview,
    confirm: suspend (QrLoginCode,Boolean)->Unit): ()->Unit {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val previewAction by rememberUpdatedState(preview)
    val confirmAction by rememberUpdatedState(confirm)
    var opened by remember(server) {mutableStateOf(false)}
    var candidate by remember(server) {mutableStateOf<QrLoginPreview?>(null)}
    var busy by remember(server) {mutableStateOf(false)}
    var error by remember(server) {mutableStateOf("")}
    var deadline by remember(server) {mutableLongStateOf(0L)}
    var remaining by remember(server) {mutableIntStateOf(0)}
    var loadingJob by remember {mutableStateOf<Job?>(null)}
    LaunchedEffect(candidate) {
        while(candidate!=null) {
            remaining=((deadline-SystemClock.elapsedRealtime())/1000).toInt().coerceAtLeast(0)
            if(remaining==0)break
            delay(1000)
        }
    }
    DisposableEffect(server) {onDispose {loadingJob?.cancel()}}
    val scanner=rememberLauncherForActivityResult(ScanContract()) {result->
        result.contents?.let {raw->
            loadingJob?.cancel();opened=true;busy=true;candidate=null;error=""
            loadingJob=scope.launch {
                try {
                    val value=previewAction(raw)
                    deadline=SystemClock.elapsedRealtime()+value.expiresIn*1000L
                    remaining=value.expiresIn;candidate=value
                } catch(cancel: CancellationException) {throw cancel}
                catch(failure: Exception) {error=if(failure is IllegalArgumentException || failure is ApiError) failure.message ?: "二维码无效" else "连接失败，请检查网络后重新扫码"}
                finally {busy=false}
            }
        }
    }
    val options=remember {ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setPrompt("扫描 MyDesk 电脑登录二维码").setBeepEnabled(false).setOrientationLocked(false)
        .setCaptureActivity(QrCaptureActivity::class.java)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {allowed->
        if(allowed)scanner.launch(options)
        else {candidate=null;error="需要相机权限才能扫一扫，请在系统设置中允许 MyDesk 使用相机。";opened=true}
    }
    fun dismiss() {loadingJob?.cancel();opened=false;candidate=null;error="";busy=false}
    if(opened) AlertDialog(onDismissRequest={if(!busy)dismiss()},
        title={Text(if(candidate==null) "扫一扫" else "确认电脑登录")},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(if(candidate==null) "正在核对登录请求…" else "正在确认…")}
            candidate?.let {
                Text("允许这台电脑登录你的 MyDesk？")
                Text(it.browser,style=MaterialTheme.typography.titleMedium)
                Text(it.code.server,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if(remaining>0) "请在 ${remaining} 秒内确认" else "二维码已过期，请在电脑上刷新后重新扫码",style=MaterialTheme.typography.bodySmall)
            }
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        }},
        confirmButton={
            if(candidate==null) TextButton({dismiss()},enabled=!busy) {Text("关闭")}
            else TextButton({val code=candidate!!.code;busy=true;error="";loadingJob=scope.launch {
                try {confirmAction(code,true);dismiss()}
                catch(cancel: CancellationException) {throw cancel}
                catch(failure: Exception) {error=if(failure is ApiError || failure is IllegalArgumentException) failure.message ?: "确认失败" else "连接失败，请重试"}
                finally {busy=false}
            }},enabled=!busy && remaining>0) {Text("确认登录")}
        },dismissButton={if(candidate!=null) TextButton({if(remaining==0)dismiss() else {val code=candidate!!.code;busy=true;loadingJob=scope.launch {
            try {confirmAction(code,false);dismiss()}
            catch(cancel: CancellationException) {throw cancel}
            catch(failure: Exception) {error=failure.message ?: "取消失败，请重试"}
            finally {busy=false}
        }}},enabled=!busy) {Text(if(remaining==0) "关闭" else "取消登录")}})
    return {
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)scanner.launch(options)
        else permission.launch(Manifest.permission.CAMERA)
    }
}
