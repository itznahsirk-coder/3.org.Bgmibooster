package com.bgmibooster

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class AndroidBridge(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val NOTIF_CH = "bgmi_booster_ch"
    private var notifId = 1000

    init { createNotifChannel() }

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(NOTIF_CH, "BGMI Booster Alerts", NotificationManager.IMPORTANCE_HIGH)
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    @JavascriptInterface
    fun getInstalledApps(): String {
        return try {
            val pm = context.packageManager
            val sb = StringBuilder("["); var first = true
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
                .forEach { app ->
                    val isUser = (app.flags and ApplicationInfo.FLAG_SYSTEM == 0) || (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                    if (isUser && app.packageName != context.packageName) {
                        if (!first) sb.append(","); first = false
                        val n = pm.getApplicationLabel(app).toString().replace("\\","\\\\").replace("\"","\\\"")
                        val cat = guessCategory(app.packageName)
                        sb.append("{\"name\":\"$n\",\"pkg\":\"${app.packageName}\",\"cat\":\"$cat\",\"icon\":\"${guessIcon(cat)}\"}")
                    }
                }
            sb.append("]").toString()
        } catch (e: Exception) { "[]" }
    }

    @JavascriptInterface
    fun getInstalledGames(): String {
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val sb = StringBuilder("["); var first = true
            pm.queryIntentActivities(intent, 0).sortedBy { it.loadLabel(pm).toString().lowercase() }.forEach { ri ->
                val ai = ri.activityInfo.applicationInfo
                val isUser = (ai.flags and ApplicationInfo.FLAG_SYSTEM == 0) || (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                if (isUser && ai.packageName != context.packageName) {
                    if (!first) sb.append(","); first = false
                    val n = ri.loadLabel(pm).toString().replace("\\","\\\\").replace("\"","\\\"")
                    val cat = guessCategory(ai.packageName)
                    sb.append("{\"name\":\"$n\",\"pkg\":\"${ai.packageName}\",\"cat\":\"$cat\",\"icon\":\"${guessIcon(cat)}\"}")
                }
            }
            sb.append("]").toString()
        } catch (e: Exception) { "[]" }
    }

    @JavascriptInterface
    fun measurePing(): String {
        return try {
            var best = Long.MAX_VALUE
            listOf(Pair("8.8.8.8",53), Pair("1.1.1.1",53)).forEach { (h,p) ->
                try {
                    val t = System.currentTimeMillis()
                    Socket().use { s -> s.connect(InetSocketAddress(h,p),3000) }
                    val ms = System.currentTimeMillis() - t
                    if (ms < best) best = ms
                } catch (_: Exception) {}
            }
            if (best == Long.MAX_VALUE) "{\"ping\":-1,\"status\":\"OFFLINE\"}"
            else {
                val st = when { best<30->"EXCELLENT"; best<60->"GOOD"; best<100->"OK"; best<150->"HIGH"; else->"POOR" }
                "{\"ping\":$best,\"status\":\"$st\"}"
            }
        } catch (e: Exception) { "{\"ping\":-1,\"status\":\"ERROR\"}" }
    }

    @JavascriptInterface
    fun killBackgroundApps(): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            var k = 0
            context.packageManager.getInstalledApplications(0).forEach { pkg ->
                if (pkg.packageName != context.packageName) { am.killBackgroundProcesses(pkg.packageName); k++ }
            }
            "{\"killed\":$k,\"success\":true}"
        } catch (e: Exception) { "{\"killed\":0,\"success\":false}" }
    }

    @JavascriptInterface
    fun getRamInfo(): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
            val a = mi.availMem/(1024*1024); val t = mi.totalMem/(1024*1024); val u = t-a
            "{\"availMB\":$a,\"totalMB\":$t,\"usedMB\":$u,\"usedPct\":${(u.toFloat()/t*100).toInt()}}"
        } catch (e: Exception) { "{\"availMB\":0,\"totalMB\":6144,\"usedMB\":0,\"usedPct\":0}" }
    }

    @JavascriptInterface
    fun getCpuTemp(): String {
        listOf("/sys/class/thermal/thermal_zone0/temp","/sys/class/thermal/thermal_zone1/temp",
               "/sys/devices/system/cpu/cpu0/cpufreq/cpu_temp","/sys/class/power_supply/BMS/temp").forEach { path ->
            try {
                val raw = File(path).readText().trim().toDoubleOrNull() ?: return@forEach
                val temp = if (raw > 1000) raw/1000.0 else raw
                val st = when { temp<38->"COOL"; temp<43->"WARM"; temp<47->"HOT"; else->"CRITICAL" }
                return "{\"temp\":${"%.1f".format(temp)},\"status\":\"$st\"}"
            } catch (_: Exception) {}
        }
        return "{\"temp\":0,\"status\":\"N/A\"}"
    }

    @JavascriptInterface
    fun getNetworkType(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "{\"type\":\"OFFLINE\",\"connected\":false}"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "{\"type\":\"WiFi\",\"connected\":true}"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "{\"type\":\"Mobile\",\"connected\":true}"
                else -> "{\"type\":\"Connected\",\"connected\":true}"
            }
        } catch (e: Exception) { "{\"type\":\"Unknown\",\"connected\":false}" }
    }

    @JavascriptInterface
    fun launchApp(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: run {
                mainHandler.post { Toast.makeText(context,"Not installed: $packageName",Toast.LENGTH_SHORT).show() }
                return false
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(intent); true
        } catch (e: Exception) { false }
    }

    @JavascriptInterface
    fun isAppInstalled(pkg: String): Boolean {
        return try { context.packageManager.getPackageInfo(pkg,0); true } catch (_: Exception) { false }
    }

    @JavascriptInterface
    fun pinWidget(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            mainHandler.post { Toast.makeText(context,"Requires Android 8+",Toast.LENGTH_SHORT).show() }
            return false
        }
        return try {
            val mgr = AppWidgetManager.getInstance(context)
            if (mgr.isRequestPinAppWidgetSupported) {
                mgr.requestPinAppWidget(ComponentName(context, BoostWidget::class.java), null, null)
                true
            } else {
                mainHandler.post { Toast.makeText(context,"Launcher doesn't support widget pinning",Toast.LENGTH_LONG).show() }
                false
            }
        } catch (e: Exception) { false }
    }

    @JavascriptInterface
    fun cleanCache(): String {
        return try {
            var freed = 0L
            freed += deleteDir(context.cacheDir)
            context.externalCacheDir?.let { freed += deleteDir(it) }
            "{\"freedMB\":${freed/(1024*1024)},\"success\":true}"
        } catch (e: Exception) { "{\"freedMB\":0,\"success\":false}" }
    }

    private fun deleteDir(dir: File?): Long {
        if (dir==null||!dir.exists()) return 0L
        return dir.listFiles()?.sumOf { f -> if(f.isDirectory) deleteDir(f) else { val s=f.length(); f.delete(); s } } ?: 0L
    }

    @JavascriptInterface
    fun sendNotification(title: String, message: String) {
        mainHandler.post {
            try {
                val pi = PendingIntent.getActivity(context, 0,
                    Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
                    PendingIntent.FLAG_IMMUTABLE)
                val notif = NotificationCompat.Builder(context, NOTIF_CH)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title).setContentText(message)
                    .setContentIntent(pi).setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH).build()
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(notifId++, notif)
            } catch (_: Exception) {}
        }
    }

    @JavascriptInterface
    fun hasUsageAccess(): Boolean {
        return try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }

    @JavascriptInterface
    fun requestUsageAccess() {
        mainHandler.post {
            try { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }) }
            catch (_: Exception) {}
        }
    }

    @JavascriptInterface
    fun getForegroundApp(): String {
        if (!hasUsageAccess()) return "{\"pkg\":\"\",\"name\":\"\"}"
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, now-10000, now)
            val pkg = stats?.maxByOrNull { it.lastTimeUsed }?.packageName ?: ""
            val name = try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg,0)).toString() } catch (_: Exception) { pkg }
            "{\"pkg\":\"$pkg\",\"name\":\"${name.replace("\"","\\\"").replace("\\","\\\\")}\"}"}
        catch (e: Exception) { "{\"pkg\":\"\",\"name\":\"\"}" }
    }

    @JavascriptInterface
    fun saveBoostState(activeCount: Int) {
        context.getSharedPreferences("boost_prefs", Context.MODE_PRIVATE).edit().putInt("active_features", activeCount).apply()
        BoostWidget.updateAllWidgets(context)
    }

    @JavascriptInterface
    fun showToast(message: String) { mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() } }

    @JavascriptInterface
    fun vibrate() {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(android.os.VibrationEffect.createOneShot(60, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(60)
        } catch (_: Exception) {}
    }

    private fun guessCategory(pkg: String): String = when {
        listOf("pubg","bgmi","freefire","codm","activision","supercell","gameloft","miniclip","newstate").any{pkg.contains(it)} -> "Game"
        listOf("whatsapp","telegram","signal").any{pkg.contains(it)} -> "Messaging"
        listOf("instagram","facebook","twitter","snapchat","moj","josh").any{pkg.contains(it)} -> "Social"
        listOf("youtube","netflix","hotstar","mxtech","jioplay").any{pkg.contains(it)} -> "Video"
        listOf("spotify","music","gaana","jiosaavn").any{pkg.contains(it)} -> "Music"
        listOf("chrome","firefox","browser").any{pkg.contains(it)} -> "Browser"
        pkg.contains("gmail")||pkg.contains("mail") -> "Email"
        pkg.contains("maps") -> "Navigation"
        listOf("amazon","flipkart","meesho").any{pkg.contains(it)} -> "Shopping"
        pkg.contains("zomato")||pkg.contains("swiggy") -> "Food"
        listOf("phonepe","paytm","gpay","dreamplug").any{pkg.contains(it)} -> "Finance"
        pkg.contains("olacabs")||pkg.contains("ubercab") -> "Transport"
        else -> "App"
    }

    private fun guessIcon(cat: String) = when(cat) {
        "Game"->"🎮";"Messaging"->"💬";"Social"->"📱";"Video"->"▶️";"Music"->"🎵"
        "Browser"->"🌐";"Email"->"📧";"Navigation"->"🗺️";"Shopping"->"🛒"
        "Food"->"🍕";"Finance"->"💳";"Transport"->"🚗";else->"📦"
    }
}
