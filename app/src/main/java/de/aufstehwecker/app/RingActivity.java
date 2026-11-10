package de.aufstehwecker.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.Locale;

public class RingActivity extends Activity {

    private FrameLayout root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int needed;
    private String current = "";
    private boolean pasteBlocked;
    private boolean inPhotoMode;
    private boolean done;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (!Prefs.get(this).getBoolean("ringing", false)) {
            finish();
            return;
        }
        needed = Prefs.get(this).getBoolean("testMode", false) ? 3 : 30;
        clearClipboard();

        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);
        showChoice();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!done && !Prefs.get(this).getBoolean("ringing", false)) finish();
        clearClipboard();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (inPhotoMode && !done) showChoice(); // Kamera freigeben
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // Zurück ist gesperrt – erst die Aufgabe lösen.
        if (inPhotoMode) showChoice();
    }

    private void clearClipboard() {
        try {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            if (cm == null) return;
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip();
            else cm.setPrimaryClip(ClipData.newPlainText("", ""));
        } catch (Exception ignored) {}
    }

    private int progress() { return Prefs.get(this).getInt("progress", 0); }

    private void setScreen(View content) {
        root.removeAllViews();
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(content);
        root.addView(sv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private LinearLayout page() {
        LinearLayout l = Ui.column(this);
        int p = Ui.dp(this, 20);
        l.setPadding(p, Ui.dp(this, 40), p, p);
        return l;
    }

    // ---------- Auswahl ----------

    private void showChoice() {
        inPhotoMode = false;
        LinearLayout l = page();
        l.setGravity(Gravity.CENTER);

        Calendar now = Calendar.getInstance();
        TextView clock = Ui.text(this, String.format(Locale.GERMAN, "%02d:%02d",
                now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)), 72, true, Ui.TEXT);
        clock.setGravity(Gravity.CENTER);
        l.addView(clock);

        TextView title = Ui.text(this, "Aufstehen! ☀️", 28, true, Ui.ACCENT);
        title.setGravity(Gravity.CENTER);
        l.addView(title);

        TextView sub = Ui.text(this, "Der Wecker hört erst auf, wenn du eine Aufgabe erledigt hast.",
                16, false, Ui.MUTED);
        sub.setGravity(Gravity.CENTER);
        l.addView(sub, Ui.wide(this, 8));

        boolean cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (Prefs.hasReference(this) && cam) {
            l.addView(Ui.button(this, "📷  Foto vom Referenzort machen", Ui.ACCENT, v -> showPhoto()),
                    Ui.wide(this, 40));
        } else {
            TextView note = Ui.text(this, Prefs.hasReference(this)
                    ? "Foto-Modus nicht möglich: Kamera-Berechtigung fehlt."
                    : "Foto-Modus nicht möglich: Es ist noch kein Referenzfoto gespeichert.",
                    14, false, Ui.MUTED);
            note.setGravity(Gravity.CENTER);
            l.addView(note, Ui.wide(this, 40));
        }

        int p = progress();
        String label = "✍️  " + needed + " Sätze abschreiben" + (p > 0 ? "  (" + p + " erledigt)" : "");
        l.addView(Ui.button(this, label, 0xFF3A4654, v -> showSentences()), Ui.wide(this, 14));

        setScreen(l);
    }

    // ---------- Foto ----------

    private void showPhoto() {
        inPhotoMode = true;
        LinearLayout l = page();
        l.setPadding(Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 16));

        l.addView(Ui.text(this, "Fotografiere denselben Ort wie auf dem Referenzfoto:", 17, true, Ui.TEXT));

        Bitmap ref = BitmapFactory.decodeFile(Prefs.referenceFile(this).getAbsolutePath());
        if (ref == null) {
            Toast.makeText(this, "Referenzfoto fehlt.", Toast.LENGTH_LONG).show();
            showChoice();
            return;
        }
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(ref);
        iv.setAdjustViewBounds(true);
        LinearLayout.LayoutParams ivp = new LinearLayout.LayoutParams(Ui.dp(this, 110), Ui.dp(this, 146));
        ivp.topMargin = Ui.dp(this, 8);
        l.addView(iv, ivp);

        CameraView cam = new CameraView(this);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 420));
        cp.topMargin = Ui.dp(this, 10);
        l.addView(cam, cp);

        TextView result = Ui.text(this, "", 16, true, Ui.TEXT);
        result.setGravity(Gravity.CENTER);
        l.addView(result, Ui.wide(this, 8));

        l.addView(Ui.button(this, "📸  Auslösen", Ui.ACCENT, v -> cam.capture(bmp -> {
            if (ImageMatcher.contrast(bmp) < 0.035f) {
                result.setText("Zu dunkel oder abgedeckt – mach Licht an!");
                return;
            }
            float s = ImageMatcher.similarity(bmp, ref);
            float need = Prefs.threshold(this);
            if (s >= need) {
                dismissAlarm();
            } else {
                result.setText(String.format(Locale.GERMAN,
                        "Passt nicht (%d %%, nötig %d %%). Geh näher ran und versuch's nochmal.",
                        Math.round(s * 100), Math.round(need * 100)));
            }
        })), Ui.wide(this, 8));

        l.addView(Ui.button(this, "Zurück", 0xFF3A4654, v -> showChoice()), Ui.wide(this, 10));
        setScreen(l);
    }

    // ---------- Sätze ----------

    private void showSentences() {
        inPhotoMode = false;
        LinearLayout l = page();

        TextView counter = Ui.text(this, "", 16, true, Ui.ACCENT);
        l.addView(counter);

        TextView sentence = Ui.text(this, "", 22, true, Ui.TEXT);
        sentence.setTextIsSelectable(false);
        sentence.setLongClickable(false);
        LinearLayout.LayoutParams sp = Ui.wide(this, 16);
        l.addView(sentence, sp);

        NoPasteEditText input = new NoPasteEditText(this);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED);
        input.setHint("Hier abtippen …");
        input.setTextSize(18);
        input.setMinLines(2);
        l.addView(input, Ui.wide(this, 16));

        TextView hint = Ui.text(this,
                "Groß-/Kleinschreibung ist egal, ae/oe/ue/ss statt Umlauten geht auch. Einfügen ist gesperrt.",
                13, false, Ui.MUTED);
        l.addView(hint, Ui.wide(this, 8));

        l.addView(Ui.button(this, "Zurück", 0xFF3A4654, v -> showChoice()), Ui.wide(this, 24));

        Runnable next = () -> {
            current = Sentences.random(current);
            sentence.setText(current);
            counter.setText("Satz " + (progress() + 1) + " von " + needed);
        };
        next.run();

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (count - before > NoPasteEditText.MAX_CHUNK) pasteBlocked = true;
            }

            @Override public void afterTextChanged(Editable s) {
                if (pasteBlocked) {
                    pasteBlocked = false;
                    handler.post(() -> input.setText(""));
                    Toast.makeText(RingActivity.this, "Einfügen ist gesperrt – selbst tippen!", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (Sentences.normalize(s.toString()).equals(Sentences.normalize(current))) {
                    int p = progress() + 1;
                    Prefs.get(RingActivity.this).edit().putInt("progress", p).apply();
                    if (p >= needed) {
                        handler.post(RingActivity.this::dismissAlarm);
                    } else {
                        handler.post(() -> {
                            input.setText("");
                            next.run();
                        });
                    }
                }
            }
        });

        setScreen(l);
        input.requestFocus();
    }

    // ---------- Fertig ----------

    private void dismissAlarm() {
        if (done) return;
        done = true;
        inPhotoMode = false;
        AlarmService.stop(this);

        LinearLayout l = page();
        l.setGravity(Gravity.CENTER);
        TextView t = Ui.text(this, "Guten Morgen! ☀️", 32, true, Ui.ACCENT);
        t.setGravity(Gravity.CENTER);
        l.addView(t);
        TextView s = Ui.text(this, "Wecker ist aus. Hab einen guten Tag!", 17, false, Ui.TEXT);
        s.setGravity(Gravity.CENTER);
        l.addView(s, Ui.wide(this, 10));
        setScreen(l);
        handler.postDelayed(this::finish, 2500);
    }
}
