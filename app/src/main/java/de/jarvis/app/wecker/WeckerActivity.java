package de.jarvis.app.wecker;

import de.jarvis.app.R;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class WeckerActivity extends Activity {

    private TextView status;
    private ImageView refImage;
    private TextView refNote;
    private LinearLayout permBox;
    private Switch enabledSwitch;
    private final TextView[] dayChips = new TextView[7];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        askRuntimePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshReference();
        refreshPermissions();
    }

    private void askRuntimePermissions() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] results) {
        refreshPermissions();
        refreshReference();
    }

    // ---------------- UI ----------------

    private void buildUi() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        l.setPadding(p, Ui.dp(this, 28), p, Ui.dp(this, 40));

        l.addView(Ui.text(this, "⏰ Aufsteh-Wecker", 28, true, Ui.TEXT));
        TextView sub = Ui.text(this, "Ausschalten nur per Foto oder 30 abgetippten Sätzen.", 15, false, Ui.MUTED);
        l.addView(sub, Ui.wide(this, 4));

        // ---- Weckzeit ----
        LinearLayout timeCard = Ui.card(this);
        timeCard.addView(Ui.text(this, "Weckzeit", 18, true, Ui.TEXT));

        TimePicker picker = new TimePicker(this);
        picker.setIs24HourView(true);
        picker.setHour(Prefs.hour(this));
        picker.setMinute(Prefs.minute(this));
        picker.setOnTimeChangedListener((v, h, m) -> {
            Prefs.get(this).edit().putInt("hour", h).putInt("minute", m).apply();
            reschedule();
        });
        timeCard.addView(picker, Ui.wide(this, 4));

        timeCard.addView(Ui.text(this, "Tage (keiner gewählt = nur einmal)", 14, false, Ui.MUTED), Ui.wide(this, 8));
        LinearLayout days = new LinearLayout(this);
        days.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"};
        for (int i = 0; i < 7; i++) {
            final int idx = i;
            TextView chip = Ui.text(this, names[i], 15, true, Ui.TEXT);
            chip.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f);
            lp.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
            chip.setOnClickListener(v -> {
                int d = Prefs.days(this) ^ (1 << idx);
                Prefs.get(this).edit().putInt("days", d).apply();
                paintChips();
                reschedule();
            });
            dayChips[i] = chip;
            days.addView(chip, lp);
        }
        paintChips();
        timeCard.addView(days, Ui.wide(this, 6));

        enabledSwitch = new Switch(this);
        enabledSwitch.setText("Wecker aktiv");
        enabledSwitch.setTextColor(Ui.TEXT);
        enabledSwitch.setTextSize(18);
        enabledSwitch.setChecked(Prefs.enabled(this));
        enabledSwitch.setOnCheckedChangeListener((v, on) -> {
            Prefs.get(this).edit().putBoolean("enabled", on).apply();
            reschedule();
        });
        timeCard.addView(enabledSwitch, Ui.wide(this, 14));

        status = Ui.text(this, "", 15, false, Ui.ACCENT);
        timeCard.addView(status, Ui.wide(this, 8));
        l.addView(timeCard, Ui.wide(this, 18));

        // ---- Referenzfoto ----
        LinearLayout refCard = Ui.card(this);
        refCard.addView(Ui.text(this, "Referenzfoto", 18, true, Ui.TEXT));
        refNote = Ui.text(this, "", 14, false, Ui.MUTED);
        refCard.addView(refNote, Ui.wide(this, 4));
        refImage = new ImageView(this);
        refImage.setAdjustViewBounds(true);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 200));
        ip.topMargin = Ui.dp(this, 8);
        ip.gravity = Gravity.CENTER_HORIZONTAL;
        refCard.addView(refImage, ip);

        refCard.addView(Ui.button(this, "📷  Referenzfoto aufnehmen", Ui.ACCENT,
                v -> startActivity(new Intent(this, ReferenceActivity.class))), Ui.wide(this, 10));
        refCard.addView(Ui.button(this, "🔍  Foto-Erkennung testen", 0xFF3A4654, v -> {
            if (!Prefs.hasReference(this)) {
                Toast.makeText(this, "Erst ein Referenzfoto aufnehmen.", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, ReferenceActivity.class).putExtra("test", true));
        }), Ui.wide(this, 8));

        refCard.addView(Ui.text(this, "Wie genau muss das Foto passen?", 14, false, Ui.MUTED), Ui.wide(this, 12));
        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        String[] levels = {"Locker", "Mittel", "Streng"};
        for (int i = 0; i < 3; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(1000 + i);
            rb.setText(levels[i]);
            rb.setTextColor(Ui.TEXT);
            rg.addView(rb, new RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        rg.check(1000 + Prefs.strictness(this));
        rg.setOnCheckedChangeListener((g, id) -> Prefs.get(this).edit().putInt("strict", id - 1000).apply());
        refCard.addView(rg, Ui.wide(this, 4));
        l.addView(refCard, Ui.wide(this, 16));

        // ---- Berechtigungen ----
        permBox = Ui.card(this);
        l.addView(permBox, Ui.wide(this, 16));

        // ---- Test ----
        l.addView(Ui.button(this, "🧪  Test: Alarm in 10 Sekunden", 0xFF2E7D5B, v -> {
            AlarmScheduler.scheduleTest(this, 10_000);
            Toast.makeText(this, "Jetzt Handy sperren! Im Test reichen 3 Sätze.", Toast.LENGTH_LONG).show();
        }), Ui.wide(this, 16));

        TextView tips = Ui.text(this,
                "Tipps:\n• Wähle als Referenz eine Stelle mit vielen Details (z. B. offener Kühlschrank), nicht eine weiße Wand.\n"
                        + "• Teste die Foto-Erkennung einmal bei Morgenlicht.\n"
                        + "• Lass die App nach dem Einrichten im Hintergrund – nicht über die Einstellungen beenden.",
                14, false, Ui.MUTED);
        l.addView(tips, Ui.wide(this, 16));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.addView(l);
        setContentView(sv);
    }

    private void paintChips() {
        int d = Prefs.days(this);
        for (int i = 0; i < 7; i++) {
            boolean on = (d & (1 << i)) != 0;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(this, 21));
            bg.setColor(on ? Ui.ACCENT : 0xFF2A333D);
            dayChips[i].setBackground(bg);
            dayChips[i].setTextColor(on ? 0xFFFFFFFF : Ui.MUTED);
        }
    }

    private void reschedule() {
        boolean exact = AlarmScheduler.schedule(this);
        if (!exact) {
            Toast.makeText(this, "Exakte Alarme sind nicht erlaubt – bitte unten freigeben.", Toast.LENGTH_LONG).show();
        }
        refreshStatus();
        refreshPermissions();
    }

    private void refreshStatus() {
        if (status == null) return;
        enabledSwitch.setChecked(Prefs.enabled(this));
        status.setText(AlarmScheduler.describeNext(this));
    }

    private void refreshReference() {
        Bitmap bmp = Prefs.hasReference(this)
                ? BitmapFactory.decodeFile(Prefs.referenceFile(this).getAbsolutePath()) : null;
        if (bmp != null) {
            refImage.setImageBitmap(bmp);
            refImage.setVisibility(ImageView.VISIBLE);
            refNote.setText("Morgens musst du diesen Ort fotografieren, um den Wecker auszuschalten.");
        } else {
            refImage.setVisibility(ImageView.GONE);
            refNote.setText("Noch kein Foto. Ohne Referenzfoto geht nur das Sätze-Abschreiben.");
        }
    }

    // ---------------- Berechtigungen ----------------

    private void refreshPermissions() {
        if (permBox == null) return;
        permBox.removeAllViews();
        permBox.addView(Ui.text(this, "Einrichtung", 18, true, Ui.TEXT));
        int missing = 0;

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing++;
            addPermButton("🔔  Benachrichtigungen erlauben", () ->
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2));
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing++;
            addPermButton("📷  Kamera erlauben", () ->
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, 3));
        }
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (!nm.canUseFullScreenIntent()) {
                missing++;
                addPermButton("📱  Vollbild-Wecker auf Sperrbildschirm erlauben", () -> openSettings(
                        new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri())));
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager am = getSystemService(AlarmManager.class);
            if (!am.canScheduleExactAlarms()) {
                missing++;
                addPermButton("⏱️  Exakte Alarme erlauben", () -> openSettings(
                        new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri())));
            }
        }
        PowerManager pm = getSystemService(PowerManager.class);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
            missing++;
            addPermButton("🔋  Akku-Optimierung ausschalten (empfohlen)", () -> openSettings(
                    new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri())));
        }

        if (missing == 0) {
            permBox.addView(Ui.text(this, "✅ Alles eingerichtet.", 15, false, Ui.TEXT), Ui.wide(this, 6));
        } else {
            permBox.addView(Ui.text(this, "Damit der Wecker auch bei gesperrtem Handy zuverlässig klingelt:",
                    14, false, Ui.MUTED), 1, Ui.wide(this, 4));
        }
    }

    private Uri pkgUri() { return Uri.parse("package:" + getPackageName()); }

    private void openSettings(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri()));
            } catch (Exception ignored) {}
        }
    }

    private void addPermButton(String label, Runnable r) {
        permBox.addView(Ui.button(this, label, 0xFF3A4654, v -> r.run()), Ui.wide(this, 8));
    }
}
