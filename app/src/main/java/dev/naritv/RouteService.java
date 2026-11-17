package dev.naritv;

import android.os.Binder;
import android.os.Process;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Shizuku UserService: hosted by app_process as UID 2000 (adb shell).
 * Never run these Binder calls in the regular application's UID.
 */
public final class RouteService extends IRouteService.Stub {
    private static final String NAME = "USB-Audio - Razer Nari Ultimate";
    private static final int USB_OUT_INTERNAL = 0x4000; // AudioSystem.DEVICE_OUT_USB_DEVICE

    public RouteService() {}

    @Override public String ping() {
        return "UserService UID=" + Process.myUid() +
                (Process.myUid() == 2000 || Process.myUid() == 0
                        ? " (достаточные права для эксперимента)"
                        : " (нет привилегий shell/root)");
    }

    private static void validate(int card) {
        if (Process.myUid() != 2000 && Process.myUid() != 0) {
            throw new SecurityException("UserService должен работать через Shizuku (UID shell/root)");
        }
        if (card < 0 || card > 63) throw new IllegalArgumentException("Неверный ALSA card");
        if (!new File("/dev/snd/pcmC" + card + "D0p").exists() ||
            !new File("/dev/snd/pcmC" + card + "D1p").exists()) {
            throw new IllegalStateException("Для card=" + card + " не найдены оба PCM-выхода D0 и D1");
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
        Constructor<?> constructor = adaClass.getConstructor(int.class, String.class, String.class);
        Object attrs = constructor.newInstance(USB_OUT_INTERNAL, addr,
                dev == 1 ? "Razer Nari Ultimate - Game" : NAME);
        Method method = Class.forName("android.media.IAudioService")
                .getMethod("setWiredDeviceConnectionState", adaClass, int.class, String.class);
        long identity = Binder.clearCallingIdentity();
        try {
            method.invoke(service, attrs, connected ? 1 : 0, "NariTV-Shizuku");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("AudioService отклонил " + addr + ": " + cause, cause);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return (connected ? "Подключение " : "Отключение ") + addr + " отправлено AudioService";
    }

    /** Non-destructive probe: original Chat device remains available. */
    @Override public String addGame(int card) {
        try {
            validate(card);
            return change(card, 1, true) +
                "\nПроверь наличие device=1 в dumpsys media.audio_policy." +
                "\nНаличие в ALSA само по себе не гарантирует рабочий звук.";
        } catch (Exception e) { return "ОШИБКА: " + e; }
    }

    /** Register D1 first, then remove D0. May fail on the vendor's USB audio HAL. */
    @Override public String switchGame(int card) {
        try {
            validate(card);
            String result = change(card, 1, true);
            Thread.sleep(950);
            result += "\n" + change(card, 0, false);
            return result + "\nПопробуй воспроизведение на небольшой громкости." +
                "\nЕсли звука нет — нажми 'Вернуть Chat' либо переподключи USB-донгл.";
        } catch (Exception e) { return "ОШИБКА: " + e; }
    }

    @Override public String restore(int card) {
        try {
            validate(card);
            String result = change(card, 0, true);
            Thread.sleep(950);
            result += "\n" + change(card, 1, false);
            return result + "\nВозвращена стандартная схема Android (Chat).";
        } catch (Exception e) { return "ОШИБКА: " + e +
                 "\nДля сброса можно вынуть и снова вставить USB-донгл."; }
    }

    /** Required by Shizuku. */
    @Override public void destroy() { System.exit(0); }
}
