package de.jarvis.app.wecker;

import de.jarvis.app.R;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class WeckerBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        AlarmScheduler.schedule(c);
    }
}
