package dev.naritv;

import android.os.Binder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Runs as UID 2000 inside a persistent Shizuku UserService. ADB shell has
 * MODIFY_AUDIO_ROUTING; the ordinary Android app does not.
 *
 * Auto-repair only reacts to actual ALSA node reconnects, or to Android
 * unexpectedly marking Chat as available. It never toggles the audio path
 * periodically while a stream is playing.
 */
public final class RouteService extends IRouteService.Stub {
    private static final String TAG = "NariTV";
    private static final String NAME = "USB-Audio - Razer Nari Ultimate";
    private static final int USB_OUT_INTERNAL = 0x4000; // AudioSystem.DEVICE_OUT_USB_DEVICE
    private static final int STATE_UNKNOWN = -9;
    private final Object routeLock = new Object();
    private volatile boolean autoEnabled = false;
    private volatile int autoCard = -1;
    private volatile String autoStatus = "Автовосстановление выключено";
    private volatile boolean alive = true;
    private final Thread monitor;

    public RouteService() {
        monitor = new Thread(this::monitorLoop, "nari-usb-monitor");
        monitor.setDaemon(true); // The Shizuku UserService process itself persists.
        monitor.start();
    }

    @Override public String ping() {
        return "UserService UID=" + Process.myUid() +
                (Process.myUid() == 2000 || Process.myUid() == 0
                        ? " (доступ к AudioService есть)" : " (недостаточно прав)");
    }

    private static boolean present(int card) {
        return card >= 0 && card <= 63
                && new File("/dev/snd/pcmC" + card + "D0p").exists()
                && new File("/dev/snd/pcmC" + card + "D1p").exists();
    }

    private static void validate(int card) {
        if (Process.myUid() != 2000 && Process.myUid() != 0) {
            throw new SecurityException("Shizuku UserService должен работать от shell/root");
        }
        if (card < 0 || card > 63) throw new IllegalArgumentException("Неверный ALSA card");
        if (!present(card)) {
            throw new IllegalStateException("Для card=" + card + " отсутствуют PCM D0 и/или D1");
        }
    }

    private static String change(int card, int dev, boolean connected) throws Exception {
        String addr = "card=" + card + ";device=" + dev;
        Class<?> iBinder = Class.forName("android.os.IBinder");
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Object binder = serviceManager.getMethod("getService", String.class).invoke(null, "audio");
        if (binder == null) throw new IllegalStateException("AudioService недоступен");
        Class<?> stub = Class.forName("android.media.IAudioService$Stub");
        Object service = stub.getMethod("asInterface", iBinder).invoke(null, binder);
        Class<?> adaClass = Class.forName("android.media.AudioDeviceAttributes");
        Constructor<?> ctor = adaClass.getConstructor(int.class, String.class, String.class);
        Object attrs = ctor.newInstance(USB_OUT_INTERNAL, addr,
                dev == 1 ? "Razer Nari Ultimate - Game" : NAME);
        Method method = Class.forName("android.media.IAudioService")
                .getMethod("setWiredDeviceConnectionState", adaClass, int.class, String.class);
        long identity = Binder.clearCallingIdentity();
        try {
            method.invoke(service, attrs, connected ? 1 : 0, "NariTV-Shizuku");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("AudioService отклонил " + addr + ": " + cause, cause);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return (connected ? "AVAILABLE " : "UNAVAILABLE ") + addr;
    }

    /** Native AudioPolicy state. 1 = connected, 0 = not connected. */
    private static int deviceState(int card, int dev) {
        try {
            Class<?> audioSystem = Class.forName("android.media.AudioSystem");
            Method getState = audioSystem.getMethod("getDeviceConnectionState", int.class, String.class);
            Object val = getState.invoke(null, USB_OUT_INTERNAL, "card=" + card + ";device=" + dev);
            return ((Integer) val).intValue();
        } catch (Throwable e) {
            return STATE_UNKNOWN;
        }
    }

    private String doSwitch(int card) throws Exception {
        validate(card);
        synchronized (routeLock) {
            String first = change(card, 1, true);
            Thread.sleep(800);
            return first + "\n" + change(card, 0, false) +
                    "\nGame зарегистрирован. Для переоткрытия потока при тишине нажми «Обновить Game».";
        }
    }

    /**
     * Force actual output-port loss and re-add, rather than re-announcing
     * an already-available device=1. Expected to reopen the AudioFlinger HAL
     * output. If the second change fails, try to restore Game in finally.
     */
    private String doRefresh(int card) throws Exception {
        validate(card);
        synchronized (routeLock) {
            // If the USB device is still connected, it is safe to re-create
            // the *output* routing. The mic / input path is untouched.
            if (deviceState(card, 1) != 1) {
                change(card, 1, true);
                Thread.sleep(500);
            }
            String down = change(card, 1, false);
            Thread.sleep(850);
            if (!present(card)) throw new IllegalStateException("USB исчез во время перезапуска");
            String up = change(card, 1, true);
            Thread.sleep(600);
            String offChat = change(card, 0, false);
            return down + "\n" + up + "\n" + offChat +
                    "\nGame переинициализирован (разрыв ~1 c). " +
                    "Проверь воспроизведение: HAL может вести себя по-разному.";
        }
    }

    @Override public String addGame(int card) {
        try { validate(card); synchronized(routeLock) { return change(card, 1, true); } }
        catch (Exception e) { return "ОШИБКА: " + e; }
    }

    @Override public String switchGame(int card) {
        try { return doSwitch(card); }
        catch (Exception e) { return "ОШИБКА: " + e; }
    }

    @Override public String refreshGame(int card) {
        try { return doRefresh(card); }
        catch (Exception e) {
            // The best-effort restoration is harmless when the USB device is absent.
            try { if (present(card)) synchronized(routeLock) { change(card, 1, true); } }
            catch (Exception restoreError) { Log.w(TAG, "Failed to restore Game", restoreError); }
            return "ОШИБКА: " + e;
        }
    }

    @Override public String restore(int card) {
        autoEnabled = false; // Prevent the monitor from immediately undoing Chat restoration.
        autoStatus = "Автовосстановление выключено (восстановлен Chat)";
        try {
            validate(card);
            synchronized(routeLock) {
                String first = change(card, 0, true);
                Thread.sleep(900);
                return first + "\n" + change(card, 1, false) + "\nВозвращён Chat.";
            }
        } catch (Exception e) { return "ОШИБКА: " + e; }
    }

    @Override public String setAutoRepair(int card, boolean enabled) {
        if (enabled) {
            try { validate(card); }
            catch (Exception e) { return "ОШИБКА: " + e; }
            autoCard = card;
            autoEnabled = true;
            autoStatus = "Активно: слежение за card=" + card + ". Ожидаю переподключения USB…";
        } else {
            autoEnabled = false;
            autoCard = -1;
            autoStatus = "Автовосстановление выключено";
        }
        return autoStatus;
    }

    @Override public String getAutoStatus() { return autoStatus; }

    private void monitorLoop() {
        int trackedCard = -1;
        boolean wasPresent = false;
        int previousChatState = STATE_UNKNOWN;
        boolean sawDisconnect = false;
        long repairDue = 0;
        while (alive) {
            try {
                if (!autoEnabled || autoCard < 0) {
                    trackedCard = -1;
                    sawDisconnect = false;
                    repairDue = 0;
                    Thread.sleep(1000);
                    continue;
                }
                int c = autoCard;
                boolean nowPresent = present(c);
                int chatState = nowPresent ? deviceState(c, 0) : STATE_UNKNOWN;
                long now = SystemClock.elapsedRealtime();
                if (trackedCard != c) {
                    trackedCard = c;
                    wasPresent = nowPresent;
                    previousChatState = chatState;
                } else {
                    if (wasPresent && !nowPresent) {
                        sawDisconnect = true;
                        repairDue = 0;
                        autoStatus = "USB card=" + c + " отключён; жду подключения";
                    }
                    if (!wasPresent && nowPresent && sawDisconnect) {
                        repairDue = now + 2600;
                        autoStatus = "USB переподключён, через 2.6 с обновлю Game";
                    }
                    // UsbAlsaManager may re-register Chat without removing the
                    // ALSA nodes (for example when it reselects the device).
                    if (nowPresent && previousChatState == 0 && chatState == 1) {
                        repairDue = now + 2000;
                        autoStatus = "Android восстановил Chat; исправлю маршрутизацию";
                    }
                    wasPresent = nowPresent;
                    previousChatState = chatState;
                }
                if (repairDue > 0 && nowPresent && now >= repairDue) {
                    repairDue = 0;
                    sawDisconnect = false;
                    // A fixed card is deliberately used; never touch unknown
                    // ALSA devices when USB re-enumeration changes the index.
                    autoStatus = "Восстанавливаю Game на card=" + c + "…";
                    String message = doRefresh(c);
                    autoStatus = "Game обновлён: " + message.replace('\n', ';');
                    Log.i(TAG, autoStatus);
                    previousChatState = deviceState(c, 0);
                }
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            } catch (Throwable e) {
                autoStatus = "Ошибка автоматики: " + e;
                Log.w(TAG, "Auto-repair error", e);
                repairDue = 0;
                try { Thread.sleep(2000); } catch (InterruptedException interrupted) { break; }
            }
        }
    }

    @Override public void destroy() {
        alive = false;
        monitor.interrupt();
        System.exit(0);
    }
}
