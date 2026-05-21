package com.phantom.app.util

import android.util.Log
import top.niunaijun.blackboxa.BuildConfig

/**
 * Logger wrapper that strips verbose/debug calls in release builds.
 *
 * `d` and `v` are no-ops when `BuildConfig.DEBUG` is false. Combined with
 * the `-assumenosideeffects` ProGuard rule on this class, R8 will also
 * remove the bytecode of the call sites in release, so neither the strings
 * being logged nor the log statement itself remain in the release APK.
 *
 * `w` and `e` are passed through unconditionally — they are kept in release
 * because they signal real conditions worth surfacing in any future crash
 * reporter (Crashlytics, Sentry, etc.).
 *
 * `i` is also kept in release: rare, intentional info-level breadcrumbs.
 */
object Slog {
    @JvmField
    val IS_DEBUG: Boolean = BuildConfig.DEBUG

    @JvmStatic
    fun d(tag: String, msg: String) {
        if (IS_DEBUG) Log.d(tag, msg)
    }

    @JvmStatic
    fun v(tag: String, msg: String) {
        if (IS_DEBUG) Log.v(tag, msg)
    }

    @JvmStatic
    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    @JvmStatic
    @JvmOverloads
    fun w(tag: String, msg: String, e: Throwable? = null) {
        if (e != null) Log.w(tag, msg, e) else Log.w(tag, msg)
    }

    @JvmStatic
    @JvmOverloads
    fun e(tag: String, msg: String, e: Throwable? = null) {
        if (e != null) Log.e(tag, msg, e) else Log.e(tag, msg)
    }
}
