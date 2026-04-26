sealed class DisposableList {

    object Nil: DisposableList()

    class Cons(
        val head: Disposable,
        val tail: DisposableList
    ): DisposableList()
}
fun DisposableList.remove(disposable: Disposable): DisposableList {
    return when (this) {
        is DisposableList.Nil -> this
        is DisposableList.Cons -> {
            if (head == disposable) {
                tail
            } else {
                DisposableList.Cons(head, tail.remove(disposable))
            }
        }
    }
}

fun DisposableList.forEach(action: (Disposable) -> Unit) {
    when(this) {
        is DisposableList.Nil -> {
            return
        }
        is DisposableList.Cons -> {
            action.invoke(this.head)
            this.tail.forEach(action)
        }
    }
}

inline fun <reified T: Disposable> DisposableList.looperOn(crossinline action: (T) -> Unit) {
    forEach {
        when (it) {
            is T ->
                action.invoke(it)
        }
    }
}