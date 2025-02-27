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
package com.machiav3lli.backup.manager.handler

import android.app.usage.StorageStats
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Process
import com.machiav3lli.backup.BACKUP_INSTANCE_PROPERTIES_INDIR
import com.machiav3lli.backup.BACKUP_INSTANCE_REGEX_PATTERN
import com.machiav3lli.backup.BACKUP_PACKAGE_FOLDER_REGEX_PATTERN
import com.machiav3lli.backup.BACKUP_SPECIAL_FILE_REGEX_PATTERN
import com.machiav3lli.backup.BACKUP_SPECIAL_FOLDER_REGEX_PATTERN
import com.machiav3lli.backup.ERROR_PREFIX
import com.machiav3lli.backup.IGNORED_PERMISSIONS
import com.machiav3lli.backup.MAIN_FILTER_SYSTEM
import com.machiav3lli.backup.MAIN_FILTER_USER
import com.machiav3lli.backup.NeoApp
import com.machiav3lli.backup.PROP_NAME
import com.machiav3lli.backup.R
import com.machiav3lli.backup.data.dbs.entity.AppInfo
import com.machiav3lli.backup.data.dbs.entity.Backup
import com.machiav3lli.backup.data.dbs.entity.SpecialInfo
import com.machiav3lli.backup.data.dbs.repository.PackageRepository
import com.machiav3lli.backup.data.entity.Package
import com.machiav3lli.backup.data.entity.Package.Companion.invalidateBackupCacheForPackage
import com.machiav3lli.backup.data.entity.StorageFile
import com.machiav3lli.backup.data.preferences.traceBackupsScan
import com.machiav3lli.backup.data.preferences.traceBackupsScanAll
import com.machiav3lli.backup.data.preferences.traceTiming
import com.machiav3lli.backup.manager.actions.BaseAppAction.Companion.ignoredPackages
import com.machiav3lli.backup.manager.handler.LogsHandler.Companion.logException
import com.machiav3lli.backup.manager.handler.ShellCommands.Companion.currentProfile
import com.machiav3lli.backup.manager.handler.ShellHandler.Companion.runAsRoot
import com.machiav3lli.backup.ui.pages.pref_backupSuspendApps
import com.machiav3lli.backup.ui.pages.pref_createInvalidBackups
import com.machiav3lli.backup.ui.pages.pref_earlyEmptyBackups
import com.machiav3lli.backup.ui.pages.pref_lookForEmptyBackups
import com.machiav3lli.backup.utils.FileUtils.ensureBackups
import com.machiav3lli.backup.utils.SystemUtils.numCores
import com.machiav3lli.backup.utils.TraceUtils
import com.machiav3lli.backup.utils.TraceUtils.beginNanoTimer
import com.machiav3lli.backup.utils.TraceUtils.endNanoTimer
import com.machiav3lli.backup.utils.TraceUtils.formatBackups
import com.machiav3lli.backup.utils.TraceUtils.logNanoTiming
import com.machiav3lli.backup.utils.getInstalledPackageInfosWithPermissions
import com.machiav3lli.backup.utils.specialBackupsEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.java.KoinJavaComponent.get
import timber.log.Timber
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

val regexBackupInstance = Regex(BACKUP_INSTANCE_REGEX_PATTERN)
val regexPackageFolder = Regex(BACKUP_PACKAGE_FOLDER_REGEX_PATTERN)
val regexSpecialFolder = Regex(BACKUP_SPECIAL_FOLDER_REGEX_PATTERN)
val regexSpecialFile = Regex(BACKUP_SPECIAL_FILE_REGEX_PATTERN)

val maxThreads = AtomicInteger(0)
val usedThreadsByName = mutableMapOf<String, AtomicInteger>()
fun clearThreadStats() {
    synchronized(usedThreadsByName) {
        usedThreadsByName.clear()
    }
}

fun checkThreadStats() {
    val nThreads = Thread.activeCount()
    maxThreads.getAndUpdate {
        if (it < nThreads)
            nThreads
        else
            it
    }
    synchronized(usedThreadsByName) {
        usedThreadsByName.getOrPut(Thread.currentThread().name) { AtomicInteger(0) }
            .getAndIncrement()
    }
}

val scanPool = when (1) {

    // force hang for recursive scanning
    0    -> Executors.newFixedThreadPool(1).asCoroutineDispatcher()

    // may hang for recursive scanning because threads are limited
    0    -> Executors.newFixedThreadPool(numCores).asCoroutineDispatcher()

    // unlimited threads!
    0    -> Executors.newCachedThreadPool().asCoroutineDispatcher()

    // creates many threads (~65)
    else -> Dispatchers.IO

    // TODO hg42 it's still not 100% clear, if queue based scanning prevents hanging
}

suspend fun scanBackups(
    directory: StorageFile,
    packageName: String = "",
    backupRoot: StorageFile,
    level: Int = 0,
    forceTrace: Boolean = false,
    damagedOp: String? = null,
    onValidBackup: suspend (StorageFile) -> Unit,
    onInvalidBackup: suspend (StorageFile, StorageFile?, String?, String?) -> Unit,
) {
    val files = ConcurrentLinkedDeque<StorageFile>()
    val suspicious = AtomicInteger(0)

    if (level == 0 && packageName.isEmpty() && traceTiming.pref.value) {
        checkThreadStats()
        traceTiming { "threads max: ${maxThreads.get()} (before)" }
    }

    fun formatBackupFile(file: StorageFile) =
        file.path?.removePrefix(backupRoot.path ?: "")?.removePrefix("/") ?: ""

    fun traceBackupsScanPackage(lazyText: () -> String) {
        if (forceTrace) {
            if (packageName.isEmpty())
                TraceUtils.traceImpl("[BackupsScanAll] ${lazyText()}")
            else
                TraceUtils.traceImpl("[BackupsScan] ${lazyText()}")
        } else {
            if (packageName.isEmpty())
                traceBackupsScanAll(lazyText)
            else
                traceBackupsScan(lazyText)
        }
    }

    fun logSuspicious(file: StorageFile, reason: String) {
        formatBackupFile(file)
            .replace(ERROR_PREFIX, "")
            .let { relPath ->
                val message = "? $relPath ($reason)"
                NeoApp.addInfoLogText(message)
                traceBackupsScanAll { message }
            }
    }

    fun renameDamagedToERROR(file: StorageFile, reason: String) {
        when (damagedOp) {
            "ren" ->
                runCatching {
                    NeoApp.hitBusy()
                    val newName = "$ERROR_PREFIX${file.name}"
                    file.renameTo(newName)
                    suspicious.getAndIncrement()
                    logSuspicious(file, reason)
                }

            null  -> {
                suspicious.getAndIncrement()
                logSuspicious(file, reason)
            }
        }
    }

    fun onErrorPrefix(file: StorageFile) {
        runCatching {
            file.name?.let { name ->
                if (name.startsWith(ERROR_PREFIX)) {
                    NeoApp.hitBusy()
                    when (damagedOp) {
                        "undo" -> {
                            val newName = name.removePrefix(ERROR_PREFIX)
                            if (file.renameTo(newName)) {
                                suspicious.getAndIncrement()
                                logSuspicious(file, "undo")
                            }
                        }

                        "del"  -> {
                            if (file.deleteRecursive()) {
                                suspicious.getAndIncrement()
                                logSuspicious(file, "delete")
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun handleInvalidProps(
        dir: StorageFile,
        file: StorageFile? = null,
        packageName: String? = null,
    ) {
        if (damagedOp != null)
            renameDamagedToERROR(dir, "no-props")
        else
            onInvalidBackup(dir, file, null, "no-props")
    }

    suspend fun handleEmptyBackup(
        dir: StorageFile,
        file: StorageFile? = null,
        packageName: String? = null,
    ) {
        if (damagedOp != null)
            renameDamagedToERROR(dir, "empty-backup")
        else
            onInvalidBackup(dir, file, null, "empty-backup")
    }

    suspend fun handleProps(
        file: StorageFile,
        path: String?,
        name: String?,
        onPropsFile: suspend (StorageFile) -> Unit,
        renamer: (suspend () -> Unit)? = null,
    ) {
        NeoApp.hitBusy()

        try {
            if (file.name == BACKUP_INSTANCE_PROPERTIES_INDIR
                ||
                (file.name?.removeSuffix(
                    ".$PROP_NAME"
                )?.let {
                    file.parent?.findFile(
                        it
                    )
                } != null)
            ) {
                beginNanoTimer("scanBackups.${if (packageName.isEmpty()) "" else "package."}onPropsFile")
                onPropsFile(file)
                endNanoTimer("scanBackups.${if (packageName.isEmpty()) "" else "package."}onPropsFile")
            } else
                renameDamagedToERROR(file, "no-dir")
        } catch (_: Throwable) {
            if (renamer != null)
                renamer()
            else {
                name?.let {
                    if (!it.contains(regexSpecialFile))
                        renameDamagedToERROR(file, "damaged")
                }
            }
        }
    }

    suspend fun handleDirectory(
        file: StorageFile,
        collector: FlowCollector<StorageFile>? = null,
    ): Boolean {
        NeoApp.hitBusy()

        beginNanoTimer("scanBackups.${if (packageName.isEmpty()) "" else "package."}listFiles")
        var list = file.listFiles()
        endNanoTimer("scanBackups.${if (packageName.isEmpty()) "" else "package."}listFiles")

        if (list.isEmpty())
            return false

        // all files for undo or del, otherwise filter
        if (damagedOp in listOf("ren", null)) {
            // queue at front of queue (depth first)
            // filter out dir matching dir.properties
            val props = list.mapNotNull { it.name }.filter { it.endsWith(".$PROP_NAME") }
            val propDirs = props.map { it.removeSuffix(".$PROP_NAME") }

            if (damagedOp == "ren" || pref_lookForEmptyBackups.value) {
                list.filter { it.name in propDirs }.forEach { dir ->
                    runCatching {   // in case it's not a directory etc.
                        if (dir.listFiles().isEmpty())
                            handleEmptyBackup(dir = dir)
                    }
                }
            }

            list = list.filterNot { it.name in propDirs }
        }

        // if matching directories are filtered out, we can sort normally,
        // so we sort reverted and push to front one by one
        list.sortedByDescending { it.name }.forEach {
            files.offerFirst(it)
        }

        return true
    }

    fun indent(level: Int, mark: String) = ":::${"|:::".repeat(level)}i"
    fun traceLine(mark: String, level: Int, file: StorageFile, text: String) =
        "${indent(level, mark)}${formatBackupFile(file)} ${text}"

    suspend fun scanBackupInstance(
        file: StorageFile,
        path: String,
        name: String,
        level: Int,
        onValidBackup: suspend (StorageFile) -> Unit,
    ) {
        traceBackupsScanPackage { traceLine("i", level, file, "instance") }

        if (file.isPropertyFile &&                                  // instance props
            !name.contains(regexSpecialFile)
        ) {
            val props = file
            traceBackupsScanPackage {
                traceLine(
                    ">",
                    level,
                    props,
                    "++++++++++++++++++++ props ok"
                )
            }

            handleProps(props, path, name, onValidBackup)

        } else {

            if (!name.contains(regexSpecialFolder) &&
                file.isDirectory                                    // instance dir
            ) {

                val dir = file
                // directories are filtered out for existing properties,
                // so if it's an instance directory it's solo -> error
                if (name.contains(regexPackageFolder)) {
                    // in case of flatStructure there could be a backup.properties inside
                    // TODO hg42 change so that this can be seen by the directory name
                    try {
                        dir.findFile(BACKUP_INSTANCE_PROPERTIES_INDIR)  // indir props
                            ?.let { props ->

                                traceBackupsScanPackage {
                                    traceLine(
                                        ">",
                                        level,
                                        props,
                                        "++++++++++++++++++++ props indir ok"
                                    )
                                }

                                handleProps(props, props.path, props.name, onValidBackup) {
                                    runCatching {
                                        dir.name?.let { name ->
                                            if (!name.contains(
                                                    regexSpecialFolder
                                                )
                                            ) {
                                                handleInvalidProps(
                                                    dir = dir, file = props,
                                                    packageName = if (packageName.isEmpty()) null else packageName
                                                )
                                            }
                                        }
                                    }
                                }
                            } ?: run {

                            handleInvalidProps(dir = dir)
                        }
                    } catch (_: Throwable) {
                        handleInvalidProps(dir = dir)
                    }

                } else {

                    handleInvalidProps(dir = dir)

                }
            }
        }
    }

    suspend fun processFile(
        file: StorageFile,
    ) {
        checkThreadStats()

        val name = file.name ?: ""
        val path = file.path ?: ""
        if (forceTrace)
            traceBackupsScanPackage { traceLine(">", level, file, "++++++++++++++++++++ file") }

        if (damagedOp in listOf("undo", "del")) {
            // undo for each file
            onErrorPrefix(file)
            // scan all files
            if (file.isDirectory)
                handleDirectory(file)
            // do nothing else
            return
        }

        if (name.contains(regexPackageFolder) ||                    // package folder
            name.contains(regexBackupInstance)                      // or backup instance
        ) {
            if (forceTrace)
                traceBackupsScanPackage {
                    traceLine(
                        "B",
                        level,
                        file,
                        "++++++++++++++++++++ backup"
                    )
                }

            if (path.contains(packageName)) {                           // package matches, empty matches all

                if (name.contains(regexBackupInstance)) {                   // instance ...

                    scanBackupInstance(file, path, name, level, onValidBackup)

                } else {                                                    // no instance

                    if (file.isPropertyFile &&
                        !name.contains(regexSpecialFile)                        // non-instance props (wtf is that? probably a saved file)
                    ) {
                        traceBackupsScanPackage {
                            traceLine(
                                ">",
                                level,
                                file,
                                "++++++++++++++++++++ non-instance props ok (a renamed backup?)"
                            )
                        }

                        handleProps(file, path, name, onValidBackup)

                    } else {
                        if (file.isDirectory) {                                 // non-instance-directory
                            val dir = file
                            traceBackupsScanPackage {
                                traceLine(
                                    "/",
                                    level,
                                    file,
                                    "++++++++++++++++++++ //////////////////// dir ok"
                                )
                            }

                            if (handleDirectory(dir).not()) {
                                // renameDamagedToERROR(dir, "empty-folder")
                            }
                        }
                    }
                }
            }

            // else
            //                                                          // single scan and non matching package -> ignored

        } else {                                                    // no package and no instance

            if (!name.contains(regexSpecialFolder) &&
                file.isDirectory                                        // no package or instance folder
            ) {
                val dir = file
                if (forceTrace)
                    traceBackupsScanPackage {
                        traceLine(
                            "F",
                            level,
                            file,
                            "/\\/\\/\\/\\/\\/\\/\\/\\/\\/\\ folder ok"
                        )
                    }

                if (handleDirectory(dir).not()) {
                    // renameDamagedToERROR(dir, "empty-folder")
                }

            }
        }
    }

    handleDirectory(directory)    // top level directory

    var total = 0
    while (files.isNotEmpty()) {
        if (packageName.isEmpty())
            traceBackupsScanPackage { "queue filled with ${files.size}" }
        runBlocking { // joins jobs, so launched jobs that queue files are finished, before checking the queue
            var count = 0
            val filesFlow = flow {
                while (files.isNotEmpty()) {
                    val file = files.remove()
                    count++
                    total++
                    emit(file)
                }
                if (packageName.isEmpty())
                    traceBackupsScanPackage { "queue empty after $count" }
            }
            filesFlow.collect {
                launch(scanPool) {
                    processFile(it)
                }
            }
        }
    }

    if (packageName.isEmpty()) {
        traceBackupsScanPackage { "queue total ----> $total" }
        if (suspicious.get() > 0)
            NeoApp.addInfoLogText(
                "${
                    when (damagedOp) {
                        "ren"  -> "renamed"
                        "del"  -> "deleted"
                        "undo" -> "undo"
                        else   -> "suspicious"
                    }
                }: ${suspicious.get()}"
            )
    }
}

fun Context.findBackups(
    packageName: String = "",
    damagedOp: String? = null,
    forceTrace: Boolean = false,
): Map<String, List<Backup>> {

    val backupsMap: MutableMap<String, MutableList<Backup>> = mutableMapOf()

    var installedNames: List<String> = emptyList()

    try {
        if (packageName.isEmpty()) {

            NeoApp.beginBusy("findBackups")

            // preset installed packages with empty backups lists
            // this prevents scanning them again when a package needs it's backups later
            // doing it here also avoids setting all packages to empty lists when findbackups fails
            // so there is a chance that scanning for backups of a single package will work later

            //val installedPackages = getInstalledPackageList()   // would scan for backups
            // so do the same without creating a Package for each
            val installedPackages = packageManager.getInstalledPackageInfosWithPermissions()
            val specialInfos =
                SpecialInfo.getSpecialInfos(this)  //TODO hg42 these probably scan for backups
            installedNames =
                installedPackages.map { it.packageName } + specialInfos.map { it.packageName }

            if (pref_earlyEmptyBackups.value)
                NeoApp.emptyBackupsForAllPackages(installedNames)

            clearThreadStats()
        }

        invalidateBackupCacheForPackage(packageName)

        val backupRoot = NeoApp.backupRoot

        //val count = AtomicInteger(0)

        when (1) {
            1 -> {
                runBlocking {

                    //------------------------------------------------------------------------------ scan
                    backupRoot?.let {
                        scanBackups(
                            directory = it,
                            packageName = packageName,
                            backupRoot = it,
                            damagedOp = damagedOp,
                            forceTrace = forceTrace,
                            onValidBackup = { props ->
                                //count.getAndIncrement()
                                Backup.createFrom(props)
                                    ?.let { backup ->
                                        //traceDebug { "put ${backup.packageName}/${backup.backupDate}" }
                                        synchronized(backupsMap) {
                                            backupsMap.getOrPut(backup.packageName) { mutableListOf() }
                                                .add(backup)
                                        }
                                    }
                            },
                            onInvalidBackup = { dir: StorageFile, props: StorageFile?, packageName: String?, why: String? ->
                                //count.getAndIncrement()
                                if (pref_createInvalidBackups.value) {
                                    Backup.createInvalidFrom(dir, props, packageName, why)
                                        ?.let { backup ->
                                            //traceDebug { "put ${backup.packageName}/${backup.backupDate}" }
                                            synchronized(backupsMap) {
                                                backupsMap.getOrPut(backup.packageName) { mutableListOf() }
                                                    .add(backup)
                                            }
                                        }
                                }
                            }
                        )
                    }
                }
            }
        }

        //traceDebug { "-----------------------------------------> backups: $count" }

        if (packageName.isEmpty()) {

            traceBackupsScan { "*** --------------------> findBackups: packages: ${backupsMap.keys.size} backups: ${backupsMap.values.flatten().size}" }

            NeoApp.setBackups(backupsMap)

            // preset installed packages that don't have backups with empty backups lists
            //NeoApp.emptyBackupsForMissingPackages(installedNames)

        } else {
            if (NeoApp.startup)
                traceBackupsScan {
                    "<$packageName> single scan (DURING STARTUP!!!) ${
                        formatBackups(
                            backupsMap[packageName] ?: listOf()
                        )
                    }"
                }
            else
                traceBackupsScan { "<$packageName> single scan ${formatBackups(backupsMap[packageName] ?: listOf())}" }
        }

    } catch (e: Throwable) {
        logException(e, backTrace = true)
    } finally {
        if (packageName.isEmpty()) {

            val time = NeoApp.endBusy("findBackups")
            NeoApp.addInfoLogText("findBackups: ${"%.3f".format(time / 1E9)} sec")

            if (traceTiming.pref.value) {
                logNanoTiming("scanBackups.", "scanBackups")
                traceTiming { "threads max: ${maxThreads.get()}" }
                val threads =
                    synchronized(usedThreadsByName) { usedThreadsByName }.toMap()
                traceTiming { "threads used: (${threads.size})${threads.values}" }
            }
        }
    }

    return backupsMap
}

// TODO respect special filter
fun Context.getPackageInfoList(filter: Int): List<PackageInfo> =
    packageManager.getInstalledPackageInfosWithPermissions()
        .filter { packageInfo: PackageInfo ->
            val isSystem =
                (packageInfo.applicationInfo?.flags ?: 0) and
                        ApplicationInfo.FLAG_SYSTEM == ApplicationInfo.FLAG_SYSTEM
            val isIgnored = packageInfo.packageName.matches(ignoredPackages)
            if (isIgnored)
                Timber.i("ignored package: ${packageInfo.packageName}")
            (if (filter and MAIN_FILTER_SYSTEM == MAIN_FILTER_SYSTEM) isSystem && !isIgnored else false)
                    || (if (filter and MAIN_FILTER_USER == MAIN_FILTER_USER) !isSystem && !isIgnored else false)
        }
        .toList()

fun Context.getInstalledPackageList(): MutableList<Package> { // only used in ScheduledActionTask
    var packageList: MutableList<Package> = mutableListOf()

    try {
        NeoApp.beginBusy("getInstalledPackageList")

        val time = measureTimeMillis {

            val pm = packageManager
            val includeSpecial = specialBackupsEnabled
            val packageInfoList = pm.getInstalledPackageInfosWithPermissions()
            packageList = packageInfoList
                .filterNot { it.packageName.matches(ignoredPackages) }
                .mapNotNull {
                    try {
                        Package(this, it)
                    } catch (e: AssertionError) {
                        Timber.e("Could not create Package for ${it}: $e")
                        null
                    }
                }
                .toMutableList()

            //if (!OABX.appsSuspendedChecked) { //TODO hg42 move somewhere else
            //    packageList.filter { appPackage ->
            //        0 != (OABX.activity?.packageManager
            //            ?.getPackageInfo(appPackage.packageName, 0)
            //            ?.applicationInfo
            //            ?.flags
            //            ?: 0) and ApplicationInfo.FLAG_SUSPENDED
            //    }.apply {
            //        OABX.main?.whileShowingSnackBar(getString(R.string.supended_apps_cleanup)) {
            //            // cleanup suspended package if lock file found
            //            this.forEach { appPackage ->
            //                runAsRoot("pm unsuspend ${appPackage.packageName}")
            //            }
            //            OABX.appsSuspendedChecked = true
            //        }
            //    }
            //}

            // Special Backups must added before the uninstalled packages, because otherwise it would
            // discover the backup directory and run in a special case where the directory is empty.
            // This would mean, that no package info is available – neither from backup.properties
            // nor from PackageManager.
            if (includeSpecial) {
                SpecialInfo.getSpecialInfos(this).forEach {
                    packageList.add(Package(it))
                }
            }

            // don't get backups here, get them lazily if they are used,
            // e.g. to filter old backups
            //val backupsMap = getAllBackups()                              //TODO WECH
            //packageList = packageList.map {
            //    it.apply { updateBackupListAndDatabase(backupsMap[it.packageName].orEmpty()) }
            //}.toMutableList()
        }

        NeoApp.addInfoLogText(
            "getPackageList: ${(time / 1000 + 0.5).toInt()} sec"
        )
    } catch (e: Throwable) {
        logException(e, backTrace = true)
    } finally {
        NeoApp.endBusy("getInstalledPackageList")
    }

    return packageList
}

fun List<Package>.toAppInfoList(): List<AppInfo> =
    filterNot { it.isSpecial }.map { it.packageInfo as AppInfo }

fun List<AppInfo>.toPackageList(
    context: Context,
    blockList: List<String> = listOf(),
): MutableList<Package> {
    var packageList: MutableList<Package> = mutableListOf()

    try {
        NeoApp.beginBusy("toPackageList")

        val includeSpecial = specialBackupsEnabled

        packageList =
            this.filterNot {
                it.packageName.matches(ignoredPackages)
            }
                .mapNotNull {
                    val pkg = try {
                        Package(context, it)
                    } catch (e: AssertionError) {
                        Timber.e("Could not create Package for ${it}: $e")
                        null
                    }
                    //pkg?.updateBackupList(backupsMap[pkg.packageName].orEmpty())
                    pkg
                }
                .toMutableList()

        // Special Backups must added before the uninstalled packages, because otherwise it would
        // discover the backup directory and run in a special case where no the directory is empty.
        // This would mean, that no package info is available – neither from backup.properties
        // nor from PackageManager.
        // TODO show special packages directly without restarting NB
        //val specialList = mutableListOf<String>()
        if (includeSpecial) {
            SpecialInfo.getSpecialInfos(context).forEach {
                if (!blockList.contains(it.packageName)) {
                    //it.updateBackupList(backupsMap[it.packageName].orEmpty())
                    packageList.add(Package(it))
                }
                //specialList.add(it.packageName)
            }
        }

    } catch (e: Throwable) {
        LogsHandler.unexpectedException(e)
    } finally {
        NeoApp.endBusy("toPackageList")
    }

    return packageList
}

suspend fun Context.updateAppTables() {
    val packagesRepo = get<PackageRepository>(PackageRepository::class.java)

    try {
        NeoApp.beginBusy("updateAppTables")

        val installedPackageInfos = packageManager.getInstalledPackageInfosWithPermissions()
        val installedNames = installedPackageInfos.map { it.packageName }.toSet()

        try {
            beginNanoTimer("unsuspend")

            if (!NeoApp.appsSuspendedChecked && pref_backupSuspendApps.value) {
                installedNames.filter { packageName ->
                    0 != (NeoApp.activity?.packageManager
                        ?.getPackageInfo(packageName, 0)
                        ?.applicationInfo
                        ?.flags
                        ?: 0) and ApplicationInfo.FLAG_SUSPENDED
                }.apply {
                    val profileId = currentProfile
                    NeoApp.main?.whileShowingSnackBar(getString(R.string.supended_apps_cleanup)) {
                        // cleanup suspended package if lock file found
                        this.forEach { packageName ->
                            runAsRoot("pm unsuspend --user $profileId $packageName")
                        }
                        NeoApp.appsSuspendedChecked = true
                    }
                }
            }
        } catch (e: Throwable) {
            logException(e, backTrace = true)
        } finally {
            endNanoTimer("unsuspend")
        }

        ensureBackups()
        val backupsMap = packagesRepo.getBackupsMap()

        val specialInfos = SpecialInfo.getSpecialInfos(this)
        val specialNames = specialInfos.map { it.packageName }.toSet()

        val uninstalledPackagesWithBackup =
            try {
                beginNanoTimer("uninstalledPackagesWithBackup")

                (backupsMap.keys - installedNames - specialNames)
                    .mapNotNull {
                        backupsMap[it]?.maxByOrNull { it.backupDate }?.toAppInfo()
                    }
            } catch (e: Throwable) {
                logException(e, backTrace = true)
                emptyList()
            } finally {
                endNanoTimer("uninstalledPackagesWithBackup")
            }

        val appInfoList =
            try {
                beginNanoTimer("appInfoList")

                installedPackageInfos
                    .map { AppInfo(this, it) }
                    .union(uninstalledPackagesWithBackup)
            } catch (e: Throwable) {
                logException(e, backTrace = true)
                emptyList()
            } finally {
                endNanoTimer("appInfoList")
            }

        try {
            beginNanoTimer("dbUpdate")

            packagesRepo.apply {
                replaceBackups(*backupsMap.values.flatten().toTypedArray())
                replaceAppInfos(*appInfoList.toTypedArray())
            }
        } catch (e: Throwable) {
            logException(e, backTrace = true)
        } finally {
            endNanoTimer("dbUpdate")
        }

    } catch (e: Throwable) {
        logException(e, backTrace = true)
    } finally {
        val time = NeoApp.endBusy("updateAppTables")
        NeoApp.addInfoLogText("updateAppTables: ${"%.3f".format(time / 1E9)} sec")
    }
}

@Throws(PackageManager.NameNotFoundException::class)
fun Context.getPackageStorageStats(
    packageName: String,
    storageUuid: UUID = packageManager.getApplicationInfo(packageName, 0).storageUuid,
): StorageStats? {
    val storageStatsManager =
        getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
    return try {
        storageStatsManager.queryStatsForPackage(
            storageUuid,
            packageName,
            Process.myUserHandle()
        )
    } catch (e: IOException) {
        Timber.e("Could not retrieve storage stats of $packageName: $e")
        null
    } catch (e: Throwable) {
        LogsHandler.unexpectedException(e, packageName)
        null
    }
}

fun Context.getSpecial(packageName: String) =
    SpecialInfo.getSpecialInfos(this)
        .find { it.packageName == packageName }
        ?.let { Package(it) }

val PackageInfo.grantedPermissions: List<String>
    get() = requestedPermissions?.filterIndexed { index, perm ->
        (((requestedPermissionsFlags?.getOrNull(index)
            ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED)
                == PackageInfo.REQUESTED_PERMISSION_GRANTED)
                &&
                perm !in IGNORED_PERMISSIONS
    }.orEmpty()
