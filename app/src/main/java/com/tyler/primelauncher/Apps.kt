package com.tyler.primelauncher

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.provider.Settings

data class AppEntry(val pkg: String, val label: String, val component: ComponentName, val user: UserHandle)

/** Installed launchable apps + usage ranking. */
object Apps {
    @Volatile var list: List<AppEntry> = emptyList(); private set
    @Volatile var usage: Map<String, Long> = emptyMap(); private set

    fun refresh(ctx: Context) {
        if (frozen) return
        val la = ctx.getSystemService(LauncherApps::class.java)
        val out = ArrayList<AppEntry>()
        for (u in la.profiles) for (a in la.getActivityList(null, u)) {
            if (a.applicationInfo.packageName == ctx.packageName) continue
            out.add(AppEntry(a.applicationInfo.packageName, a.label?.toString()?.trim().orEmpty().ifEmpty { a.applicationInfo.packageName }, a.componentName, a.user))
        }
        list = out.distinctBy { it.pkg + "/" + it.component.className + "/" + it.user.hashCode() }.sortedBy { it.label.lowercase() }
        usage = if (hasUsageAccess(ctx)) loadUsage(ctx) else emptyMap()
    }
    fun byPkg(pkg: String): AppEntry? = list.firstOrNull { it.pkg == pkg }
    /** Tests only: Robolectric has no launcher activities. */
    fun setForTest(apps: List<AppEntry>, use: Map<String, Long>) { list = apps.sortedBy { it.label.lowercase() }; usage = use; frozen = true }
    @Volatile private var frozen = false

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }
    private fun loadUsage(ctx: Context): Map<String, Long> = runCatching {
        val um = ctx.getSystemService(UsageStatsManager::class.java); val now = System.currentTimeMillis()
        um.queryAndAggregateUsageStats(now - 30L * 24 * 3600 * 1000, now).mapValues { it.value.totalTimeInForeground }
    }.getOrDefault(emptyMap())

    /** The app ring: pinned apps only (0.1.12). */
    fun ring(ctx: Context, pins: List<String>): List<AppEntry> = Logic.pinnedRing(list.map { it.pkg }, pins).mapNotNull { byPkg(it) }
    /** Empty-search suggestions: most used, then pins. */
    fun suggest(ctx: Context, pins: List<String>): List<AppEntry> {
        val ids = Logic.ringPkgs(list.map { it.pkg to it.label }.distinctBy { it.first }, usage, pins)
        return ids.mapNotNull { byPkg(it) }
    }

    fun launch(ctx: Context, a: AppEntry): Boolean = runCatching {
        ctx.getSystemService(LauncherApps::class.java).startMainActivity(a.component, a.user, null, null); true
    }.getOrElse { launchPkg(ctx, a.pkg) }
    fun launchPkg(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }
    fun appInfo(ctx: Context, a: AppEntry) = runCatching { ctx.getSystemService(LauncherApps::class.java).startAppDetailsActivity(a.component, a.user, null, null) }
    /** Real uninstall: the system shows its own confirmation. */
    fun uninstall(ctx: Context, pkg: String) = runCatching {
        @Suppress("DEPRECATION") ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun usageSettings(ctx: Context) = runCatching { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
