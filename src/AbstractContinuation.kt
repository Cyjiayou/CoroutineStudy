import java.awt.Container
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.*

abstract class AbstractContinuation<T>(context: CoroutineContext): Continuation<T>, Job {

    // 为了可以从 context 中获取到 AbstractContinuation，让其实现 Element
    override val context: CoroutineContext = context + this

    override fun resumeWith(result: Result<T>) {
        val newState = state.getAndUpdate { prev ->
            when (prev) {
                is CoroutineState.Incomplete -> {
                    CoroutineState.Completion(result.getOrNull(), result.exceptionOrNull()).from(prev)
                }
                is CoroutineState.Completion<*> -> throw IllegalArgumentException("Already completed")
            }
        }

        newState.notifyCompletion(result)
        newState.clear()
    }

    protected val state: AtomicReference<CoroutineState> = AtomicReference(CoroutineState.Incomplete())

    // 思考，如果执行 join 的时候执行完了，能要 join 吗
    override suspend fun join() {
        when(state.get()) {
            is CoroutineState.Incomplete -> suspendJoin()
            is CoroutineState.Completion<*> -> return
        }
    }

    private suspend fun suspendJoin() {
        suspendCoroutine { continuation ->
            // 注册回调，等状态从 incomplete -> Completion 后执行 resume 恢复挂起
            doCompleted { result -> continuation.resume(Unit) }
        }
    }

    protected fun doCompleted(block: (Result<T>) -> Unit) {
        val disposable = CompletionHandlerDisposable(block)
        state.updateAndGet { prev ->
            when(prev) {
                is CoroutineState.Incomplete -> {
                    CoroutineState.Incomplete().from(prev).with(disposable)
                }
                is CoroutineState.Completion<*> -> {
                    prev
                }
            }
        }
    }
}


class DefaultContinuation<T>(context: CoroutineContext): AbstractContinuation<T>(context) {

}