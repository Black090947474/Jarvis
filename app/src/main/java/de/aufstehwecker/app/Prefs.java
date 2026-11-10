package de.aufstehwecker.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

final class Prefs {
    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("wecker", Context.MODE_PRIVATE);
    }

    static int hour(Context c) { return get(c).getInt("hour", 7); }
    static int minute(Context c) { return get(c).getInt("minute", 0); }
    static boolean enabled(Context c) { return get(c).getBoolean("enabled", false); }

    /** Bitmaske Mo=1, Di=2, Mi=4, Do=8, Fr=16, Sa=32, So=64. 0 = einmalig. */
    static int days(Context c) { return get(c).getInt("days", 31); }

    /** 0 = leicht, 1 = mittel, 2 = streng */
    static int strictness(Context c) { return get(c).getInt("strict", 1); }

    static float threshold(Context c) {
        switch (strictness(c)) {
            case 0: return 0.45f;
            case 2: return 0.65f;
            default: return 0.55f;
        }
    }

    static File referenceFile(Context c) {
        return new File(c.getFilesDir(), "reference.jpg");
    }

    static boolean hasReference(Context c) {
        return referenceFile(c).exists();
    }
}
