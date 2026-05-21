package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.media.AudioManager;
import android.os.IBinder;
import android.os.IInterface;
import android.util.Log;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.Slog;


public class IAudioServiceProxy extends BinderInvocationStub {
    public static final String TAG = "AudioServiceProxy";

    public IAudioServiceProxy() {
        super(BRServiceManager.get().getService(Context.AUDIO_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService(Context.AUDIO_SERVICE);
        if (binder == null) {
            Slog.e(TAG, "Failed to get AUDIO_SERVICE binder");
            return null;
        }
        
        try {
            
            Object iface = null;
            
            
            try {
                iface = Reflector.on("android.media.IAudioService$Stub").call("asInterface", binder);
            } catch (Exception e1) {
                Slog.d(TAG, "Failed Android 16+ path, trying alternative: " + e1.getMessage());
                
                
                try {
                    iface = Reflector.on("android.media.IAudioService").call("asInterface", binder);
                } catch (Exception e2) {
                    Slog.d(TAG, "Failed alternative path: " + e2.getMessage());
                    
                    
                    try {
                        Class<?> stubClass = Class.forName("android.media.IAudioService$Stub");
                        Method asInterfaceMethod = stubClass.getMethod("asInterface", IBinder.class);
                        iface = asInterfaceMethod.invoke(null, binder);
                    } catch (Exception e3) {
                        Slog.e(TAG, "All reflection paths failed for IAudioService", e3);
                        return null;
                    }
                }
            }
            
            if (iface != null) {
                Slog.d(TAG, "Successfully obtained IAudioService interface");
                return (IInterface) iface;
            } else {
                Slog.e(TAG, "Reflection succeeded but returned null interface");
                return null;
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to get IAudioService interface", e);
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            return super.invoke(proxy, method, args);
        } catch (Throwable t) {
            if (isSecurityException(t)) {
                Log.w(TAG, "IAudioService call '" + method.getName()
                        + "' denied, returning safe default", t);
                return safeDefault(method.getReturnType());
            }
            throw t;
        }
    }

    private static boolean isSecurityException(Throwable t) {
        Throwable cause = t;
        int depth = 0;
        while (cause != null && depth++ < 16) {
            if (cause instanceof SecurityException) return true;
            Throwable next = cause.getCause();
            if (next == cause) break;
            cause = next;
        }
        return false;
    }

    private static Object safeDefault(Class<?> returnType) {
        if (returnType == boolean.class || returnType == Boolean.class) return Boolean.FALSE;
        if (returnType == int.class     || returnType == Integer.class) return 0;
        if (returnType == long.class    || returnType == Long.class)    return 0L;
        if (returnType == float.class   || returnType == Float.class)   return 0f;
        if (returnType == double.class  || returnType == Double.class)  return 0.0;
        if (returnType == short.class   || returnType == Short.class)   return (short) 0;
        if (returnType == byte.class    || returnType == Byte.class)    return (byte) 0;
        if (returnType == char.class    || returnType == Character.class) return '\0';
        if (returnType == void.class    || returnType == Void.class)    return null;
        return null;
    }


    @ProxyMethod("isMicrophoneMuted")
    public static class IsMicrophoneMuted extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: isMicrophoneMuted returning false");
            return false;
        }
    }

    
    @ProxyMethod("setMicrophoneMute")
    public static class SetMicrophoneMute extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: setMicrophoneMute called, forcing unmute");
            
            if (args != null && args.length > 0) {
                args[0] = false; 
            }
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("startRecording")
    public static class StartRecording extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: startRecording called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("stopRecording")
    public static class StopRecording extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: stopRecording called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("isRecordingActive")
    public static class IsRecordingActive extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: isRecordingActive called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("getRecordingState")
    public static class GetRecordingState extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: getRecordingState called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("isMicrophoneMutedForUser")
    public static class IsMicrophoneMutedForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: isMicrophoneMutedForUser returning false");
            return false;
        }
    }

    
    @ProxyMethod("setMicrophoneMuteForUser")
    public static class SetMicrophoneMuteForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: setMicrophoneMuteForUser called, forcing unmute");
            
            if (args != null && args.length > 1) {
                args[1] = false; 
            }
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("getRecordingStateForUser")
    public static class GetRecordingStateForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: getRecordingStateForUser called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("requestAudioFocus")
    public static class RequestAudioFocus extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: requestAudioFocus called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("registerAudioFocusClient")
    public static class RegisterAudioFocusClient extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: registerAudioFocusClient called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("unregisterAudioFocusClient")
    public static class UnregisterAudioFocusClient extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: unregisterAudioFocusClient called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("abandonAudioFocus")
    public static class AbandonAudioFocus extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: abandonAudioFocus called, allowing");
            return method.invoke(who, args);
        }
    }
}
