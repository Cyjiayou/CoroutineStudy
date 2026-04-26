import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

interface Deferred<T>: Job {

    suspend fun await(): T
}

class DeferredContinuation<T>(context: CoroutineContext): AbstractContinuation<T>(context), Deferred<T> {

    override suspend fun await(): T {
        val currentState = state.get()
        return when (currentState) {
            is CoroutineState.Completion<*> -> {
                currentState.exception?.let { throw it} ?: (currentState.value as T)
            }
            is CoroutineState.Incomplete -> awaitSuspend()
        }
    }


    private suspend fun awaitSuspend(): T {
        return suspendCoroutine { continuation ->
            doCompleted { result ->
                continuation.resumeWith(result)
            }
        }
    }
}