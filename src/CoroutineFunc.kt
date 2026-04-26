import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.*

val worker = Executors.newScheduledThreadPool(1) { runnable ->
    Thread(runnable, "Schedule").apply { isDaemon = true }
}

suspend fun delay(time: Long, timeUnit: TimeUnit = TimeUnit.MILLISECONDS) {
    if (time < 0) return

    suspendCoroutine<Unit> { continuation ->
        worker.schedule({ continuation.resume(Unit)}, time, timeUnit)
    }
}

fun <T> launch(context: CoroutineContext = EmptyCoroutineContext, block: suspend () -> T): AbstractContinuation<T> {
    val continuation = DefaultContinuation<T>(context)
    block.startCoroutine(continuation)
    return continuation
}

fun<T> async(context: CoroutineContext = EmptyCoroutineContext, block: suspend () -> T): Deferred<T> {
    val continuation = DeferredContinuation<T>(context)
    block.startCoroutine(continuation)
    return continuation
}