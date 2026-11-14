package dev.usbmic.inspector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

/** Deliberate, bounded experiment; never started by attachment, permission or boot. */
public final class CaptureClaimService extends Service {
    static final String ACTION_RELEASE = "dev.usbmic.inspector.RELEASE";
    static volatile boolean held;
    static volatile boolean starting;
    static volatile long deadline;
    static volatile String status = "No USB interface claimed.";
    private static final String CHANNEL = "usb_experiment";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private UsbDeviceConnection connection;
    private UsbInterface claimed;
    private String deviceName;
    private boolean plannedCommunication;
    private boolean receiverRegistered;
    private boolean finished;

    static String experimentSnapshot() {
        boolean heldNow = held;
        boolean startingNow = starting;
        long deadlineNow = deadline;
        String statusNow = status;
        long remaining = heldNow ? Math.max(0, (deadlineNow - SystemClock.elapsedRealtime() + 999) / 1000) : 0;
        return "held=" + heldNow + ", starting=" + startingNow + ", remainingSeconds=" + remaining
                + ", status=" + statusNow;
    }

    private final BroadcastReceiver detached = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null && device.getDeviceName().equals(deviceName)) {
                ExperimentHistory.record("USB detach event for the experimental device; " + experimentSnapshot());
                finish("USB device detached.");
            }
        }
    };

    private final Runnable guard = new Runnable() {
        @Override public void run() {
            if (!held) return;
            AudioManager audio = getSystemService(AudioManager.class);
            if (SystemClock.elapsedRealtime() >= deadline) {
                ExperimentHistory.record("Experiment timeout reached; " + experimentSnapshot());
                finish("Two-minute experiment ended. Unplug/replug if routing did not recover.");
                return;
            }
            if (!plannedCommunication && audio.getMode() != AudioManager.MODE_NORMAL) {
                ExperimentHistory.record("Audio mode change triggered release; " + experimentSnapshot());
                finish("Audio mode changed; released before communication test. Replug if needed.");
                return;
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        ExperimentHistory.record("Service request received: " + (intent == null ? "null intent"
                : ACTION_RELEASE.equals(intent.getAction()) ? "release" : "capture claim")
                + "; " + experimentSnapshot());
        if (intent == null || ACTION_RELEASE.equals(intent.getAction())) {
            ExperimentHistory.record("Service release requested; " + experimentSnapshot());
            finish("Released. Driver restoration is not guaranteed; unplug/replug if needed.");
            return START_NOT_STICKY;
        }
        if (held || connection != null) {
            ExperimentHistory.record("Service claim request refused: an interface is held or a connection is already open; "
                    + experimentSnapshot());
            return START_NOT_STICKY;
        }
        finished = false;
        starting = true;
        String stage = "preflight";
        try {
            if (!intent.getBooleanExtra("idleConfirmed", false)) throw new IllegalStateException("Idle confirmation missing.");
            AudioManager audio = getSystemService(AudioManager.class);
            if (audio.getMode() != AudioManager.MODE_NORMAL || !audio.getActiveRecordingConfigurations().isEmpty()) {
                throw new IllegalStateException("Audio is busy. End calls and recordings before testing.");
            }
            UsbManager manager = getSystemService(UsbManager.class);
            deviceName = intent.getStringExtra("device");
            UsbDevice device = manager.getDeviceList().get(deviceName);
            if (device == null || !manager.hasPermission(device)) throw new IllegalStateException("Device or USB permission missing.");
            connection = manager.openDevice(device);
            if (connection == null) throw new IllegalStateException("Cannot open USB device.");
            byte[] raw = connection.getRawDescriptors();
            UsbCapturePolicy.Result result = UsbCapturePolicy.analyze(raw);
            if (result.candidateInterfaceId < 0
                    || result.candidateInterfaceId != intent.getIntExtra("interface", -1)
                    || raw == null || !UsbSafety.sha256(raw).equals(intent.getStringExtra("descriptorHash"))) {
                throw new IllegalStateException("Descriptor gate changed or refused. Inspect again.");
            }
            UsbInterface candidate = UsbSafety.captureInterface(device, result.candidateInterfaceId);
            if (candidate == null) throw new IllegalStateException("Framework descriptor cross-check refused.");
            plannedCommunication = intent.getBooleanExtra("plannedCommunication", false);
            NotificationManager notifications = getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "USB experiment", NotificationManager.IMPORTANCE_LOW));
            PendingIntent release = PendingIntent.getService(this, 1,
                    new Intent(this, CaptureClaimService.class).setAction(ACTION_RELEASE),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle("USB capture experiment: 2 minute limit")
                    .setContentText("Playback may fail. Finish before timeout. Release; replug may be needed.")
                    .setContentIntent(open).setOngoing(true)
                    .addAction(new Notification.Action.Builder(null, "Release", release).build()).build();
            startForeground(1, notification);
            // Last mode check immediately before the only driver-disconnecting operation.
            if (audio.getMode() != AudioManager.MODE_NORMAL || !audio.getActiveRecordingConfigurations().isEmpty()) {
                throw new IllegalStateException("Audio became busy; experiment cancelled.");
            }
            stage = "claimInterface";
            ExperimentHistory.record("claimInterface attempt: interface=" + candidate.getId() + ", force=true; " + experimentSnapshot());
            boolean claimResult = connection.claimInterface(candidate, true);
            ExperimentHistory.record("claimInterface result: interface=" + candidate.getId() + ", accepted=" + claimResult);
            if (!claimResult) throw new IllegalStateException("Kernel refused capture interface claim. Replug if audio changed.");
            claimed = candidate;
            held = true;
            deadline = SystemClock.elapsedRealtime() + 120_000;
            status = "Interface " + candidate.getId() + " claimed experimentally. This proves neither playback nor global mic routing. "
                    + (plannedCommunication ? "Planned manual communication test enabled. " : "Releases if audio mode changes. ")
                    + "Finish before 2-minute timeout; unplug/replug may be needed.";
            ExperimentHistory.record("Capture claim is now held: interface=" + candidate.getId() + "; " + experimentSnapshot());
            stage = "post-claim setup";
            registerReceiver(detached, new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
            handler.post(guard);
        } catch (RuntimeException failure) {
            ExperimentHistory.record("Experiment " + stage + " refused/failed: " + ExperimentHistory.failure(failure)
                    + "; " + experimentSnapshot());
            finish("Experiment stopped: " + failure.getMessage());
        } finally {
            starting = false;
            ExperimentHistory.record("Service claim request processing ended; " + experimentSnapshot());
        }
        return START_NOT_STICKY;
    }

    private void finish(String reason) {
        ExperimentHistory.record("Cleanup requested: " + reason + "; claimedInterfacePresent=" + (claimed != null)
                + ", connectionPresent=" + (connection != null) + "; " + experimentSnapshot());
        if (finished) {
            ExperimentHistory.record("Cleanup skipped: already finished; " + experimentSnapshot());
            return;
        }
        finished = true;
        handler.removeCallbacksAndMessages(null);
        boolean released = true;
        boolean heldBefore = held;
        boolean hadClaimedInterface = claimed != null;
        String releaseOutcome = "not attempted (no claimed interface)";
        String closeOutcome = "not attempted (no connection)";
        if (connection != null) {
            try {
                if (claimed != null) {
                    ExperimentHistory.record("releaseInterface attempt: interface=" + claimed.getId() + ", heldBefore=" + heldBefore);
                    released = connection.releaseInterface(claimed);
                    releaseOutcome = "returned " + released;
                    ExperimentHistory.record("releaseInterface result: interface=" + claimed.getId() + ", accepted=" + released);
                } else {
                    ExperimentHistory.record("releaseInterface skipped: no interface had been claimed.");
                }
            } catch (RuntimeException failure) {
                released = false;
                releaseOutcome = "threw " + ExperimentHistory.failure(failure);
                ExperimentHistory.record("releaseInterface failed: " + ExperimentHistory.failure(failure));
            } finally {
                try {
                    connection.close();
                    closeOutcome = "returned normally";
                    ExperimentHistory.record("USB connection close returned normally; driver restoration remains unverified.");
                } catch (RuntimeException failure) {
                    ExperimentHistory.record("USB connection close failed: " + ExperimentHistory.failure(failure)
                            + "; cleanup aborted; " + experimentSnapshot());
                    throw failure;
                }
                connection = null;
                claimed = null;
            }
        }
        held = false;
        starting = false;
        deadline = 0;
        status = reason + (released ? "" : " releaseInterface failed; unplug/replug required.");
        ExperimentHistory.record("Cleanup completed: heldBefore=" + heldBefore + ", claimedInterfacePresentBefore="
                + hadClaimedInterface + ", releaseInterface=" + releaseOutcome + ", connectionClose=" + closeOutcome
                + "; " + experimentSnapshot() + ". No system-routing or driver-restoration success is implied.");
        if (receiverRegistered) {
            unregisterReceiver(detached);
            receiverRegistered = false;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        ExperimentHistory.record("Service task removed; " + experimentSnapshot());
        finish("Task removed; released. Unplug/replug if routing did not recover.");
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        ExperimentHistory.record("Service onDestroy received; " + experimentSnapshot());
        finish("Service stopped; released. Unplug/replug if routing did not recover.");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
