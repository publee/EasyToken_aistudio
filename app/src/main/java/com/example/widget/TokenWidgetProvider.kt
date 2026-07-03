package com.example.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.util.TokenCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class TokenWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAllWidgets(context, appWidgetManager, appWidgetIds)
        scheduleNextUpdate(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == "com.example.UPDATE_WIDGET" || intent.action == "com.example.REFRESH_ACTION" || intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, TokenWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            updateAllWidgets(context, appWidgetManager, allWidgetIds)
            scheduleNextUpdate(context)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // Immediately update this widget with options changes to respond to resizing instantly
        updateAllWidgets(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scheduleNextUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelUpdate(context)
    }

    private fun scheduleNextUpdate(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, TokenWidgetProvider::class.java).apply {
            action = "com.example.UPDATE_WIDGET"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            12345,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // For TOTP, updating every 30 seconds ensures the code is always fresh
        val triggerAtMillis = System.currentTimeMillis() + 30_000L

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC, triggerAtMillis, pendingIntent)
            }
        } catch (e: Exception) {
            alarmManager.set(AlarmManager.RTC, triggerAtMillis, pendingIntent)
        }
    }

    private fun cancelUpdate(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, TokenWidgetProvider::class.java).apply {
            action = "com.example.UPDATE_WIDGET"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            12345,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun updateAllWidgets(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val selectedId = prefs.getInt("selected_token_id", -1)

        CoroutineScope(Dispatchers.IO).launch {
            val database = AppDatabase.getDatabase(context)
            val tokens = database.tokenDao().getAllTokens().firstOrNull() ?: emptyList()
            val selectedToken = tokens.find { it.id == selectedId } ?: tokens.firstOrNull()

            for (widgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_token)
                
                val options = appWidgetManager.getAppWidgetOptions(widgetId)
                val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)

                // If resized to compact 1x1 size (width < 120dp or height < 110dp on any grid layout)
                val isCompact = (minWidth in 1..119) || (minHeight in 1..109)

                if (isCompact) {
                    views.setViewVisibility(R.id.header_layout, View.GONE)
                    views.setViewVisibility(R.id.widget_token_time, View.GONE)
                } else {
                    views.setViewVisibility(R.id.header_layout, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_token_time, View.VISIBLE)
                }

                // Adjust root layout padding dynamically to maximize layout space in 1x1 widget
                val density = context.resources.displayMetrics.density
                val paddingPx = (if (isCompact) 2 else 12) * density
                views.setViewPadding(R.id.widget_root, paddingPx.toInt(), paddingPx.toInt(), paddingPx.toInt(), paddingPx.toInt())

                // Scale font size down dynamically to fit 1x1 area perfectly
                val textSizeSp = if (isCompact) 15f else 26f
                views.setTextViewTextSize(R.id.widget_token_code, TypedValue.COMPLEX_UNIT_SP, textSizeSp)

                if (selectedToken != null) {
                    val passcode = when (selectedToken.type) {
                        "SECURID" -> TokenCalculator.calculateSecurID(
                            secret = selectedToken.secret,
                            timeSeconds = System.currentTimeMillis() / 1000L,
                            digits = selectedToken.digits,
                            interval = selectedToken.interval,
                            pin = selectedToken.pin,
                            serial = selectedToken.serial
                        )
                        "HOTP" -> TokenCalculator.calculateHOTP(
                            secret = selectedToken.secret,
                            counter = selectedToken.counter,
                            digits = selectedToken.digits
                        )
                        else -> TokenCalculator.calculateTOTP(
                            secret = selectedToken.secret,
                            timeSeconds = System.currentTimeMillis() / 1000L,
                            digits = selectedToken.digits,
                            interval = selectedToken.interval
                        )
                    }

                    val isTimeBased = selectedToken.type != "HOTP"
                    val interval = selectedToken.interval.coerceAtLeast(1)
                    val elapsedInCycle = (System.currentTimeMillis() / 1000L) % interval
                    val remainingSeconds = interval - elapsedInCycle

                    views.setTextViewText(R.id.widget_token_name, selectedToken.name)
                    views.setTextViewText(R.id.widget_token_serial, if (selectedToken.serial.isNotEmpty()) "ID: ${selectedToken.serial}" else "MFA Soft-Token")
                    
                    // Format passcode spacing - use newline for compact (2 rows), space for normal
                    val formatted = if (isCompact) {
                        when {
                            passcode.length == 6 -> passcode.substring(0, 3) + "\n" + passcode.substring(3, 6)
                            passcode.length == 8 -> passcode.substring(0, 4) + "\n" + passcode.substring(4, 8)
                            passcode.length == 10 -> passcode.substring(0, 5) + "\n" + passcode.substring(5, 10)
                            else -> passcode
                        }
                    } else {
                        when {
                            passcode.length == 6 -> passcode.substring(0, 3) + " " + passcode.substring(3, 6)
                            passcode.length == 8 -> passcode.substring(0, 4) + " " + passcode.substring(4, 8)
                            passcode.length == 10 -> passcode.substring(0, 5) + " " + passcode.substring(5, 10)
                            else -> passcode
                        }
                    }
                    views.setTextViewText(R.id.widget_token_code, formatted)
                    
                    val timeLabel = if (isTimeBased) "Valid for ${remainingSeconds}s" else "Counter-based code"
                    views.setTextViewText(R.id.widget_token_time, timeLabel)
                } else {
                    views.setTextViewText(R.id.widget_token_name, "EasyToken")
                    views.setTextViewText(R.id.widget_token_serial, "No keys registered")
                    views.setTextViewText(R.id.widget_token_code, if (isCompact) "---\n---" else "------")
                    views.setTextViewText(R.id.widget_token_time, "Open app to add a token")
                }

                // Setup refresh button intent
                val refreshIntent = Intent(context, TokenWidgetProvider::class.java).apply {
                    action = "com.example.REFRESH_ACTION"
                }
                val refreshPendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_refresh_button, refreshPendingIntent)

                // Setup content intent to open main activity
                val openAppIntent = Intent(context, MainActivity::class.java)
                val openAppPendingIntent = PendingIntent.getActivity(
                    context,
                    1,
                    openAppIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_token_code, openAppPendingIntent)

                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }
    }
}
