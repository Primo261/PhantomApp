package top.niunaijun.blackbox.utils;

import android.Manifest;

/**
 * Centralised whitelist of Android runtime permissions that virtualised apps
 * should always see as {@code PERMISSION_GRANTED}, regardless of whether the
 * system or the virtual PackageManager would deny them.
 *
 * Inspired by Waxmoon Multi App's "force grant2" path
 * (decompiled {@code org.waxmoon.engine.c70.e4} → {@code nz0.b}). The host
 * (PhantomApp) already holds every permission listed here on the real device
 * via its own manifest, so granting them inside the sandbox is safe: any
 * resulting access still goes through PhantomApp's identity on the device.
 *
 * Triggered by the Vinted "ajouter photo" loop on Android 16 — sandboxed
 * {@code checkSelfPermission(READ_MEDIA_IMAGES)} kept returning DENIED even
 * after {@code GrantPermissionsActivity} reported GRANTED, causing the app
 * to re-prompt every ~10s and display "une erreur est survenue".
 *
 * All consumers (proxy hooks, virtual PackageManager, AppOps proxy) should
 * delegate to this single source of truth to keep the whitelist coherent.
 */
public final class RuntimePermissionWhitelist {

    private RuntimePermissionWhitelist() {}

    public static boolean isAutoGranted(String permission) {
        if (permission == null) return false;
        return isStorageOrMedia(permission)
                || isCamera(permission)
                || isLocation(permission)
                || isAudio(permission)
                || isNotification(permission)
                || isForegroundService(permission)
                || isXiaomiQuirk(permission);
    }

    public static boolean isStorageOrMedia(String p) {
        if (p == null) return false;
        switch (p) {
            case Manifest.permission.READ_EXTERNAL_STORAGE:
            case Manifest.permission.WRITE_EXTERNAL_STORAGE:
            case Manifest.permission.READ_MEDIA_AUDIO:
            case Manifest.permission.READ_MEDIA_VIDEO:
            case Manifest.permission.READ_MEDIA_IMAGES:
            case Manifest.permission.ACCESS_MEDIA_LOCATION:
            case "android.permission.READ_MEDIA_VISUAL_USER_SELECTED":
            case "android.permission.READ_MEDIA_IMAGES_USER_SELECTED":
            case "android.permission.READ_MEDIA_VIDEO_USER_SELECTED":
            case "android.permission.READ_MEDIA_AUDIO_USER_SELECTED":
            case "android.permission.READ_MEDIA_VISUAL":
            case "android.permission.READ_MEDIA_AURAL":
            case "android.permission.READ_MEDIA_AURAL_USER_SELECTED":
            case "android.permission.MANAGE_EXTERNAL_STORAGE":
                return true;
            default:
                return false;
        }
    }

    public static boolean isCamera(String p) {
        return Manifest.permission.CAMERA.equals(p);
    }

    public static boolean isLocation(String p) {
        if (p == null) return false;
        return Manifest.permission.ACCESS_FINE_LOCATION.equals(p)
                || Manifest.permission.ACCESS_COARSE_LOCATION.equals(p)
                || "android.permission.ACCESS_BACKGROUND_LOCATION".equals(p);
    }

    public static boolean isAudio(String p) {
        if (p == null) return false;
        return Manifest.permission.RECORD_AUDIO.equals(p)
                || Manifest.permission.MODIFY_AUDIO_SETTINGS.equals(p)
                || Manifest.permission.CAPTURE_AUDIO_OUTPUT.equals(p);
    }

    public static boolean isNotification(String p) {
        return "android.permission.POST_NOTIFICATIONS".equals(p);
    }

    public static boolean isForegroundService(String p) {
        if (p == null) return false;
        switch (p) {
            case "android.permission.FOREGROUND_SERVICE":
            case "android.permission.FOREGROUND_SERVICE_MICROPHONE":
            case "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION":
            case "android.permission.FOREGROUND_SERVICE_CAMERA":
            case "android.permission.FOREGROUND_SERVICE_LOCATION":
            case "android.permission.FOREGROUND_SERVICE_HEALTH":
            case "android.permission.FOREGROUND_SERVICE_DATA_SYNC":
            case "android.permission.FOREGROUND_SERVICE_SPECIAL_USE":
            case "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED":
            case "android.permission.FOREGROUND_SERVICE_PHONE_CALL":
            case "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE":
            case "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK":
            case "android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING":
                return true;
            default:
                return false;
        }
    }

    public static boolean isXiaomiQuirk(String p) {
        if (p == null) return false;
        switch (p) {
            case "miui.permission.USE_INTERNAL_GENERAL_API":
            case "miui.permission.OPTIMIZE_POWER":
            case "miui.permission.RUN_IN_BACKGROUND":
            case "miui.permission.POST_NOTIFICATIONS":
            case "miui.permission.AUTO_START":
            case "miui.permission.BACKGROUND_POPUP_WINDOW":
            case "miui.permission.SHOW_WHEN_LOCKED":
            case "miui.permission.TURN_SCREEN_ON":
                return true;
            default:
                return false;
        }
    }
}
