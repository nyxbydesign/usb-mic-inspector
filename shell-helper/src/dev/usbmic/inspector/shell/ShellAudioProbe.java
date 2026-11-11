package dev.usbmic.inspector.shell;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Read-only shell capability inventory. This class contains no audio mutation path. */
public final class ShellAudioProbe {
    private ShellAudioProbe() { }

    public static void main(String[] args) {
        if (args.length > 1 || (args.length == 1 && !"probe".equals(args[0]))) {
            System.err.println("Usage: ShellAudioProbe [probe]. Read-only; no mutation commands exist.");
            System.exit(2);
            return;
        }
        System.out.println("USB audio shell probe 1; read_only=true");
        System.out.println("uid=" + Process.myUid() + "; sdk=" + Build.VERSION.SDK_INT);
        if (Process.myUid() != 2000) {
            System.err.println("REFUSED: run through an existing authorized ADB/LADB shell (UID 2000).");
            System.exit(2);
            return;
        }
        if (Build.VERSION.SDK_INT < 31) {
            System.err.println("REFUSED: this probe targets Android 12/API 31 and later.");
            System.exit(2);
            return;
        }
        boolean complete = true;
        Context context = null;
        try {
            // app_process has no application context; obtain one without an installed APK.
            if (Looper.myLooper() == null) {
                Looper.prepareMainLooper();
            }
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Context systemContext = (Context) activityThread.getMethod("getSystemContext")
                    .invoke(thread);
            context = systemContext.createPackageContext("com.android.shell", 0);
            if (!"com.android.shell".equals(context.getPackageName())
                    || context.getApplicationInfo().uid != Process.myUid()) {
                throw new SecurityException("Shell package does not match the process UID");
            }
            System.out.println("context_package=" + context.getPackageName()
                    + "; context_uid=" + context.getApplicationInfo().uid);
            // Derived contexts may retain an operation-package attribution from their parent.
            // The inventory APIs below do not send a package or AttributionSource to AudioService.
            System.out.println("attribution_package="
                    + context.getAttributionSource().getPackageName()
                    + "; package_attribution_not_used_by_inventory=true");
        } catch (ReflectiveOperationException | PackageManager.NameNotFoundException
                | RuntimeException | LinkageError failure) {
            context = null;
            reportFailure("shell_package_context", failure);
            complete = false;
        }

        if (context != null) {
            String[] permissions = {
                "android.permission.MODIFY_AUDIO_ROUTING",
                "android.permission.MANAGE_AUDIO_POLICY",
                "android.permission.MODIFY_AUDIO_SETTINGS",
                "android.permission.QUERY_AUDIO_STATE"
            };
            for (String permission : permissions) {
                try {
                    boolean granted = context.checkPermission(permission, Process.myPid(),
                            Process.myUid()) == PackageManager.PERMISSION_GRANTED;
                    System.out.println(permission + "=" + (granted ? "GRANTED" : "DENIED"));
                } catch (RuntimeException failure) {
                    reportFailure(permission, failure);
                    complete = false;
                }
            }
            try {
                AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (audio == null) {
                    throw new IllegalStateException("AudioManager unavailable");
                }
                System.out.println("audio_mode=" + audio.getMode()
                        + "; normal_mode=" + AudioManager.MODE_NORMAL);
                System.out.println("visible_active_recording_count="
                        + audio.getActiveRecordingConfigurations().size()
                        + "; visibility_may_be_limited=true");
                int count = 0;
                for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_ALL)) {
                    if (isUsb(device.getType())) {
                        System.out.println("USB role=" + (device.isSource() ? "INPUT" : "OUTPUT")
                                + "; type=" + device.getType()
                                + "; address=" + safeAddress(device.getAddress()));
                        count++;
                    }
                }
                System.out.println("usb_audio_port_count=" + count);
            } catch (RuntimeException | LinkageError failure) {
                reportFailure("audio_inventory", failure);
                complete = false;
            }
        }

        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) serviceManager.getMethod("getService", String.class)
                    .invoke(null, "audio");
            System.out.println("audio_binder_alive=" + (binder != null && binder.isBinderAlive()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            reportFailure("audio_binder", failure);
            complete = false;
        }

        try {
            Class<?> attributes = Class.forName("android.media.AudioDeviceAttributes");
            attributes.getConstructor(AudioDeviceInfo.class);
            System.out.println("constructor_present=AudioDeviceAttributes(AudioDeviceInfo)");
            Class<?> service = Class.forName("android.media.IAudioService");
            Method method = service.getMethod("setWiredDeviceConnectionState", attributes,
                    int.class, String.class);
            System.out.println("method_present=" + method.getName()
                    + "(AudioDeviceAttributes,int,String)");
            System.out.println("method_invoked=false; constructor_invoked=false");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            reportFailure("hidden_api_introspection", failure);
            complete = false;
        }

        System.out.println("result=" + (complete ? "READ_ONLY_PROBE_COMPLETE" : "PARTIAL_OR_BLOCKED"));
        System.out.println("No routing, settings, USB claims or permissions were changed.");
        System.out.println("Permission/method presence does not prove input-only routing will work.");
        System.exit(complete ? 0 : 1);
    }

    private static boolean isUsb(int type) {
        return type == AudioDeviceInfo.TYPE_USB_DEVICE
                || type == AudioDeviceInfo.TYPE_USB_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }

    private static String safeAddress(String address) {
        if (address == null) {
            return "<null>";
        }
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < Math.min(address.length(), 160); i++) {
            char character = address.charAt(i);
            safe.append(character >= 32 && character <= 126 ? character : '?');
        }
        if (address.length() > 160) {
            safe.append("<truncated>");
        }
        return safe.toString();
    }

    private static void reportFailure(String operation, Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof InvocationTargetException
                && ((InvocationTargetException) cause).getTargetException() != null) {
            cause = ((InvocationTargetException) cause).getTargetException();
        }
        // Exception messages and stack dumps can contain device or package information.
        System.out.println(operation + "=UNAVAILABLE; reason=" + cause.getClass().getName());
        System.out.println("Stop at this result; do not grant permissions or change hidden-API settings.");
    }
}
