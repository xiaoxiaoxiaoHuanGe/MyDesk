package app.mydesk.android

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalIpViewModelTest {
    @Before fun mainDispatcher() {Dispatchers.setMain(UnconfinedTestDispatcher())}
    @After fun resetMain() {Dispatchers.resetMain()}

    @Test fun enteringQueriesOnceAndDoesNotPollUntilAnotherEntryOrManualRefresh()=runTest {
        val calls=mutableListOf<String>()
        val model=LocalIpViewModel(loader={endpoint->calls+=endpoint.id;IpExitResult(endpoint,IpExitStatus.SUCCESS,"203.0.113.42","中国 深圳")})
        assertTrue(calls.isEmpty())
        model.enter();model.enter()
        assertEquals(3,calls.size)
        advanceTimeBy(60_000)
        assertEquals(3,calls.size)
        model.refresh()
        assertEquals(6,calls.size)
        model.leave();model.enter()
        assertEquals(9,calls.size)
        assertFalse(model.state.value.busy)
        assertNotNull(model.state.value.updatedAt)
        model.leave()
    }

    @Test fun fastResultsRemainVisibleWhileOtherExitTimesOutIndependently()=runTest {
        val started=mutableListOf<String>()
        val model=LocalIpViewModel(loader={endpoint->
            started+=endpoint.id
            when(endpoint.id) {
                "foreign"->delay(60_000)
                "google"->throw java.io.IOException("offline")
            }
            IpExitResult(endpoint,IpExitStatus.SUCCESS,"203.0.113.42","中国 深圳")
        },timeoutMs=1_000)
        model.enter()
        assertEquals(3,started.size)
        assertEquals(IpExitStatus.SUCCESS,model.state.value.exits[0].status)
        assertEquals(IpExitStatus.LOADING,model.state.value.exits[1].status)
        assertEquals(IpExitStatus.FAILED,model.state.value.exits[2].status)
        advanceTimeBy(1_001);runCurrent()
        assertEquals(IpExitStatus.TIMEOUT,model.state.value.exits[1].status)
        assertEquals(IpExitStatus.SUCCESS,model.state.value.exits[0].status)
        assertFalse(model.state.value.busy)
        model.leave()
    }

    @Test fun leavingCancelsQueriesAndDiscardsLateResultsFromPreviousEntry()=runTest {
        val previous=CompletableDeferred<Unit>()
        var firstEntry=true
        val model=LocalIpViewModel(loader={endpoint->
            val old=firstEntry
            if(old) withContext(NonCancellable) {previous.await()}
            IpExitResult(endpoint,IpExitStatus.SUCCESS,if(old) "203.0.113.1" else "203.0.113.2","中国")
        })
        model.enter();model.leave();firstEntry=false;model.enter()
        assertTrue(model.state.value.exits.all {it.ip=="203.0.113.2"})
        previous.complete(Unit);runCurrent()
        assertTrue(model.state.value.exits.all {it.ip=="203.0.113.2"})
        model.leave()
    }
}
