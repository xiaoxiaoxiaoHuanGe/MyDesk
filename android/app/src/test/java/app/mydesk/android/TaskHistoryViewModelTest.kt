package app.mydesk.android

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskHistoryViewModelTest {
    @Before fun mainDispatcher() {Dispatchers.setMain(UnconfinedTestDispatcher())}
    @After fun resetMain() {Dispatchers.resetMain()}
    private fun row(id: Int,task: String)=buildJsonObject {put("id",id);put("task_id",task);put("task_name",task);put("message","记录 $id")}
    @Test fun selectedTaskHistoryPaginatesWithoutMixingTasksOrDuplicates()=runTest {
        val calls=mutableListOf<Pair<String?,Long?>>()
        val model=TaskHistoryViewModel({task,before,limit->
            calls+=task to before
            assertEquals(2,limit)
            if(before == null) listOf(row(5,"backup"),row(3,"backup")) else listOf(row(1,"backup"))
        },2)
        model.open("backup")
        assertEquals(listOf("5","3"),model.state.value.rows.map {it.text("id")})
        model.loadMore()
        assertEquals(listOf("5","3","1"),model.state.value.rows.map {it.text("id")})
        assertFalse(model.state.value.hasMore)
        model.loadMore()
        assertEquals(listOf("backup" to null,"backup" to 3L),calls)
        model.clear()
    }
    @Test fun changingTaskOrLoggingOutDiscardsLateResponse()=runTest {
        val delayed=CompletableDeferred<List<JsonObject>>()
        val model=TaskHistoryViewModel({task,_,_->if(task == "backup") withContext(NonCancellable) {delayed.await()} else listOf(row(2,"mail"))})
        model.open("backup")
        model.open("mail")
        assertEquals("mail",model.state.value.taskId)
        assertEquals("mail",model.state.value.rows.single().text("task_id"))
        delayed.complete(listOf(row(1,"backup")))
        assertEquals("mail",model.state.value.rows.single().text("task_id"))
        model.clear()
        assertTrue(model.state.value.rows.isEmpty())
        assertNull(model.state.value.taskId)
    }
    @Test fun failedPageKeepsVisibleRowsAndAllowsRetry()=runTest {
        var fail=true
        val model=TaskHistoryViewModel({_,before,_->if(before == null) listOf(row(5,"backup"),row(3,"backup")) else if(fail) throw java.io.IOException("no network") else listOf(row(1,"backup"))},2)
        model.open("backup");model.loadMore()
        assertEquals(2,model.state.value.rows.size)
        assertTrue(model.state.value.error.isNotEmpty())
        assertFalse(model.state.value.busy)
        fail=false;model.loadMore()
        assertEquals(3,model.state.value.rows.size)
        assertTrue(model.state.value.error.isEmpty())
        model.clear()
    }
}
