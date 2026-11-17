package com.threethan.launcher.activity.dialog;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.threethan.launcher.activity.LauncherActivity;
import com.threethan.launcher.helper.LaunchDeckAdb;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Launch deck "Tools" section.
 *  - showGate(): Wireless debugging pairing screen, shown before the app list.
 *  - show(): Tools screen with refresh rate buttons (75 / 90 / 120 / 200 Hz).
 */
public class ToolsDialog {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static boolean gateSkipped = false;
    private static boolean gateShowing = false;
    private static boolean reconnectTried = false;

    private final LauncherActivity a;

    public ToolsDialog(LauncherActivity activity) { this.a = activity; }

    private int dp(int v) { return (int) (v * a.getResources().getDisplayMetrics().density); }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(20), dp(20), dp(20));
        return l;
    }
    private TextView text(String s, float size, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(size);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }
    private EditText number(String hint, int prefill) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setSingleLine(true);
        if (prefill > 0) e.setText(String.valueOf(prefill));
        return e;
    }
    private Button button(String s) {
        Button b = new Button(a);
        b.setText(s);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }
    private AlertDialog wrap(LinearLayout content) {
        ScrollView sv = new ScrollView(a);
        sv.addView(content);
        AlertDialog d = new AlertDialog.Builder(a).setView(sv).create();
        return d;
    }

    // ------------------------------------------------------------------ Gate

    /** Call from onResume. Shows the pairing screen unless already connected or skipped. */
    public static void gateIfNeeded(LauncherActivity activity) {
        if (gateSkipped || gateShowing) return;
        IO.execute(() -> {
            boolean ok = false;
            try {
                LaunchDeckAdb adb = LaunchDeckAdb.get(activity);
                ok = adb.isConnected() || (!reconnectTried && adb.tryReconnect());
            } catch (Exception ignored) {}
            reconnectTried = true;
            if (!ok) activity.runOnUiThread(() -> {
                if (!gateShowing && !gateSkipped && !activity.isFinishing())
                    new ToolsDialog(activity).showGate();
            });
        });
    }

    public AlertDialog showGate() {
        gateShowing = true;
        LinearLayout c = column();
        c.addView(text("Connect Wireless debugging", 22, true));
        c.addView(text("1. Headset Settings → System → Developer → turn on Wireless debugging.\n"
                + "2. Open it, tap \"Pair device with pairing code\".\n"
                + "3. Type the pairing code and the pairing port shown there.\n"
                + "4. Type the connect port from the main Wireless debugging screen (the number after the IP address).", 14, false));
        EditText pairPort = number("Pairing port", 0);
        EditText code = number("Pairing code (6 digits)", 0);
        int saved = 0;
        try { saved = LaunchDeckAdb.get(a).getSavedConnectPort(); } catch (Exception ignored) {}
        EditText connectPort = number("Connect port", saved);
        c.addView(pairPort); c.addView(code); c.addView(connectPort);
        TextView status = text("", 14, false);
        status.setGravity(Gravity.CENTER);
        c.addView(status);
        Button go = button("Pair & connect");
        Button reconnect = button("Already paired – just connect");
        Button skip = button("Skip for now");
        c.addView(go); c.addView(reconnect); c.addView(skip);

        AlertDialog d = wrap(c);
        d.setCancelable(false);
        d.setCanceledOnTouchOutside(false);

        go.setOnClickListener(v -> {
            String pp = pairPort.getText().toString().trim();
            String cd = code.getText().toString().trim();
            String cp = connectPort.getText().toString().trim();
            if (pp.isEmpty() || cd.isEmpty() || cp.isEmpty()) { status.setText("Fill in all three boxes."); return; }
            status.setText("Pairing…");
            go.setEnabled(false);
            IO.execute(() -> {
                try {
                    LaunchDeckAdb.get(a).pairAndConnect(Integer.parseInt(pp), cd, Integer.parseInt(cp));
                    a.runOnUiThread(() -> { gateShowing = false; d.dismiss(); });
                } catch (Exception e) {
                    a.runOnUiThread(() -> { go.setEnabled(true); status.setText("Failed: " + e.getMessage()); });
                }
            });
        });
        reconnect.setOnClickListener(v -> {
            String cp = connectPort.getText().toString().trim();
            if (cp.isEmpty()) { status.setText("Type the connect port."); return; }
            status.setText("Connecting…");
            IO.execute(() -> {
                try {
                    LaunchDeckAdb.get(a).connectOnly(Integer.parseInt(cp));
                    a.runOnUiThread(() -> { gateShowing = false; d.dismiss(); });
                } catch (Exception e) {
                    a.runOnUiThread(() -> status.setText("Failed: " + e.getMessage()));
                }
            });
        });
        skip.setOnClickListener(v -> { gateSkipped = true; gateShowing = false; d.dismiss(); });
        d.setOnDismissListener(x -> gateShowing = false);
        d.show();
        return d;
    }

    // ----------------------------------------------------------------- Tools

    public AlertDialog show() {
        LinearLayout c = column();
        c.addView(text("Tools", 22, true));
        c.addView(text("Refresh rate", 16, true));
        TextView status = text("", 14, false);
        status.setGravity(Gravity.CENTER);
        int[] rates = {75, 90, 120, 200};
        for (int hz : rates) {
            Button b = button(hz + " Hz");
            b.setOnClickListener(v -> IO.execute(() -> {
                try {
                    LaunchDeckAdb adb = LaunchDeckAdb.get(a);
                    if (!adb.isConnected() && !adb.tryReconnect()) {
                        a.runOnUiThread(() -> status.setText("Not connected – use \"Connect Wireless debugging\" below."));
                        return;
                    }
                    adb.setRefreshRate(hz);
                    a.runOnUiThread(() -> status.setText("Requested " + hz + " Hz"));
                } catch (Exception e) {
                    a.runOnUiThread(() -> status.setText("Failed: " + e.getMessage()));
                }
            }));
            c.addView(b);
        }
        c.addView(status);
        Button wd = button("Connect Wireless debugging");
        c.addView(wd);
        Button close = button("Close");
        c.addView(close);
        AlertDialog d = wrap(c);
        wd.setOnClickListener(v -> { d.dismiss(); gateSkipped = false; showGate(); });
        close.setOnClickListener(v -> d.dismiss());
        d.show();
        return d;
    }
}
