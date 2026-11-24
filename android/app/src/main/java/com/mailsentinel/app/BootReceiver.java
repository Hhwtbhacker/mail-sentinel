package com.mailsentinel.app;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Api.prefs(context).getString("token", "").isEmpty()) {
            PollReceiver.schedule(context);
        }
    }
}
