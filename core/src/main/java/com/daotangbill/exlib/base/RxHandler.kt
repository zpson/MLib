package com.daotangbill.exlib.base

import com.daotangbill.exlib.commons.logger.Lerror
import com.daotangbill.exlib.commons.logger.Linfo
import com.daotangbill.exlib.commons.utils.isFalse
import com.daotangbill.exlib.commons.utils.isTrue
import io.reactivex.Observable
import io.reactivex.ObservableOnSubscribe
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.observers.DisposableObserver
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.TimeUnit

/**
 * Project com.daotangbill.exlib.base
 * Created by DaoTangBill on 2018/3/28/028.
 * Email:tangbaozi@daotangbill.uu.me
 *
 * @author bill
 * @version 1.0
 * @description
 */
class RxHandler {

    private val mCompositeDisposable: CompositeDisposable by lazy {
        CompositeDisposable()
    }

    private val disposableMap: HashMap<String, Disposable> by lazy {
        HashMap<String, Disposable>()
    }

    /**
     * 这个 添加方式  会顶掉前面的 可能 会有问题
     */
    fun put(key: String, disposable: Disposable) {
        disposableMap[key] = disposable
    }

    fun putSafe(key: String, disposable: Disposable): Boolean {
        val t = disposableMap[key]
        return if (t == null) {
            disposableMap[key] = disposable
            true
        } else {
            false
        }
    }

    fun post(block: () -> Unit) {
        postDelayed(block, 0)
    }

    fun postDelayed(block: () -> Unit, time: Long) {
        val disposableObserver = object : DisposableObserver<Any>() {
            override fun onComplete() {
            }

            override fun onNext(t: Any) {
                block()
            }

            override fun onError(e: Throwable) {
            }
        }

        Observable.timer(time, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(disposableObserver)
        mCompositeDisposable.add(disposableObserver)
    }

    /**
     * @param timeLimit 计时次数
     * @param key 当前流的 名字，用于提前取消等操作
     * @param block （time 计数次数，是增加；
     *              key,当前流的名字）
     */
    fun timer(timeLimit: Long, key: String, block: (time: Long, key: String) -> Unit) {
        if (checkKey(key)) {
            return
        }

        val disposableObserver = object : DisposableObserver<Long>() {
            override fun onComplete() {
                disposableMap.remove(key)
            }

            override fun onNext(t: Long) {
                block(t + 1, key)
            }

            override fun onError(e: Throwable) {
                Lerror { "倒计时 出现异常： ${e.printStackTrace()}" }
                disposableMap.remove(key)
            }
        }
        disposableMap[key] = disposableObserver

//        Observable.interval(0, 1000, TimeUnit.MILLISECONDS)
//                .take(timeLimit)
//                .observeOn(AndroidSchedulers.mainThread())
//                .subscribe(disposableObserver)

        Observable.intervalRange(0, timeLimit, 0, 1000, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(disposableObserver)
        mCompositeDisposable.add(disposableObserver)
    }


    fun retry(key: String,
              waitTime: Long,
              block: () -> Boolean,
              success: (() -> Unit)? = null,
              error: (() -> Unit)? = null) {
        retry(key, 0, waitTime, block, success, error)
    }

    /**
     * 失败重试？或者重试
     * @param block : 返回值为  是否重试：true ,重试，false 不重试
     * @param limitime : <=0 代表无线次
     * @param waitTime : ms 重试间隔时间
     * @param success : 成功之后的 操作
     * @param error : 失败之后的 操作
     */
    fun retry(key: String,
              limitime: Int,
              waitTime: Long,
              block: () -> Boolean,
              success: (() -> Unit)? = null,
              error: (() -> Unit)? = null) {
        if (checkKey(key)) {
            return
        }
        var time = 0
        val disposableObserver = object : DisposableObserver<String>() {
            override fun onComplete() {
                Linfo { "retry == onComplete" }
                success?.invoke()
                disposableMap.remove(key)
            }

            override fun onNext(t: String) {
                Linfo { "retry==onNext==$t " }
            }

            override fun onError(e: Throwable) {
                Lerror { "${e.message}====失败了" }
//                disposableMap.remove(key)
                error?.invoke()
            }
        }

        Observable
                .create(ObservableOnSubscribe<String> { e ->
                    val b = block()
                    if (b) {
                        time++
                        if (limitime <= 0) {
                            Linfo { "失败进行第${time}次重试" }
                            e.onError(Throwable("retry"))
                        } else if (limitime > 0 && limitime >= time) {
                            Linfo { "失败进行第${time}次重试" }
                            e.onError(Throwable("retry"))
                        } else {
                            Linfo { "失败，重试次数超限 limit=$limitime , time = $time" }
                            e.onError(Throwable("More retry times"))
                        }
                    } else {
                        Linfo { "成功" }
                        e.onNext("Work Success")
                        e.onComplete()
                    }
                })
                .retryWhen {
                    it.flatMap {
                        val msg = it.message
                        return@flatMap if (msg == "retry") {
                            Observable.timer(waitTime, TimeUnit.MILLISECONDS)
                        } else {
//                            这两个都是可以 取消 重试机制
                            Observable.error(it)
//                            Observable.empty()
                        }
                    }
                }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(disposableObserver)
        disposableMap[key] = disposableObserver
        mCompositeDisposable.add(disposableObserver)
    }

    class NetWorkContext<T> {
        companion object {
            /**
             * 同时只有一个请求，重复请求 拒绝
             */
            const val TYPE_REFUSING_SECOND = 0
        }

        var key: String? = null
        var type: Int = TYPE_REFUSING_SECOND
        var subscribeOnScheduler: Scheduler = Schedulers.io()
        var observeOnScheduler: Scheduler = AndroidSchedulers.mainThread()
        var disposable: DisposableObserver<T>? = null
        var observable: Observable<T>? = null

        fun start() {
            val obs = observable
            val disp = disposable
            if (obs != null && disp != null) {
                obs.subscribeOn(subscribeOnScheduler)
                        .observeOn(observeOnScheduler)
                        .subscribe(disp)
            }
        }
    }

    fun <T> netWork(block: NetWorkContext<T>.() -> Unit): NetWorkContext<T>? {
        val context = NetWorkContext<T>()
        val disp = context.disposable
        val obs = context.observable
        val key = context.key
        context.block()
        if (obs == null || disp == null || key == null || context.key.isNullOrBlank()) {
            return null
        }
        val b: Boolean = when (context.type) {
            NetWorkContext.TYPE_REFUSING_SECOND -> {
                val b = checkKey(key)
                if (b) {
                    false
                }
                mCompositeDisposable.add(disp)
                true
            }
            else -> false
        }
        return if (b) {
            context
        } else {
            null
        }
    }

    fun checkKey(key: String): Boolean {
        val t = disposableMap[key]
        return t != null && t.isDisposed
    }

    fun removeCallbacksAndMessages(key: String? = null, block: ((b: Boolean) -> Unit)? = null) {
        if (key == null) {
            mCompositeDisposable.clear()
            disposableMap.clear()
        } else {
            val t = disposableMap[key]
            t?.let {
                mCompositeDisposable.remove(it)
                        .isTrue {
                            disposableMap.remove(key)
                            block?.invoke(true)
                        }.isFalse {
                            Lerror { "停止指定 disposable 失败==key =$key" }
                            block?.invoke(false)
                        }
            }
        }
    }
}