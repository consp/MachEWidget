package com.example.khughes.machewidget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.format.DateUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class StatusReceiver extends BroadcastReceiver {
    private Context mContext;
    private StoredData appInfo;

    @Override
    public void onReceive(Context context, Intent arg1) {
        final int Millis = 1000;

        mContext = context;
        nextAlarm(mContext);

        String VIN = PreferenceManager.getDefaultSharedPreferences(mContext).getString(mContext.getResources().getString(R.string.VIN_key), "");
        appInfo = new StoredData(context);
        long timeout = appInfo.getTokenTimeout(VIN);
        LocalDateTime time = LocalDateTime.now(ZoneId.systemDefault());
        long nowtime = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        ProgramStateMachine.States state = new ProgramStateMachine(appInfo.getProgramState(VIN)).getCurrentState();

        Log.d(MainActivity.CHANNEL_ID, "time is " + (timeout - nowtime) / Millis + ", state is " + state.name());

        if (state.equals(ProgramStateMachine.States.HAVE_TOKEN_AND_STATUS)) {
            SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
            int delayInMillis = new Integer(sharedPref.getString(context.getResources().getString(R.string.update_frequency_key), "10")) * 60 * Millis;

            // Since actions such as "Refresh" don't check the token's expiration, be sure to refresh if it would expire before
            // the next update.
            long calculation = (timeout - delayInMillis - 5 * Millis) - nowtime;
            Log.d(MainActivity.CHANNEL_ID, "Calculating time as " + calculation);
            if (timeout - delayInMillis - 5 * Millis < nowtime) {
                Log.d(MainActivity.CHANNEL_ID, "Need to refresh token");
                getRefresh(appInfo.getRefreshToken(VIN));
            } else {
                Log.d(MainActivity.CHANNEL_ID, "Token good? Just grab info");
                getStatus(appInfo.getAccessToken(VIN));
                getOTAStatus(appInfo.getAccessToken(VIN));
            }
            appInfo.incCounter(StoredData.GOOD);
        } else if (state.equals(ProgramStateMachine.States.ATTEMPT_TO_GET_VIN_AGAIN) ||
                state.equals(ProgramStateMachine.States.ATTEMPT_TO_GET_ACCESS_TOKEN)) {
            getRefresh(appInfo.getRefreshToken(VIN));
            appInfo.incCounter(StoredData.BAD);
        } else {
            Log.d(MainActivity.CHANNEL_ID, "Hmmm... don't know what to do here");
            getRefresh(appInfo.getRefreshToken(VIN));
            appInfo.incCounter(StoredData.UGLY);
        }
        int good = appInfo.getCounter(StoredData.GOOD);
        int bad = appInfo.getCounter(StoredData.BAD);
        int ugly = appInfo.getCounter(StoredData.UGLY);

        Log.d(MainActivity.CHANNEL_ID, "good = " + good + ", bad = " + bad + ",ugly = " + ugly);
    }

    private Bundle bb = new Bundle();

    private void getRefresh(String refreshToken) {
        Handler h = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                String VIN = PreferenceManager.getDefaultSharedPreferences(mContext).getString(mContext.getResources().getString(R.string.VIN_key), "");
                bb = msg.getData();
                ProgramStateMachine.States action = ProgramStateMachine.States.valueOf(bb.getString("action"));
                Log.d(MainActivity.CHANNEL_ID, "Refresh: " + action);
                appInfo.setProgramState(VIN, action);
                if (action.equals(ProgramStateMachine.States.ATTEMPT_TO_GET_VEHICLE_STATUS) || action.equals(ProgramStateMachine.States.HAVE_TOKEN_AND_STATUS)) {
                    String accessToken = bb.getString("access_token");
                    String refreshToken = bb.getString("refresh_token");
                    int expires = bb.getInt("expires", 0);
                    LocalDateTime time = LocalDateTime.now(ZoneId.systemDefault()).plusSeconds(expires);
                    long nextTime = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    appInfo.setTokenInfo(VIN, accessToken, refreshToken, nextTime);
                    getStatus(accessToken);
                    getOTAStatus(accessToken);
                }
            }
        };
        NetworkCalls.refreshAccessToken(h, mContext, refreshToken);
    }

    private void getStatus(String accessToken) {
        String VIN = PreferenceManager.getDefaultSharedPreferences(mContext).getString(mContext.getResources().getString(R.string.VIN_key), "");
        Handler h = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                bb = msg.getData();
                ProgramStateMachine.States action = ProgramStateMachine.States.valueOf(bb.getString("action"));
                Log.d(MainActivity.CHANNEL_ID, "Status: " + action);
                appInfo.setProgramState(VIN, action);
                if (action.equals(ProgramStateMachine.States.HAVE_TOKEN_AND_STATUS)) {
                    MainActivity.updateWidget(mContext);
                }
            }
        };
        NetworkCalls.getStatus(h, mContext, accessToken);
    }

    private void getOTAStatus(String accessToken) {
        Handler h = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                MainActivity.updateWidget(mContext);
            }
        };
        NetworkCalls.getOTAStatus(h, mContext, accessToken);
    }

    public static void nextAlarm(Context context) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        int delay = new Integer(sharedPref.getString(context.getResources().getString(R.string.update_frequency_key), "10"));
        if (delay != 0) {
            nextAlarm(context, delay * 60);
        }
    }

    public static void nextAlarm(Context context, int delay) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        LocalDateTime time = LocalDateTime.now(ZoneId.systemDefault()).plusSeconds(delay);
        String timeText = time.format(DateTimeFormatter.ofPattern("MM/dd HH:mm:ss", Locale.US));
        Log.i(MainActivity.CHANNEL_ID, "Next AlarmReceiver at " + timeText);
        long nextTime = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        Intent intent = new Intent(context, StatusReceiver.class);
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        final AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarmManager.set(AlarmManager.RTC_WAKEUP, nextTime, pendingIntent);
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, nextTime, DateUtils.SECOND_IN_MILLIS, pendingIntent);
    }

    public static void cancelAlarm(Context context) {
        Intent intent = new Intent(context, StatusReceiver.class);
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        final AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarmManager.cancel(pendingIntent);
    }
}
