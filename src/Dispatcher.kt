import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler

interface Dispatcher {

    fun dispatch(block: () -> Unit)
}


object DefaultDispatcher: Dispatcher {

    private val threadGroup = ThreadGroup("DefaultDispatcher")
    private val threadIndex = AtomicInteger(0)

    private val executor = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors() + 1
    ) { runnable ->
        Thread(threadGroup, runnable, "${threadGroup.name}-worker-${threadIndex.getAndIncrement()}").apply { isDaemon = true }
    }

    override fun dispatch(block: () -> Unit) {
        executor.submit(block)
    }

}

//object AndroidDispatcher: Dispatcher {
//    private val handler = Handler(Looper.getMainLooper())
//
//    override fun dispatch(block: () -> Unit) {
//        handler.post(block)
//    }
//
//}

object Dispatchers {
    val Default by lazy {
        DispatchInterceptor(DefaultDispatcher)
    }

//    val Main by lazy {
//        DispatchInterceptor(AndroidDispatcher)
//    }
}