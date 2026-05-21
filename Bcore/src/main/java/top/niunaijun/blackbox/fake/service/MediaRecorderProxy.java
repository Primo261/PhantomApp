package top.niunaijun.blackbox.fake.service;

import android.media.MediaRecorder;
import android.util.Log;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;


public class MediaRecorderProxy extends ClassInvocationStub {
    public static final String TAG = "MediaRecorderProxy";

    public MediaRecorderProxy() {
        super();
    }

    @Override
    protected Object getWho() {
        return null; 
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        
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
                Log.w(TAG, "MediaRecorder call '" + method.getName()
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


    @ProxyMethod("<init>")
    public static class Constructor extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "MediaRecorder: Constructor called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("setAudioSource")
    public static class SetAudioSource extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "MediaRecorder: setAudioSource called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("setOutputFormat")
    public static class SetOutputFormat extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "MediaRecorder: setOutputFormat called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("setAudioEncoder")
    public static class SetAudioEncoder extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "MediaRecorder: setAudioEncoder called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("setOutputFile")
    public static class SetOutputFile extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "MediaRecorder: setOutputFile called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("prepare")
    public static class Prepare extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "MediaRecorder: prepare called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("start")
    public static class Start extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "MediaRecorder: start called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("stop")
    public static class Stop extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "MediaRecorder: stop called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("release")
    public static class Release extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "MediaRecorder: release called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("reset")
    public static class Reset extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "MediaRecorder: reset called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("setAudioSamplingRate")
    public static class SetAudioSamplingRate extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "MediaRecorder: setAudioSamplingRate called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("setAudioChannels")
    public static class SetAudioChannels extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "MediaRecorder: setAudioChannels called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("setAudioEncodingBitRate")
    public static class SetAudioEncodingBitRate extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "MediaRecorder: setAudioEncodingBitRate called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("setMaxDuration")
    public static class SetMaxDuration extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "MediaRecorder: setMaxDuration called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("setMaxFileSize")
    public static class SetMaxFileSize extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "MediaRecorder: setMaxFileSize called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("setOnErrorListener")
    public static class SetOnErrorListener extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "MediaRecorder: setOnErrorListener called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("setOnInfoListener")
    public static class SetOnInfoListener extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "MediaRecorder: setOnInfoListener called, allowing");
            return method.invoke(who, args);
        }
    }
}
