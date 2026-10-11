package app.mydesk.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

data class LocalIpState(val exits: List<IpExitResult> = localIpEndpoints.map {IpExitResult(it)},val busy: Boolean=false,val updatedAt: Instant?=null)

class LocalIpViewModel(
    private val loader: suspend (LocalIpEndpoint)->IpExitResult=LocalIpClient()::fetch,
    private val timeoutMs: Long=10_000
): ViewModel() {
    private val mutable=MutableStateFlow(LocalIpState())
    val state=mutable.asStateFlow()
    private var entered=false
    private var generation=0L
    private var query: Job?=null

    fun enter() {
        if(entered) return
        entered=true
        refresh()
    }

    fun leave() {
        entered=false
        ++generation
        query?.cancel()
        query=null
    }

    fun refresh() {
        if(!entered || state.value.busy && query?.isActive==true) return
        query?.cancel()
        val current=++generation
        mutable.value=LocalIpState(busy=true)
        query=viewModelScope.launch {
            coroutineScope {
                localIpEndpoints.forEach {endpoint->launch {
                    val result=try {withTimeout(timeoutMs) {loader(endpoint)}}
                    catch(_: TimeoutCancellationException) {IpExitResult(endpoint,IpExitStatus.TIMEOUT)}
                    catch(cancel: CancellationException) {throw cancel}
                    catch(_: Exception) {IpExitResult(endpoint,IpExitStatus.FAILED)}
                    ensureActive()
                    if(current==generation) mutable.update {value->value.copy(exits=value.exits.map {if(it.endpoint.id==endpoint.id) result else it})}
                }}
            }
            if(current==generation) mutable.update {it.copy(busy=false,updatedAt=Instant.now())}
        }
    }

    override fun onCleared() {leave();super.onCleared()}
}
