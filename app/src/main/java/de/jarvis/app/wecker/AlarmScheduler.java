package de.jarvis.app.wecker;

import de.jarvis.app.R;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;
import java.util.Locale;

final class AlarmScheduler {
    private AlarmScheduler() {}

    private static final int RC_MAIN = 1;
    private static final int RC_TEST = 3;

    /** true, wenn der Alarm exakt gestellt werden konnte. */
    static boolean schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = alarmIntent(c, RC_MAIN, false);
        am.cancel(pi);
        if (!Prefs.enabled(c)) return true;
        long t = nextTrigger(c);
        return setExact(c, am, t, pi);
    }

    static boolean scheduleTest(Context c, long delayMs) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        return setExact(c, am, System.currentTimeMillis() + delayMs, alarmIntent(c, RC_TEST, true));
    }

    private static boolean setExact(Context c, AlarmManager am, long t, PendingIntent pi) {
        PendingIntent show = PendingIntent.getActivity(c, 2, new Intent(c, WeckerActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) throw new SecurityException();
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(t, show), pi);
            return true;
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
            return false;
        }
    }

    private static PendingIntent alarmIntent(Context c, int rc, boolean test) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.putExtra("test", test);
        return PendingIntent.getBroadcast(c, rc, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static int dayIndex(Calendar cal) {
        // Montag = 0 ... Sonntag = 6
        return (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    static long nextTrigger(Context c) {
        int days = Prefs.days(c);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, Prefs.hour(c));
        cal.set(Calendar.MINUTE, Prefs.minute(c));
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long now = System.currentTimeMillis();
        if (cal.getTimeInMillis() <= now + 1000) cal.add(Calendar.DAY_OF_YEAR, 1);
        if (days != 0) {
            for (int i = 0; i < 8; i++) {
                if ((days & (1 << dayIndex(cal))) != 0) break;
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }
        }
        return cal.getTimeInMillis();
    }

    static String describeNext(Context c) {
        if (!Prefs.enabled(c)) return "Wecker ist aus.";
        long t = nextTrigger(c);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(t);
        String[] names = {"Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"};
        long diffMin = (t - System.currentTimeMillis()) / 60000 + 1;
        return String.format(Locale.GERMAN, "Nächster Alarm: %s, %02d.%02d. um %02d:%02d\n(in %d Std. %d Min.)",
                names[dayIndex(cal)], cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), diffMin / 60, diffMin % 60);
    }
}
