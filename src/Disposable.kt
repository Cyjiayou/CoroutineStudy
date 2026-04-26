interface Disposable {
}

class CompletionHandlerDisposable<T>(
    val onCompletion: (Result<T>) -> Unit
): Disposable {

}