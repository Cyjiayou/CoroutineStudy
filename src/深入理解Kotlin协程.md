# 协程源码解读

```kotlin
fun main() {

    suspend {
        println("in coroutine")
    }.startCoroutine(object : Continuation<Unit> {
        override val context: CoroutineContext
            get() = EmptyCoroutineContext

        override fun resumeWith(result: Result<Unit>) {
            println("after coroutine")
        }

    })

    println("hello world")
}
```

一说到协程，大家都会觉得就是一个线程。那么你能知道上面代码的运行结果是什么吗？

答案是：

```text
in coroutine
after coroutine
hello world
```

你会不会很吃惊呢？为什么 hello world 的执行会在 after coroutine 后面呢？一切从源码开始

```kotlin
public inline fun <R> suspend(noinline block: suspend () -> R): suspend () -> R = block
```

suspend { } 实际上就是一个方法，{ } 中时机上还是一个 suspend lambda 表达式。可以理解为 suspend 就是将普通的方法转化为 suspend 方法。

```kotlin
public fun <T> (suspend () -> T).startCoroutine(
    completion: Continuation<T>
) {
    createCoroutineUnintercepted(completion).intercepted().resume(Unit)
}
```

startCoroutine() 是 suspend lambda 表达式的扩展函数，实际上是调用 `creatCoroutineUnintercepted` 函数，从名字上可以看出，就是创建一个没有拦截的 Coroutine。

```kotlin
public actual fun <T> (suspend () -> T).createCoroutineUnintercepted(
    completion: Continuation<T>
): Continuation<Unit> {
    val probeCompletion = probeCoroutineCreated(completion)
    return if (this is BaseContinuationImpl)
        create(probeCompletion)
    else
        createCoroutineFromSuspendFunction(probeCompletion) {
            (this as Function1<Continuation<T>, Any?>).invoke(it)
        }
}
```

可以发现这里有个类型判断，而且用的是 this，指的是 suspend lambda 表达式，也就是说编译器会将 suspend lambda 表示式生成一个类（原本lambda表达式就应该是一个匿名类）。

```kotlin
final class CoroutineTestKt$main$1 extends kotlin/coroutines/jvm/internal/SuspendLambda implements kotlin/jvm/functions/Function1 { }
```

从字节码中可以看到生成的类继承了 SuspendLambda 并且实现了 Function1。Function1是因为原本是一个lambda表达式。仔细看一下 SuspendLambda 类

```kotlin
internal abstract class SuspendLambda(
    public override val arity: Int,
    completion: Continuation<Any?>?
) : ContinuationImpl(completion), FunctionBase<Any?>, SuspendFunction {
    constructor(arity: Int) : this(arity, null)

    public override fun toString(): String =
        if (completion == null)
            Reflection.renderLambdaToString(this) // this is lambda
        else
            super.toString() // this is continuation
}

internal abstract class ContinuationImpl(
    completion: Continuation<Any?>?,
    private val _context: CoroutineContext?
) : BaseContinuationImpl(completion) { 
	...
}
```

可以看到 SuspendLambda 实际上就是 BaseContinuationImpl 的子类。回到 `createCoroutineUnintercepted` 中我们就可以知道 this 实际上就是 `BaseContinuationImpl`。那我们就要看一下 create 方法了

```kotlin
public final create(Lkotlin/coroutines/Continuation;)Lkotlin/coroutines/Continuation;
   L0
    NEW CoroutineTestKt$main$1
    DUP
    ALOAD 1
    INVOKESPECIAL CoroutineTestKt$main$1.<init> (Lkotlin/coroutines/Continuation;)V
    CHECKCAST kotlin/coroutines/Continuation
    ARETURN
   L1
    LOCALVARIABLE this LCoroutineTestKt$main$1; L0 L1 0
    LOCALVARIABLE $completion Lkotlin/coroutines/Continuation; L0 L1 1
    MAXSTACK = 3
    MAXLOCALS = 2

// 从字节码推测就是调用了构建函数，将 completion 传递进去
public final Continuation create(@NotNull Continuation completion) {
    return new CoroutineTestKt$main$1(completion);
}
```

**记住，在调用 startCoroutine(completion) 的时候会先创建 BaseContinuationImpl，然后将 completion 传递进去**

接下来看 intercepted 的方法实现

```kotlin
public actual fun <T> Continuation<T>.intercepted(): Continuation<T> =
    (this as? ContinuationImpl)?.intercepted() ?: this
internal abstract class ContinuaionImpl {
  
  private var intercepted: Continuation<Any?> = null
  
  public fun intercepted(): Continuation<Any?> =
        intercepted
            ?: (context[ContinuationInterceptor]?.interceptContinuation(this) ?: this)
                .also { intercepted = it }
}
```

==由于这里没用到 intercept，所以不做过多解释。等后面说到拦截器的时候再补充==。

接下来看 resume() 方法，也是协程中很重要的方法之一。

```kotlin
public inline fun <T> Continuation<T>.resume(value: T): Unit =
    resumeWith(Result.success(value))
```

**实际上就是调用 Continuation 的 resumeWith 方法。**

```kotlin
internal abstract class BaseContinuationImpl {
  public final override fun resumeWith(result: Result<Any?>) {

      var current = this
      var param = result
      while (true) {

          with(current) {
              val completion = completion!! 
              val outcome: Result<Any?> =
                  try {
                      val outcome = invokeSuspend(param)
                      if (outcome === COROUTINE_SUSPENDED) return
                      Result.success(outcome)
                  } catch (exception: Throwable) {
                      Result.failure(exception)
                  }
              releaseIntercepted() // this state machine instance is terminating
              if (completion is BaseContinuationImpl) {
                  // unrolling recursion via loop
                  current = completion
                  param = outcome
              } else {
                  // top-level completion reached -- invoke and return
                  completion.resumeWith(outcome)
                  return
              }
          }
      }
  }
}
```

>   1.   调用 invokeSuspend() 方法。编译器会为每个 suspend function 创建一个 invokeSuspend() 函数；
>   2.   如果 invokeSuspend() 返回 COROUTINE_SUSPEND，说明当前方法被挂起了。此时立即返回；
>   3.   如果返回的不是 COROUTINE_SUSPEND 说明拿到了返回值，此时就会调用构建函数传递进来的 continuation 的 resumeWith() 方法。

**问题1: invokeSuspend() 是什么？**

**问题2：挂起完成后怎么通知呢？**

```kotlin
suspend {
    println("in coroutine")
}
```

先看一下这个简单代码的 `invokeSuspend()`

```java
int label;

public final Object invokeSuspend(Object $result) {
    Object var3 = IntrinsicsKt.getCOROUTINE_SUSPENDED();
    switch (this.label) {
       case 0:
          ResultKt.throwOnFailure($result);
          String var2 = "in coroutine";
          System.out.println(var2);
          return Unit.INSTANCE;
       default:
          throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
    }
 }
```

>   [!note]
>
>   其中 `IntrinsicsKt.getCOROUTINE_SUSPENDED()` 是 Kotlin 协程框架中的一个**特殊哨兵对象**。
>
>   ```kotlin
>   // 位于 kotlin.coroutines.intrinsics 包下
>   public actual fun getCOROUTINE_SUSPENDED(): Any = CoroutineSingletons.COROUTINE_SUSPENDED
>   
>   // 位于 kotlin.coroutines.intrinsics 包下
>   public actual fun getCOROUTINE_SUSPENDED(): Any = CoroutineSingletons.COROUTINE_SUSPENDED
>   ```

可以发现这段代码很简单，就是执行了 `println("in coroutine")`。因为，我们提供的代码太简单了。

```kotlin
suspend {
    println("in coroutine")

    delay(5000)
}.startCoroutine(object : Continuation<Unit> {
    override val context: CoroutineContext
        get() = EmptyCoroutineContext

    override fun resumeWith(result: Result<Unit>) {
        println("after coroutine")
    }
})
```

加了一个挂起函数，看一下这段代码对应的 `invokeSuspend()`

```java
int lable;

public final Object invokeSuspend(Object $result) {
  Object var3 = IntrinsicsKt.getCOROUTINE_SUSPENDED();
  switch (this.label) {
     case 0:
        ResultKt.throwOnFailure($result);
        String var2 = "in coroutine";
        System.out.println(var2);
        Continuation var10002 = (Continuation)this;
        this.label = 1;
        if (CoroutineTestKt.delay$default(5000L, (TimeUnit)null, var10002, 2, (Object)null) == var3) {
           return var3;
        }
        break;
     case 1:
        ResultKt.throwOnFailure($result);
        break;
     default:
        throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
  }

  String var4 = "after delay";
  System.out.println(var4);
  return Unit.INSTANCE;
}
```

可以看到，以前 lable 只会判断是不是 0，现在多了一个是不是 1。

>   1.   先调用 IntrinsicsKt.getCOROUTINE_SUSPENDED() 获取哨兵标记；
>   2.   执行 suspend 函数体，先执行 println("in coroutine")，将 lable 置为 1，执行 delay() 方法，如果 delay() 返回 COROUTINE_SUSPENDED，则返回 COROUTINE_SUSPENDED；反之则返回 Unit.INSTANCE;

>   [!note]
>
>   这里可以发现在调用 delay 方法的时候，会将当前 suspend 里面的 contination 传递给 delay 方法。

看一下 delay 方法。

```kotlin
public static Object delay$default(long var0, TimeUnit var2, Continuation var3, int var4, Object var5) {
  if ((var4 & 2) != 0) {
     var2 = TimeUnit.MILLISECONDS;
  }

  return delay(var0, var2, var3);
}

public static final Object delay(long time, @NotNull TimeUnit unit, @NotNull Continuation $completion) {
  if (time <= 0L) {
     return Unit.INSTANCE;
  } else {
     SafeContinuation var5 = new SafeContinuation(IntrinsicsKt.intercepted($completion));
     final Continuation continuation = (Continuation)var5;
     int var7 = 0;
     executor.schedule(new Runnable() {
        public final void run() {
           Result.Companion var10001 = Result.Companion;
           continuation.resumeWith(Result.constructor-impl(Unit.INSTANCE));
        }
     }, time, unit);
     Object var10000 = var5.getOrThrow();
     if (var10000 == IntrinsicsKt.getCOROUTINE_SUSPENDED()) {
        DebugProbesKt.probeCoroutineSuspended($completion);
     }

     return var10000 == IntrinsicsKt.getCOROUTINE_SUSPENDED() ? var10000 : Unit.INSTANCE;
  }
}
```

**SafeContinuation：异步转同步的桥梁**：确保`Continuation` 只会被恢复一次，并且能正确处理“立即返回”和“挂起返回”两种情况。它的内部维护一个状态机，用来协调 `resumeWith`(恢复) 和 `getOrThrow`(挂起)。

```kotlin
internal actual class SafeContinuation<in T>
internal actual constructor(
    private val delegate: Continuation<T>,
    initialResult: Any?
) : Continuation<T>, CoroutineStackFrame {
    @PublishedApi
    internal actual constructor(delegate: Continuation<T>) : this(delegate, UNDECIDED)
  
  public actual override context: CoroutineContext = delegate.context
  
  public actual override fun resumeWith(result: Result<T>) {
        while (true) { // 自旋锁，处理并发竞争
            val cur = this.result // 原子读取当前状态
            when {
              	// 场景 A：结果先到了，协程还没决定是否要挂起
                cur === UNDECIDED -> if (RESULT.compareAndSet(this, UNDECIDED, result.value)) return
                // 场景 B：协程已经确认挂起了
              	cur === COROUTINE_SUSPENDED -> if (RESULT.compareAndSet(this, COROUTINE_SUSPENDED, RESUMED)) {
                    // 只有进入这个分支，才会真正触发状态机的下一跳
                  	delegate.resumeWith(result)
                    return
                }
                else -> throw IllegalStateException("Already resumed")
            }
        }
    }

    internal actual fun getOrThrow(): Any? {
        var result = this.result 
        if (result === UNDECIDED) {
          	// 如果当前状态是不确定，则尝试将状态转为挂起，成功则返回
            if (RESULT.compareAndSet(this, UNDECIDED, COROUTINE_SUSPENDED)) return COROUTINE_SUSPENDED
            // CAS 失败，则说明 resume 已经执行了。
          	result = this.result 
        }
        return when {
            result === RESUMED -> COROUTINE_SUSPENDED // 表示 resumeWith 已经先跑过了COROUTINE_SUSPENDED upstream
            result is Result.Failure -> throw result.exception // 如果是失败的结果，直接抛出异常
            else -> result // 可能是 COROUTINE_SUSPENDED 也可能是结果
        }
    }
}
```

>   SafeContinuation 有三个状态：
>
>   -   UNDECIDED：初始状态，不知道会立即完成还是挂起；
>   -   COROUTINE_SUSPEND：函数已经确认要挂起了；
>   -   RESUME：数据已经准备好了；
>
>   getOrThrow() 用来决定当前函数应该返回一个真实的结果，还是返回一个挂起信号（Couroutine_suspend）；
>
>   resumeWith() 确保结果被安全地送达，并根据协程当前的状态决定是“立即填坑”还是“远程唤醒”

再回过头去看 delay 方法，一开始会创建一个定时线程，5s后执行 resumeWith() 方法; 然后执行 getOrThrow() 返回 COROUTINESUSPEND。

等 5 s后会执行 resumeWith() 方法，将状态置为 RESUMED，并执行 delegate 的 resumeWith() 方法。这里的 delegate 是 SuspendLambda 对象，所以会执行 ContinuationImpl 的 resumeWith() 方法。再次执行 invokeSuspend() 方法，但这次 lable 变成了 1，执行剩余逻辑并返回 Unit.INSTANCE。随后执行我们创建的 continuation 的 resumeWith() 方法。

>   [!NOTE]
>
>   为什么说 suspend 方法只能在 suspend 方法中执行？这优点像 C++ 中的 this 指针，在执行每个类方法的时候都隐式传递了 this 指针。suspend 方法实际上也有个 continuation 隐式变量，调用时会将当前方法中的 continuation 传给下个 suspend function
>
>   ```kotlin
>   suspendCoroutine { continuation ->
>       executor.schedule({continuation.resume(Unit) }, time, unit)
>   }
>   
>   public suspend inline fun <T> suspendCoroutine(crossinline block: (Continuation<T>) -> Unit): T {
>       contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
>       return suspendCoroutineUninterceptedOrReturn { c: Continuation<T> ->
>           val safe = SafeContinuation(c.intercepted())
>           block(safe)
>           safe.getOrThrow()
>       }
>   }
>   ```
>
>   suspendCoroutine 的作用可以理解为，用 SafeContinuation 包装一个 Continuation（ContinuationImpl），执行 suspendCorotine 的函数体，然后调用 getOrThrow() 方法将函数挂起。



## resume 和 Result

```kotlin
public inline fun <T> Continuation<T>.resume(value: T): Unit =
    resumeWith(Result.success(value))
```

`resume()` 的参数就是普通变化，会将参数封装成 `Result`，并调用 `resumeWith()` 方法。所以 `Resume` 的参数是 T，`ResumeWith` 的参数是 `Result<T>`。

```kotlin
class Result<out T>(val value: Any) {
  
  companion object {
    public inline fun <T> success(value: T): Result<T> = Result(value)
    
    public inline fun <T> failure(exception: Throwable): Result<T> = Result(createFailure(exception))
  }
  
  public val isSuccess: Boolean get() = value !is Failure
  public val isFailure: Boolean get() = value is Failure
  
  public inline fun getOrNull(): T? = 
    when {
      isFailure -> null
      else -> value as T
    }
  }
  
  public fun exceptionOrNull(): Throwable? = 
		when (value) {
      is Failure -> valure.exception
      else -> null
    }
}
```





## suspendCoroutine 的返回值

```kotlin
internal actual class SafeContinuation<in T>
internal actual constructor(
    private val delegate: Continuation<T>,
    initialResult: Any?
) : Continuation<T>, CoroutineStackFrame {
    @PublishedApi
    internal actual constructor(delegate: Continuation<T>) : this(delegate, UNDECIDED)
  
  public actual override context: CoroutineContext = delegate.context
  
  public actual override fun resumeWith(result: Result<T>) {
        while (true) { // 自旋锁，处理并发竞争
            val cur = this.result // 原子读取当前状态
            when {
              	// 场景 A：结果先到了，协程还没决定是否要挂起
                cur === UNDECIDED -> if (RESULT.compareAndSet(this, UNDECIDED, result.value)) return
                // 场景 B：协程已经确认挂起了
              	cur === COROUTINE_SUSPENDED -> if (RESULT.compareAndSet(this, COROUTINE_SUSPENDED, RESUMED)) {
                    // 只有进入这个分支，才会真正触发状态机的下一跳
                  	delegate.resumeWith(result)
                    return
                }
                else -> throw IllegalStateException("Already resumed")
            }
        }
    }

    internal actual fun getOrThrow(): Any? {
        var result = this.result 
        if (result === UNDECIDED) {
          	// 如果当前状态是不确定，则尝试将状态转为挂起，成功则返回
            if (RESULT.compareAndSet(this, UNDECIDED, COROUTINE_SUSPENDED)) return COROUTINE_SUSPENDED
            // CAS 失败，则说明 resume 已经执行了。
          	result = this.result 
        }
        return when {
            result === RESUMED -> COROUTINE_SUSPENDED // 表示 resumeWith 已经先跑过了COROUTINE_SUSPENDED upstream
            result is Result.Failure -> throw result.exception // 如果是失败的结果，直接抛出异常
            else -> result // 可能是 COROUTINE_SUSPENDED 也可能是结果
        }
    }
}
```

如果当前协程还在挂起，则返回 `COROUTINE_SUSPENDED`；如果已经调用过 `resumeWith(value)`，则将对应的 value 返回。



## 拦截器（源码分析）

官方提供了拦截器，允许拦截协程异步回调时的恢复调用。定义拦截器，只需要实现拦截的接口，并添加到对应的协程上下文中即可。

```kotlin
class LogInterceptor() : ContinuationInterceptor {
    override val key: CoroutineContext.Key<*> = ContinuationInterceptor

    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
        return LogContinuation(continuation)
    }
}

class LogContinuation<T>(private val delegate: Continuation<T>): Continuation<T> by delegate {

    override fun resumeWith(result: Result<T>) {
        println("before resumeWith: $result")
        delegate.resumeWith(result)
        println("after resumeWith: $result")
    }
    
}
```

如果想使用 `LogInterceptor` 只需要将其添加到 context 中即可

```kotlin
suspend {
  ...
}.startContinuation(object: Continuation {
  override val context = LogInterceptor()
  ...
})
```



### 源码分析

```kotlin
suspend {
    println("in coroutine")
}.startCoroutine(object : Continuation<Unit> {
    override val context: CoroutineContext
        get() = LogInterceptor()

    override fun resumeWith(result: Result<Unit>) {
        println("after coroutine")
    }

})

println("hello world")
```

还是以之前的例子来分析源码，这里仅仅是将 context 设置为 LogInterceptor()。

```kotlin
public fun <T> (suspend () -> T).startCoroutine(
    completion: Continuation<T>
) {
    createCoroutineUnintercepted(completion).intercepted().resume(Unit)
}
```

`createCoroutineUnintercepted` 是用于创建一个 `continuation`，这里重点看一下 `intercepted` 方法。

```kotlin 
public actual fun <T> Continuation<T>.intercepted(): Continuation<T> =
    (this as? ContinuationImpl)?.intercepted() ?: this
```

由之前的分支可以知道，创建的 continuation 是 ContinuationImpl 的子类

```kotlin
internal abstract class ContinuationImpl(
    completion: Continuation<Any?>?,
    private val _context: CoroutineContext?
) : BaseContinuationImpl(completion) {
  
	constructor(completion: Continuation<Any?>?) : this(completion, completion?.context)  

	public override val context: CoroutineContext
        get() = _context!!
  
  private var intercepted: Continuation<Any?>? = null
  
  public fun intercepted(): Continuation<Any?> =
        intercepted
            ?: (context[ContinuationInterceptor]?.interceptContinuation(this) ?: this)
                .also { intercepted = it }
}
```

-   构建函数会将 continuation 传递进去，并将 Context 绑定为其的 context。由于我们创建 Continuation 的时候设置 Context 为 LogInterceptor。
-   intercepted() 方法会从 context 中提取 ContinuationInterceptor（我们传递的 LogInterceptor），并调用其 interceptContinuation() 方法，将 continuation 进行封装，这里即是返回 LogContinuation。
-   最后就是调用 LogContinuation 的 resume() 和 resumeWith() 方法。

>   [!Note]
>
>   ContinuationInterceptor 实际上就是将 Continuation 重新封装了一层。

```kotlin
before resumeWith: Success(kotlin.Unit)
in coroutine
after coroutine
after resumeWith: Success(kotlin.Unit)

```

再试试另一个案例

```kotlin
suspend fun function01() = suspendCoroutine<Int> { continuation ->
    thread {
        println("in function01")
        continuation.resume(100)
    }
}
suspend {
    println("in coroutine")
    println("before suspendCoroutine1")
    function01()
    println("after suspendCoroutine1")
    println("before suspendCoroutine2")
    function01()
    println("after suspendCoroutine1")
}.startCoroutine(object : Continuation<Unit> {
    override val context: CoroutineContext
        get() = LogInterceptor()

    override fun resumeWith(result: Result<Unit>) {
        println("after coroutine")
    }

})

println("hello world")

before resumeWith: Success(kotlin.Unit)
in coroutine
before suspendCoroutine1
after resumeWith: Success(kotlin.Unit)
hello world
in function01
before resumeWith: Success(100)
after suspendCoroutine1
before suspendCoroutine2
after resumeWith: Success(100)
in function01
before resumeWith: Success(100)
after suspendCoroutine1
after coroutine
after resumeWith: Success(100)
```

**为什么会输出这么多拦截器的信息呢，如果改成同步还会不会输出呢**

**SuspendCoroutine 会调用 interceptor() 将 Continuation 封装。如果是异步调用，则会在 resumeWith 的时候调用拦截器的 resumeWith 方法。但如果是同步，则不会发生挂起，不会调用拦截器的 resumeWith 方法**

>   [!Note]
>
>   ==对于 suspendCoroutine 来看是 getOrThrow 先执行，还是 resumeWith 先执行==

```kotlin
public static final Object function01(@NotNull Continuation $completion) {
  SafeContinuation var2 = new SafeContinuation(IntrinsicsKt.intercepted($completion));
  final Continuation continuation = (Continuation)var2;
  int var4 = 0;
  ThreadsKt.thread$default(false, false, (ClassLoader)null, (String)null, 0, new Function0() {
     public final void invoke() {
        String var1 = "in function01";
        System.out.println(var1);
        Result.Companion var10001 = Result.Companion;
        continuation.resumeWith(Result.constructor-impl(100));
     }

     // $FF: synthetic method
     // $FF: bridge method
     public Object invoke() {
        this.invoke();
        return Unit.INSTANCE;
     }
  }, 31, (Object)null);
  Object var10000 = var2.getOrThrow();
  if (var10000 == IntrinsicsKt.getCOROUTINE_SUSPENDED()) {
     DebugProbesKt.probeCoroutineSuspended($completion);
  }

  return var10000;
}
```

==梳理一下流程（难）：==

1.   将 ContinuationImpl 封装成 LogContinuation，执行 LogContinuation 的 resumeWith 方法，输出 `before resumeWith`；
2.   其次执行 delegate.resumeWith() 方法，即执行 ContinuationImpl 的 resumeWith 方法。这一步会先执行 invokeSuspend() 方法，输出 `In Coroutine` 和 `"before suspendCoroutine1"`；
3.   其次执行 function01。delegate 会被传递给 function01，并封装为 LogContinuation，再封装为 SafeContinuation，随后执行函数体。但是由于这里发生了异步挂起，直接返回 SuspendCoroutine。所以退出 invokeSuspend() 方法，继续执行 LogContinuation，输出 `after resumeWith`，并继续执行住线程的流程，执行 `hello world`。
4.   随后执行函数体的内容，输出 `in funtion01`，并调用 LogContinuation 的 resumeWith 方法，输出 `before resumeWith` ，再执行 delegate 的 resumeWith 方法，继续执行 invokeSuspend 方法，输出 `after suspendCoroutine1` 和 `before suspendCoroutine2`；
5.   后续同理遇到异步挂起，直接执行 `after resumeWith`。随后执行函数体的内容，输出 `in funtion01`，并调用 LogContinuation 的 resumeWith 方法，输出 `before resumeWith` ，再执行 delegate 的 resumeWith 方法，继续执行 invokeSuspend 方法，输出 `after suspendCoroutine1` 和 `before suspendCoroutine2`；invokeSuspend 执行完后调用真正的 continuation 的 resumeWith 方法，输出 `after coroutine`，最后再输出 `after resumeWith`





# 手写封装协程

## delay/launch

开胃菜 delay，挂起一段时间后恢复协程代码。实际上就是调用 `suspendCoroutine` 方法，然后异步等待，到达时间后恢复调用

```kotlin
val worker = Executors.newScheduledThreadPool(1) { runnable ->
    Thread(runnable, "Schedule").apply { isDaemon = true }
}

suspend fun delay(time: Long, timeUnit: TimeUnit = TimeUnit.MILLISECONDS) {
    if (time < 0) return

    suspendCoroutine<Unit> { continuation ->
        worker.schedule({ continuation.resume(Unit)}, time, timeUnit)
    }
}
```

Launch 主要用于启动协程，我们先看一下一般的启动流程是什么样子的

```kotlin
suspend {
	...
}.startCoroutine(object : Continuation<Unit> {
    override val context: CoroutineContext
        get() = EmptyCoroutineContext

    override fun resumeWith(result: Result<Unit>) {
        ...
    }
})
```

可以发现，每次启动一个协程都要写 `Continuation`，还需要调用 `startCoroutine`，非常的麻烦，所以希望可以将这段代码封装起来。

```kotlin
suspend fun<T> launch(context: CoroutineContext = EmptyCoroutineContext, block: suspend () -> T): Continuation<T> {
  val continuation = DefaultContinuation()
  block.startCoroutine(continuation)
  return Continuation
}
```

通过将协程封装，可以简化启动流程。

```kotlin
launch {
  ....
}
```

目前缺少的就是 `DefaultContinuation` 的封装。



## Continuation 封装

首先为了扩展，定义了一个 Abstract class。

```kotlin
abstract class AbstractContinuation<T>(context: CoroutineContext): Continuation<T> {
  override val context: CoroutineContext = context
  
  override fun resumeWith(result: Result<T>) {

  }
}
```

这么写存在的问题是，我们无法从 context 中获取到 AbstractContinuation，为此我们按官方协程框架将 `Continuation` 封装为 Job

```kotlin
interface Job: CoroutineContext.Element {
    override val key: CoroutineContext.Key<*>
        get() = Job

    companion object: CoroutineContext.Key<Job>

}

abstract class AbstractContinuation<T>(context: CoroutineContext): Continuation<T>, Job {
	override val context: CoroutineContext = context + this

  override fun resumeWith(result: Result<T>) {

  }  
}
```

后续就可以从context 获取到 AbstractContinuation。

其实，协程类似于线程，除了启动以外，还需要添加 join 方法，用于等待协程执行结束。思考，调用 join 方法后的动作有哪些呢？首先会判断当前协程是不是已经执行结束了，如果执行完了，就不需要执行其他逻辑了。如果没有执行完，就需要将调用协程的地方挂起等待。

```kotlin
interface Job: CoroutineContext.Element {
    override val key: CoroutineContext.Key<*>
        get() = Job

    companion object: CoroutineContext.Key<Job>

  	suspend fun join()
}
```

所以，我们需要为 Continuation 添加状态标记 `CoroutineState`。根据描述，我们可以知道，一共有两种状态 `Incomplete` 和 `Completion`

```kotlin
sealed class CoroutineState {

    class Incomplete: CoroutineState()

    class Completion: CoroutineState()
}
```

顾，我们完善一下 `AbstractCoroutine` 的 `join` 方法

```kotlin
abstract class AbstractContinuation<T>(context: CoroutineContext): Continuation<T>, Job {
	
  protected val state: AtomicReference<CoroutineState> = AtomicReference(CoroutineState.Incomplete())
  
  override suspend fun join() {
      when(state.get()) {
          is CoroutineState.Incomplete -> suspendJoin()
          is CoroutineState.Completion -> return
      }
  }
  
  private suspend fun suspendJoin() {
  	suspendCoroutine { continuation ->
      ...
   	}  
  }
}
```

首先为了避免多线程调用导致 `state` 被竞争，我们将状态设置为原子变量。其次在写 `suspendJoin` 方法的时候我们是希望在 Continuation 的状态转化为 `Completion` 的时候再调用 `resume(Unit)`，所以我们这里应该是将恢复事件添加到列表 **DisposableList** 中，当状态变化为 `Completion` 时执行事件。

```kotlin
interface Disposable {
  
}

sealed class DisposableList {

    object Nil: DisposableList()

    class Cons(
        val head: Disposable,
        val tail: DisposableList
    ): DisposableList()
}

```

说是列表，但更像是链表，方便动态添加和删除。并且为了线程安全，我们每次都应该是重新创建列表，并将之前的元素添加到列表中

```kotlin
sealed class CoroutineState {

    class Incomplete: CoroutineState()

    class Completion: CoroutineState()
  
  	private val disposableList: DispoableList = DisposableList.Nil
  
  	fun form(state: CoroutineState): CoroutineState {
      this.disposableList = state.disposableList
      return this
    }
  
  	// 动态添加
    fun with(disposable: Disposable): CoroutineState {
        this.disposableList = DisposableList.Cons(disposable, this.disposableList)
        return this
    }

  	// 动态删除
    fun without(disposable: Disposable): CoroutineState {
        this.disposableList.remove(disposable)
        return this
    }
  
  	fun clear() {
        this.disposableList = DisposableList.Nil
    }
}

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
```

列表已准备就绪，后续需要动态将 disposable 添加到 DisposableList 上。

```kotlin
class CompletionHandlerDisposable(
	val onCompletion: (Result<Unit>) -> Unit
): Dispoable {
  
}

abstract class AbstractContinuation<T>(context: CoroutineContext): Continuation<T>, Job {
	
  private val state: AtomicReference<CoroutineState> = AtomicReference(CoroutineState.Incomplete())
  
  override suspend fun join() {
      when(state.get()) {
          is CoroutineState.Incomplete -> suspendJoin()
          is CoroutineState.Completion -> return
      }
  }
  
  private suspend fun suspendJoin() {
    suspendCoroutine { continuation ->
      val disposable = CompletionHandlerDisposable { result -> continuation.resume(Unit) }
      // 判断当前状态，如果是 Incomplete 则将 disposable 添加到列表中
      state.updateAndGet { prev ->
          when(prev) {
              is CoroutineState.Incomplete -> {
                  CoroutineState.Incomplete().from(prev).with(disposable)
              }
              is CoroutineState.Completion -> {
                  prev
              }
          }
      }
    }  
  }
}
```

什么时候将 `AbstractContinuation` 的状态从 `Incomplete` 转化为 `Completion` 呢？==在 Continuation 的 resumeWith 方法调用的时候==，所以接下来就补充一下 resumeWith 方法。这个方法实际上就是将状态进行流转，如果之前是 `Incomplete`，则转化为 `Completion` 并将列表中的事件唤醒；如果之前就是 `Completion ` 则报错。

```kotlin
abstract class AbstractContinuation<T>(context: CoroutineContext): Continuation<T> {
  
  override fun resumeWith(result: Result<T>) {
      val newState = state.getAndUpdate { prev ->
          when (prev) {
              is CoroutineState.Incomplete -> {
                  CoroutineState.Completion().from(prev)
              }
              is CoroutineState.Completion -> throw IllegalArgumentException("Already completed")
          }
      }

      newState.notifyCompletion(result)
      newState.clear()
  }
}
```

接下来就是实现 `notifyCompletion()` 方法了，这个方法实际上就是遍历列表中的所有元素，并调用他们的 `onComplete` 方法。首先 disposableList 并不是传统的集合类，所以无法使用 for 循环直接遍历。需要扩展 DisposableList 的 forEach 方法。其次发现接口类型是 Disposable，没有 onComplete() 方法。

```kotlin
sealed class CoroutineState {
  
  private var disposableList: DisposableList = DisposableList.Nil
  
  fun <T> notifyCompletion(result: Result<T>) {
      disposableList.forEach<CompletionHandlerDisposable> { disposable -> disposable.onCompletion(result) }
  }
}


fun DisposableList.forEach(action: (Disposable) -> Unit) {
inline fun<reified T> DisposableList.forEach(action: (T) -> Unit) {
    when(this) {
        is DisposableList.Nil -> {
            return
        }
        is DisposableList.Cons -> {
            if (head is T) {
                action.invoke(this.head)
            }
            this.tail.forEach(action)
        }
    }
}
```

原本应该写成这样，但可惜的是这里用了递归，不能将其写成 inline function，所以我们将其修改一下

```kotlin
sealed class CoroutineState {
  
  private var disposableList: DisposableList = DisposableList.Nil
  
  fun <T> notifyCompletion(result: Result<T>) {
      disposableList.looperOn<CompletionHandlerDisposable> { disposable -> disposable.onCompletion(result) }
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
```

由于这里 onComplete 的参数类型不再是 Unit，需要改成泛型

```kotlin
class CompletionHandlerDisposable<T>(
    val onCompletion: (Result<T>) -> Unit
): Disposable {

}

fun <T> notifyCompletion(result: Result<T>) {
    disposableList.looperOn<CompletionHandlerDisposable<T>> { disposable -> disposable.onCompletion(result) }
}

private suspend fun suspendJoin() {
    suspendCoroutine { continuation ->
        // 注册回调，等状态从 incomplete -> Completion 后执行 resume 恢复挂起
        val disposable = CompletionHandlerDisposable<Unit> { result -> continuation.resume(Unit) }
        val newState = state.updateAndGet { prev ->
            when(prev) {
                is CoroutineState.Incomplete -> {
                    CoroutineState.Incomplete().from(prev).with(disposable)
                }
                is CoroutineState.Completion -> {
                    prev
                }
            }
        }

    }
}
```

Continuation 封装基本写完了，我们补充一下 launch 方法，并测试一下

```kotlin
class DefaultContinuation<T>(context: CoroutineContext): AbstractContinuation<T>(context) {

}

suspend fun <T> launch(context: CoroutineContext = EmptyCoroutineContext, block: suspend () -> T): Continuation<T> {
    val continuation = DefaultContinuation<T>(context)
    block.startCoroutine(continuation)
    return continuation
}

suspend fun main() {

    val continuation = launch {
        println("in launch, prepare to delay 3s")
        delay(3000)
        println("after delay")
    }

    println("prepare to join")
    continuation.join()
    println("after join")
}

in launch, prepare to delay 3s
prepare to join
after delay
after join
```



## async 和 await

我们前面已经实现了 launch 和 join，但 join 只能等待线程执行完成，没办法等待并获取结果，因此我们基于 Job 在定义一个接口 `Deferred`。并新建 `DeferredContinuation` 实现类。await 的功能类似 join，如果当前状态已经是 Completion，则直接返回结果；如果是 Incomplete，则挂起调用协程等待结果返回

```kotlin
interface Deferred<T>: Job {

    suspend fun await(): T
}

class DeferredContinuation<T>(context: CoroutineContext): AbstractContinuation<T>(context), Deferred<T> {

    override suspend fun await(): T {
        return when (state.get()) {
            is CoroutineState.Completion -> {

            }
            is CoroutineState.Incomplete -> awaitSuspend()
        }
    }


    private suspend fun awaitSuspend(): T {
        return suspendCoroutine { continuation ->
            val disposable = CompletionHandlerDisposable { result ->
                continuation.resumeWith(result)
            }
            state.updateAndGet { prev ->
                when(prev) {
                    is CoroutineState.Completion -> {
                        prev
                    }
                    is CoroutineState.Incomplete -> {
                        CoroutineState.Incomplete().from(prev).with(disposable)
                    }
                }
            }
        }
    }
}
```

按我们现在的代码，对于已经是 Completion 状态时，无法返回结果，所以需要对 Completion 类型进行扩展。并且可以发现 `awaitSuspend` 和 `joinSuspend` 里面有大量重复代码，所以将这部分封装为一个独立接口。

```kotlin
class Completion<T>(var value: T? = null, var exception: Throwable? = null): CoroutineState()

abstract class AbstractContinuation<T>(context: CoroutineContext): Continuation<T>, Job {
  private suspend fun suspendJoin() {
      suspendCoroutine { continuation ->
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
```

接下来就是完成 async 代码，这部分代码和 launch 基本一致.

```kotlin
fun<T> async(context: CoroutineContext = EmptyCoroutineContext, block: suspend () -> T): Deferred<T> {
    val continuation = DeferredContinuation<T>(context)
    block.startCoroutine(continuation)
    return continuation
}
```

最后写一个测试代码来结尾

```kotlin
val deferred = async {
    delay(1000)
    "hello world"
}

val result = deferred.await()
println(result)
```





## 调度器

**调度的本质是解决挂起点恢复后的协程逻辑在哪里问题的问题**，而拦截器是用于拦截协程恢复逻辑的，所以我们要先自定义一个拦截器。

```kotlin
class DispatchInterceptor() : ContinuationInterceptor {

    override val key: CoroutineContext.Key<*> = ContinuationInterceptor

    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
        return DispatchContinuation(continuation)
    }

}

class DispatchContinuation<T>(
    private val delegate: Continuation<T>): Continuation<T> by delegate{

    override fun resumeWith(result: Result<T>) {
        thread {
            delegate.resumeWith(result)
        }
    }
}
```

这里的 resumeWith 只是一个示例，将 delegate 的 resumeWith 放到自线程中去执行。为了实现切面，我们定义一个 Dispatcher 接口，并将其添加到 DispatcherContinuation 的入参中。

```kotlin
interface Dispatcher {

    fun dispatch(block: () -> Unit)
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
```

接下来就是实现多种调度器，首先实现基于线程池的调度器。实际上就是创建一个固定线程数量的线程池，并且用于服务 CPU 密集型程序。

```kotlin
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
```

接下来实现基于 UI 事件循环的调度器。这里以 Android 侧为例，官方会每个平台做适配

```kotlin
object AndroidDispatcher: Dispatcher {
    private val handler = Handler(Looper.getMainLooper())
    
    override fun dispatch(block: () -> Unit) {
        handler.post(block)
    }

}
```

为了方便使用，用 Dispatchers 对象持有来持有调度器

```kotlin
object Dispatchers {
    val Default by lazy {
        DispatchInterceptor(DefaultDispatcher)
    }

    val Main by lazy {
        DispatchInterceptor(AndroidDispatcher)
    }
}
```

后续就可以在调用 launch 和 async 方法时提供调度器了

```kotlin
val threadContinuation = launch(Dispatchers.Default) {
    println(Thread.currentThread().name)
    delay(3000)
    println(Thread.currentThread().name)
}

threadContinuation.join()

DefaultDispatcher-worker-0
DefaultDispatcher-worker-1
```

你可以发现这里线程名是不一样的，因为 delay 是一个异步挂起，会再次触发拦截器。



## 线程取消







## 异常处理





## 作用域



