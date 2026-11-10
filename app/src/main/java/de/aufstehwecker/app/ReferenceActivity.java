package de.aufstehwecker.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.util.Locale;

/** Referenzfoto aufnehmen – oder (test=true) ausprobieren, ob ein Foto erkannt würde. */
public class ReferenceActivity extends Activity {

    private boolean testMode;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        testMode = getIntent().getBooleanExtra("test", false);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 5);
            return;
        }
        build();
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) build();
        else {
            Toast.makeText(this, "Ohne Kamera-Berechtigung geht das nicht.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void build() {
        LinearLayout l = Ui.column(this);
        l.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 16);
        l.setPadding(p, Ui.dp(this, 24), p, p);

        String head = testMode
                ? "Foto testen: Mach ein Foto wie morgens beim Weckerklingeln."
                : "Referenzfoto: Fotografiere einen Ort, an den du morgens gehen musst (z. B. Kühlschrank-Inneres, Bad-Waschbecken). Achte auf viele Details und gutes Licht.";
        l.addView(Ui.text(this, head, 16, true, Ui.TEXT));

        CameraView cam = new CameraView(this);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 460));
        cp.topMargin = Ui.dp(this, 12);
        l.addView(cam, cp);

        TextView result = Ui.text(this, "", 16, true, Ui.TEXT);
        result.setGravity(Gravity.CENTER);
        l.addView(result, Ui.wide(this, 8));

        l.addView(Ui.button(this, testMode ? "📸  Testfoto machen" : "📸  Als Referenz speichern", Ui.ACCENT,
                v -> cam.capture(bmp -> onPicture(bmp, result))), Ui.wide(this, 8));
        l.addView(Ui.button(this, "Schließen", 0xFF3A4654, v -> finish()), Ui.wide(this, 10));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.addView(l);
        setContentView(sv);
    }

    private void onPicture(Bitmap bmp, TextView result) {
        float contrast = ImageMatcher.contrast(bmp);
        if (contrast < 0.035f) {
            result.setText("Zu dunkel oder zu einfarbig – such dir eine Stelle mit mehr Details.");
            return;
        }
        if (testMode) {
            Bitmap ref = BitmapFactory.decodeFile(Prefs.referenceFile(this).getAbsolutePath());
            if (ref == null) {
                result.setText("Noch kein Referenzfoto gespeichert.");
                return;
            }
            float s = ImageMatcher.similarity(bmp, ref);
            float need = Prefs.threshold(this);
            result.setText(String.format(Locale.GERMAN, "Ähnlichkeit: %d %% (nötig: %d %%)\n%s",
                    Math.round(s * 100), Math.round(need * 100),
                    s >= need ? "✅ Würde den Wecker ausschalten" : "❌ Würde nicht reichen"));
            return;
        }
        try {
            int max = Math.max(bmp.getWidth(), bmp.getHeight());
            Bitmap out = bmp;
            if (max > 800) {
                float f = 800f / max;
                out = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * f), Math.round(bmp.getHeight() * f), true);
            }
            try (FileOutputStream fos = new FileOutputStream(Prefs.referenceFile(this))) {
                out.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            }
            Toast.makeText(this, "Referenzfoto gespeichert ✅", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception e) {
            result.setText("Speichern fehlgeschlagen.");
        }
    }
}
