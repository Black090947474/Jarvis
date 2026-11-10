package de.jarvis.app.wecker;

import de.jarvis.app.R;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        boolean test = intent.getBooleanExtra("test", false);
        if (!test) {
            if (Prefs.days(c) == 0) {
                Prefs.get(c).edit().putBoolean("enabled", false).apply();
            }
            AlarmScheduler.schedule(c);
        }
        Intent s = new Intent(c, AlarmService.class);
        s.putExtra("test", test);
        c.startForegroundService(s);
    }
}
