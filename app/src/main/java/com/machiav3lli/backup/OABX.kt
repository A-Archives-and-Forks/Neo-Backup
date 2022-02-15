/*
 * OAndBackupX: open-source apps backup and restore app.
 * Copyright (C) 2020  Antonios Hazim
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.machiav3lli.backup

import android.app.Application
import android.content.Context
import android.util.LruCache
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner
import com.machiav3lli.backup.activities.MainActivityX
import com.machiav3lli.backup.handler.ShellHandler
import com.machiav3lli.backup.handler.WorkHandler
import com.machiav3lli.backup.items.AppInfo
import com.machiav3lli.backup.services.ScheduleService
import com.machiav3lli.backup.utils.getDefaultSharedPreferences
import timber.log.Timber
import java.lang.ref.WeakReference

class OABX : Application(), LifecycleObserver {

    var cache: LruCache<String, MutableList<AppInfo>> = LruCache(4000)

    var work: WorkHandler? = null

    companion object {

        // app should always be created
        var appRef: WeakReference<OABX> = WeakReference(null)
        val app: OABX get() = appRef.get()!!

        // service might be null
        var serviceRef: WeakReference<ScheduleService> = WeakReference(null)
        var service: ScheduleService?
            get() {
                return serviceRef.get()
            }
            set(service) {
                serviceRef = WeakReference(service)
            }

        // activity might be null
        var activityRef: WeakReference<MainActivityX> = WeakReference(null)
        var activity: MainActivityX?
            get() {
                return activityRef.get()
            }
            set(activity) {
                activityRef = WeakReference(activity)
            }

        var appsSuspendedChecked = false

        var shellHandlerInstance: ShellHandler? = null
            private set

        fun initShellHandler(): Boolean {
            return try {
                shellHandlerInstance = ShellHandler()
                true
            } catch (e: ShellHandler.ShellCommandFailedException) {
                false
            }
        }

        val context: Context get() = app.applicationContext
        val work: WorkHandler get() = app.work!!

        fun prefFlag(name: String, default: Boolean) = context.getDefaultSharedPreferences()
            .getBoolean(name, default)

        fun prefInt(name: String, default: Int) = context.getDefaultSharedPreferences()
            .getInt(name, default)
    }

    override fun onCreate() {
        super.onCreate()
        appRef = WeakReference(this)

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        Timber.plant(object : Timber.DebugTree() {

            override fun log(
                priority: Int, tag: String?, message: String, t: Throwable?
            ) {
                super.log(priority, "$tag", message, t)
            }

            override fun createStackElementTag(element: StackTraceElement): String {
                return "${
                    element.methodName
                }@${
                    element.lineNumber
                }:${
                    super.createStackElementTag(element)
                }"
            }
        })

        initShellHandler()
        work = WorkHandler(context)
        work?.prune()
    }

    override fun onTerminate() {
        work = work?.release()
        appRef = WeakReference(null)
        super.onTerminate()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onAppBackgrounded() {
        Timber.d("App in background")
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onAppForegrounded() {
        Timber.d("App in foreground")
    }
}
