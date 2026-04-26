sealed class CoroutineState {

    class Incomplete: CoroutineState()

    class Completion<T>(var value: T? = null, var exception: Throwable? = null): CoroutineState()

    private var disposableList: DisposableList = DisposableList.Nil

    fun from(state: CoroutineState): CoroutineState {
        this.disposableList = state.disposableList
        return this
    }

    fun with(disposable: Disposable): CoroutineState {
        this.disposableList = DisposableList.Cons(disposable, this.disposableList)
        return this
    }

    fun without(disposable: Disposable): CoroutineState {
        this.disposableList.remove(disposable)
        return this
    }

    fun clear() {
        this.disposableList = DisposableList.Nil
    }

    fun <T> notifyCompletion(result: Result<T>) {
        disposableList.looperOn<CompletionHandlerDisposable<T>> { disposable -> disposable.onCompletion(result) }
    }
}