import kotlin.concurrent.thread
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

class DispatchInterceptor(private val dispatcher: Dispatcher) : ContinuationInterceptor {

    override val key: CoroutineContext.Key<*> = ContinuationInterceptor

    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
        return DispatchContinuation(continuation, dispatcher)
    }

}

class DispatchContinuation<T>(
    private val delegate: Continuation<T>,
    private val dispatcher: Dispatcher): Continuation<T> by delegate{

    override fun resumeWith(result: Result<T>) {
        dispatcher.dispatch {
            delegate.resumeWith(result)
        }
    }
}