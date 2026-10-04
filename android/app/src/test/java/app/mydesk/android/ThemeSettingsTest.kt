package app.mydesk.android

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class)
class ThemeSettingsTest {
    @Test fun savingAppearanceDoesNotShowProgressOrClearAnOngoingOperation() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val preferencesScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val preferencesFile=context.cacheDir.resolve("theme-${UUID.randomUUID()}.preferences_pb")
        val preferences=PreferenceDataStoreFactory.create(scope=preferencesScope) {preferencesFile}
        val graph=AppGraph(context,AppPrefs(context,preferences))
        var observer: Job?=null
        val releaseOperation=CompletableDeferred<Unit>()
        try {
            withTimeout(10_000) {graph.ready.await()}
            val sawProgress=AtomicBoolean(false)
            observer=launch(Dispatchers.Unconfined,start=CoroutineStart.UNDISPATCHED) {
                graph.state.collect {if(it.busy) sawProgress.set(true)}
            }
            graph.setTheme("dark").join()
            withTimeout(10_000) {graph.state.first {it.theme=="dark"}}
            assertEquals("dark",graph.prefs.flow.first().theme)
            assertFalse("Saving a local theme must not show the global progress bar",sawProgress.get())
            observer.cancelAndJoin()

            val entered=CompletableDeferred<Unit>()
            val operation=graph.perform {entered.complete(Unit);releaseOperation.await()}
            withTimeout(10_000) {entered.await()}
            assertTrue(graph.state.value.busy)
            graph.setTheme("light").join()
            withTimeout(10_000) {graph.state.first {it.theme=="light"}}
            assertEquals("light",graph.prefs.flow.first().theme)
            assertTrue("Theme saving must not hide progress belonging to another operation",graph.state.value.busy)
            releaseOperation.complete(Unit)
            operation.join()
            assertFalse(graph.state.value.busy)
        } finally {
            releaseOperation.complete(Unit)
            observer?.cancelAndJoin()
            graph.scope.coroutineContext.job.cancelAndJoin()
            graph.store.close()
            preferencesScope.coroutineContext.job.cancelAndJoin()
            preferencesFile.delete()
        }
    }
}

