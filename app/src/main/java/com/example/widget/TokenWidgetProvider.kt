package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == "com.example.UPDATE_WIDGET" || intent.action == "com.example.REFRESH_ACTION") {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, TokenWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            updateAllWidgets(context, appWidgetManager, allWidgetIds)
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
                    
                    // Format passcode spacing
                    val formatted = when {
                        passcode.length == 6 -> passcode.substring(0, 3) + " " + passcode.substring(3, 6)
                        passcode.length == 8 -> passcode.substring(0, 4) + " " + passcode.substring(4, 8)
                        passcode.length == 10 -> passcode.substring(0, 5) + " " + passcode.substring(5, 10)
                        else -> passcode
                    }
                    views.setTextViewText(R.id.widget_token_code, formatted)
                    
                    val timeLabel = if (isTimeBased) "Valid for ${remainingSeconds}s" else "Counter-based code"
                    views.setTextViewText(R.id.widget_token_time, timeLabel)
                } else {
                    views.setTextViewText(R.id.widget_token_name, "EasyToken")
                    views.setTextViewText(R.id.widget_token_serial, "No keys registered")
                    views.setTextViewText(R.id.widget_token_code, "------")
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
