import kotlin.concurrent.thread
import kotlin.coroutines.*

suspend fun main() {

    val continuation = launch {
        println("in launch, prepare to delay 3s")
        delay(3000)
        println("after delay")
    }

    println("prepare to join")
    continuation.join()
    println("after join")

    val deferred = async {
        delay(1000)
        "hello world"
    }

    val result = deferred.await()
    println(result)

    val threadContinuation = launch(Dispatchers.Default) {
        println(Thread.currentThread().name)
        delay(3000)
        println(Thread.currentThread().name)
    }

    threadContinuation.join()
}
