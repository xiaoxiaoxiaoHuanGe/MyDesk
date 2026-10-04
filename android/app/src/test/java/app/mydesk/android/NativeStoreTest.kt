package app.mydesk.android

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=android.app.Application::class)
class NativeStoreTest {
    private val snapshot="""{"reminders":[{"id":"r1","title":"备份","remind_at":"2026-10-02T08:30:00Z","status":"pending","revision":"v1"}]}"""

    @Test fun offlineCompletionSurvivesOldSnapshotsAndProcessRestart() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        NativeStore(context).useStore { store ->
            store.clear(); store.accept(snapshot)
            store.enqueue("r1","v1","complete")
            store.accept(snapshot)
            assertTrue(store.visible().isEmpty())
            assertEquals(1,store.pending().size)
        }
        NativeStore(context).useStore { store ->
            assertTrue(store.visible().isEmpty())
            assertEquals(1,store.pending().size)
            store.clear()
            assertTrue(store.pending().isEmpty())
        }
    }

    @Test fun staleNotificationCannotEnqueueAnAction() = runBlocking {
        NativeStore(RuntimeEnvironment.getApplication(), memory=true).useStore { store ->
            store.accept(snapshot)
            assertTrue(runCatching { store.enqueue("r1","old","complete") }.isFailure)
            assertTrue(store.pending().isEmpty())
        }
    }

    @Test fun receiptRebasesQueuedSnoozeChainWithoutRecomputingTime() = runBlocking {
        NativeStore(RuntimeEnvironment.getApplication(), memory=true).useStore { store ->
            store.accept(snapshot)
            val first=store.enqueue("r1","v1","snooze",30)
            val before=store.visible().single()
            val second=store.enqueue("r1",before.revision,"complete")
            store.receipt(first.operationId,before.copy(revision="v2"))
            assertTrue(store.visible().isEmpty())
            assertEquals("v2",store.pending().single().revision)
            assertEquals(second.operationId,store.pending().single().operationId)
        }
    }

    private suspend fun NativeStore.useStore(block: suspend (NativeStore)->Unit) {
        try { block(this) } finally { close() }
    }
}
