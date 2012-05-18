/*
 * Copyright (C) 2012 Aubort Jean-Baptiste (Rorist)
 * Licensed under GNU's GPL 2, see README
 */

package ch.fixme.status;

import java.io.ByteArrayOutputStream;
import java.net.UnknownHostException;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

public class Widget extends AppWidgetProvider {

    // FIXME: Set interval in preferences
    private final static long UPDATE_INTERVAL = AlarmManager.INTERVAL_FIFTEEN_MINUTES;

    public void onReceive(Context ctxt, Intent intent) {
        String action = intent.getAction();
        if (AppWidgetManager.ACTION_APPWIDGET_DELETED.equals(action)) {
            // Remove widget alarm
            int widgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            PendingIntent pi = PendingIntent.getService(ctxt, widgetId,
                    getIntent(ctxt, widgetId), 0);
            AlarmManager am = (AlarmManager) ctxt
                    .getSystemService(Context.ALARM_SERVICE);
            am.cancel(pi);

            // remove preference
            SharedPreferences prefs = PreferenceManager
                    .getDefaultSharedPreferences(ctxt);
            Editor edit = prefs.edit();
            edit.remove(Main.PREF_API_URL_WIDGET + widgetId);
            edit.remove(Main.PREF_INIT_WIDGET + widgetId);
            edit.remove(Main.PREF_LAST_WIDGET + widgetId);
            edit.commit();

            Log.i(Main.TAG, "Remove widget alarm for id=" + widgetId);
        }
        super.onReceive(ctxt, intent);
    }

    public void onUpdate(Context ctxt, AppWidgetManager appWidgetManager,
            int[] appWidgetIds) {
        final int N = appWidgetIds.length;
        for (int i = 0; i < N; i++) {
            int widgetId = appWidgetIds[i];
            // Set initialize
            SharedPreferences prefs = PreferenceManager
                    .getDefaultSharedPreferences(ctxt);
            Editor edit = prefs.edit();
            edit.putBoolean(Main.PREF_INIT_WIDGET + widgetId, false);
            edit.commit();
            // Update timer
            Intent intent = getIntent(ctxt, widgetId);
            setAlarm(ctxt, intent, widgetId);
            Log.i(Main.TAG, "Update widget alarm for id=" + widgetId);
        }
        super.onUpdate(ctxt, appWidgetManager, appWidgetIds);
    }

    protected static Intent getIntent(Context ctxt, int widgetId) {
        Intent i = new Intent(ctxt, UpdateService.class);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        return i;
    }

    protected static void setAlarm(Context ctxt, Intent i, int widgetId) {
        setAlarm(ctxt, i, widgetId, 0);
    }

    protected static void setAlarm(Context ctxt, Intent i, int widgetId,
            int delay) {
        AlarmManager am = (AlarmManager) ctxt
                .getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getService(ctxt, widgetId, i, 0);
        am.cancel(pi);
        am.setRepeating(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + delay, UPDATE_INTERVAL, pi);
        Log.i(Main.TAG, "start notification every " + UPDATE_INTERVAL / 1000 + "s");
    }

    private static class GetImage extends AsyncTask<String, Void, byte[]> {

        private int mId;
        private Context mCtxt;
        private String mText;

        public GetImage(Context ctxt, int id, String text) {
            mCtxt = ctxt;
            mId = id;
            mText = text;
        }

        @Override
        protected byte[] doInBackground(String... url) {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            try {
                new Net(url[0], os);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return os.toByteArray();
        }

        @Override
        protected void onPostExecute(byte[] result) {
            AppWidgetManager manager = AppWidgetManager.getInstance(mCtxt);
            updateWidget(mCtxt, mId, manager,
                    BitmapFactory.decodeByteArray(result, 0, result.length),
                    mText);
        }

    }

    protected static void updateWidget(final Context ctxt, int widgetId,
            AppWidgetManager manager, Bitmap bitmap, String text) {
        RemoteViews views = new RemoteViews(ctxt.getPackageName(),
                R.layout.widget);
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.widget_image, bitmap);
        } else {
            views.setImageViewResource(R.id.widget_image,
                    android.R.drawable.ic_popup_sync);
        }
        if (text != null) {
            views.setTextViewText(R.id.widget_status, text);
            views.setViewVisibility(R.id.widget_status, View.VISIBLE);
        } else {
            views.setViewVisibility(R.id.widget_status, View.GONE);
        }
        Intent clickIntent = new Intent(ctxt, Main.class);
        clickIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        PendingIntent pendingIntent = PendingIntent.getActivity(ctxt, widgetId,
                clickIntent, PendingIntent.FLAG_CANCEL_CURRENT);
        views.setOnClickPendingIntent(R.id.widget_image, pendingIntent);
        manager.updateAppWidget(widgetId, views);
        Log.i(Main.TAG, "Update widget image for id=" + widgetId);
        // Is initialized
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctxt);
        Editor edit = prefs.edit();
        edit.putBoolean(Main.PREF_INIT_WIDGET + widgetId, true);
        edit.commit();
    }

    private static class GetApiTask extends AsyncTask<String, Void, String> {

        private int mId;
        private Context mCtxt;
        private boolean retry = false;

        public GetApiTask(Context ctxt, int id) {
            mCtxt = ctxt;
            mId = id;
        }

        @Override
        protected String doInBackground(String... url) {
            ByteArrayOutputStream spaceOs = new ByteArrayOutputStream();
            try {
                new Net(url[0], spaceOs);
            } catch (UnknownHostException e) {
                e.printStackTrace();
                cancel(true);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return spaceOs.toString();
        }

        @Override
        protected void onCancelled() {
            // Set alarm 5 seconds in the future
            Log.i(Main.TAG, "Set alarm in 5 seconds");
            Intent intent = getIntent(mCtxt, mId);
            setAlarm(mCtxt, intent, mId, 5000);
        }

        @Override
        protected void onPostExecute(String result) {
            try {
                JSONObject api = new JSONObject(result);
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mCtxt);
                boolean statusBool = api.getBoolean(Main.API_STATUS);
                // Update only if different than last status and not the first time
                if (!api.isNull(Main.API_LASTCHANGE)){
                    long last = api.getLong(Main.API_LASTCHANGE) * 1000;
                    if (prefs.getBoolean(Main.PREF_LAST_WIDGET + mId, false) == statusBool
                            && prefs.getBoolean(Main.PREF_INIT_WIDGET + mId, false)) {
                        Log.i(Main.TAG, "Nothing to update");
                        return;
                    }
                }
                // Mandatory fields
                String status = Main.API_ICON_CLOSED;
                if (statusBool) {
                    status = Main.API_ICON_OPEN;
                }
                Editor edit = prefs.edit();
                edit.putBoolean(Main.PREF_LAST_WIDGET + mId, statusBool);
                edit.commit();
                // Status icon or space icon
                if (!api.isNull(Main.API_ICON)) {
                    JSONObject status_icon = api.getJSONObject(Main.API_ICON);
                    if (!status_icon.isNull(status)) {
                        new GetImage(mCtxt, mId, null).execute(status_icon
                                .getString(status));
                    }
                } else {
                    String status_text = Main.CLOSED;
                    if (api.getBoolean(Main.API_STATUS)) {
                        status_text = Main.OPEN;
                    }
                    new GetImage(mCtxt, mId, status_text).execute(api
                            .getString(Main.API_LOGO));
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    public static class UpdateService extends IntentService {

        public UpdateService() {
            super("MyHackerspaceWidgetService");
        }

        @Override
        protected void onHandleIntent(Intent intent) {
            Log.i(Main.TAG, "UpdateService started");
            final Context ctxt = UpdateService.this;
            int widgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            SharedPreferences prefs = PreferenceManager
                    .getDefaultSharedPreferences(ctxt);
            if (prefs.contains(Main.PREF_API_URL_WIDGET + widgetId)) {
                new GetApiTask(ctxt, widgetId).execute(prefs
                        .getString(Main.PREF_API_URL_WIDGET + widgetId,
                                Main.API_DEFAULT));
            }
            stopSelf();
        }
    }

}
