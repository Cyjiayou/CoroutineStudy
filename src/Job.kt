import kotlin.coroutines.CoroutineContext

interface Job: CoroutineContext.Element {
    override val key: CoroutineContext.Key<*>
        get() = Job

    companion object: CoroutineContext.Key<Job>

    suspend fun join()
}