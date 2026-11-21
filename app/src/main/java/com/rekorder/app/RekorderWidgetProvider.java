// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * Minimal pitch-black 1x1 Home Screen Record Widget.
 * Single tap toggles RecordingService directly without even opening the app.
 */
public final class RekorderWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateWidget(context, mgr, id);
        }
    }

    public static void requestUpdate(Context ctx) {
        if (ctx == null) return;
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            ComponentName cn = new ComponentName(ctx, RekorderWidgetProvider.class);
            int[] ids = mgr.getAppWidgetIds(cn);
            if (ids != null && ids.length > 0) {
                for (int id : ids) {
                    updateWidget(ctx, mgr, id);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void updateWidget(Context ctx, AppWidgetManager mgr, int widgetId) {
        boolean rec = RecordingService.isRecordingGlobally();
        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.widget_rekorder);
        views.setImageViewResource(R.id.widget_icon,
                rec ? R.drawable.ic_widget_stop : R.drawable.ic_launcher_foreground);
        views.setTextViewText(R.id.widget_label, rec ? "STOP" : "REC");
        views.setTextColor(R.id.widget_label, rec ? 0xFFFF2D2D : 0xFFA0A0A0);

        Intent svc = new Intent(ctx, RecordingService.class);
        svc.setAction(RecordingService.ACTION_WIDGET_TOGGLE);
        PendingIntent pi;
        if (Build.VERSION.SDK_INT >= 26) {
            pi = PendingIntent.getForegroundService(ctx, 2001, svc,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            pi = PendingIntent.getService(ctx, 2001, svc,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        views.setOnClickPendingIntent(R.id.widget_root, pi);
        mgr.updateAppWidget(widgetId, views);
    }
}
