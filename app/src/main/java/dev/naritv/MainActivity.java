package dev.naritv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int SHIZUKU_PERMISSION_REQ = 101;
    private IRouteService route;
    private TextView state;
    private TextView result;
    private EditText card;
    private Button probe;
    private Button switchGame;
    private Button restore;
    private Button auth;
    private boolean binding;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> runOnUiThread(this::updateShizuku);
    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> runOnUiThread(() -> {
        route = null;
        binding = false;
        status("Shizuku остановлен. Запусти Shizuku и попробуй ещё раз.");
        refreshButtons();
    });
    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode == SHIZUKU_PERMISSION_REQ) runOnUiThread(this::updateShizuku);
    };

    private final Shizuku.UserServiceArgs serviceArgs =
            new Shizuku.UserServiceArgs(new ComponentName("dev.naritv", "dev.naritv.RouteService"))
                    .daemon(false)
                    .processNameSuffix("route")
                    .version(1)
                    .tag("nari-route");
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            route = IRouteService.Stub.asInterface(binder);
            runOnUiThread(() -> {
                status("Shizuku подключён. " + safePing());
                refreshButtons();
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            route = null;
            binding = false;
            runOnUiThread(() -> {
                status("Служба маршрутизации отключена");
                refreshButtons();
            });
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setBackgroundColor(Color.rgb(20, 23, 30));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(42), dp(22), dp(42), dp(32));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = text("RAZER NARI / ANDROID TV", 30, Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);
        root.addView(text("Выбор USB-аудиовыхода Game вместо Chat · эксперимент", 16, 0xffb1b8c4));

        state = text("Проверяю Shizuku…", 17, 0xff9dd1ff);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(18);
        root.addView(state, sp);

        LinearLayout cardRow = new LinearLayout(this);
        cardRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text("ALSA card:", 18, Color.WHITE);
        cardRow.addView(label);
        card = new EditText(this);
        card.setInputType(InputType.TYPE_CLASS_NUMBER);
        card.setSingleLine(true);
        card.setText("4");
        card.setSelectAllOnFocus(true);
        card.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(90), dp(58));
        cp.leftMargin = dp(14);
        cardRow.addView(card, cp);
        root.addView(cardRow);

        auth = button("1 · Подключить Shizuku");
        auth.setOnClickListener(v -> updateShizuku());
        root.addView(auth);
        probe = button("2 · Проверить Game (без отключения Chat)");
        probe.setOnClickListener(v -> perform("probe"));
        root.addView(probe);
        switchGame = button("3 · Переключить весь звук на Game");
        switchGame.setOnClickListener(v -> perform("switch"));
        root.addView(switchGame);
        restore = button("Вернуть Chat / откатить");
        restore.setOnClickListener(v -> perform("restore"));
        root.addView(restore);

        result = text("Вывод появится здесь. Если пропал звук — переподключи USB-донгл.", 16, 0xffd9deea);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(18);
        root.addView(result, rp);

        int found = detectCard();
        if (found >= 0) {
            card.setText(String.valueOf(found));
            result.setText("Android распознал Razer Nari Ultimate: ALSA card=" + found +
                    ".\nДля эксперимента сначала нажми «Проверить Game»." );
        }
        refreshButtons();
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionListener);
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }
    private Button button(String name) {
        Button b = new Button(this);
        b.setText(name);
        b.setTextSize(17);
        b.setAllCaps(false);
        b.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(62));
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }
    private void status(String text) { state.setText(text); }
    private String safePing() {
        try { return route != null ? route.ping() : "сервис недоступен"; }
        catch (Exception ex) { return "Ошибка службы: " + ex; }
    }

    private int detectCard() {
        AudioManager mgr = (AudioManager) getSystemService(AUDIO_SERVICE);
        for (AudioDeviceInfo dev : mgr.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (dev.getType() != AudioDeviceInfo.TYPE_USB_DEVICE && dev.getType() != AudioDeviceInfo.TYPE_USB_HEADSET) continue;
            CharSequence name = dev.getProductName();
            if (name == null || !name.toString().toLowerCase().contains("razer")) continue;
            Matcher m = Pattern.compile("card=(\\d+);device=(\\d+)").matcher(dev.getAddress());
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    private void refreshButtons() {
        boolean available = route != null;
        probe.setEnabled(available);
        switchGame.setEnabled(available);
        restore.setEnabled(available);
        auth.setEnabled(!available);
    }

    private void updateShizuku() {
        if (!Shizuku.pingBinder()) {
            status("Shizuku не запущен. Установи его и запусти через ADB.");
            return;
        }
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                status("Требуется разрешение Shizuku для Nari TV Router");
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQ);
                return;
            }
            if (route == null && !binding) {
                binding = true;
                status("Запускаю UserService с правами shell…");
                Shizuku.bindUserService(serviceArgs, connection);
            }
        } catch (Exception e) {
            binding = false;
            status("Ошибка Shizuku: " + e);
        }
    }

    private void perform(String op) {
        final IRouteService active = route;
        if (active == null) { status("Сервис не подключён"); return; }
        final int cardNum;
        try { cardNum = Integer.parseInt(card.getText().toString()); }
        catch (NumberFormatException e) { result.setText("Введи номер ALSA-карты"); return; }
        probe.setEnabled(false);
        switchGame.setEnabled(false);
        restore.setEnabled(false);
        result.setText("Выполняю " + op + " для card=" + cardNum + "…");
        new Thread(() -> {
            String output;
            try {
                switch (op) {
                    case "probe": output = active.addGame(cardNum); break;
                    case "switch": output = active.switchGame(cardNum); break;
                    default: output = active.restore(cardNum); break;
                }
            } catch (Exception e) { output = "Ошибка Binder: " + e; }
            String message = output;
            runOnUiThread(() -> {
                result.setText(message);
                refreshButtons();
            });
        }, "route-request").start();
    }

    @Override protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        // Shizuku UserService is non-daemon; avoid calling unbind from a dead Binder.
        try { if (binding && Shizuku.pingBinder()) Shizuku.unbindUserService(serviceArgs, connection, false); }
        catch (Exception ignored) {}
        super.onDestroy();
    }
}
