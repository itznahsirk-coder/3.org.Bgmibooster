package com.bgmibooster

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class BoostWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    companion object {
        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.boost_widget)

            // Tap opens the app
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_boost_btn, pendingIntent)
            views.setOnClickPendingIntent(R.id.widget_status, pendingIntent)

            val prefs = context.getSharedPreferences("boost_prefs", Context.MODE_PRIVATE)
            val featCount = prefs.getInt("active_features", 0)
            views.setTextViewText(R.id.widget_status,
                if (featCount > 0) "✅ $featCount Features Active" else "Tap to open app")

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BoostWidget::class.java))
            val provider = BoostWidget()
            provider.onUpdate(context, manager, ids)
        }
    }
}
