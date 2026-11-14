package dev.usbmic.inspector;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final String USB_PERMISSION = "dev.usbmic.inspector.USB_PERMISSION";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final StringBuilder report = new StringBuilder();
    private AudioManager audio;
    private UsbManager usb;
    private TextView output;
    private TextView status;
    private LinearLayout devices;
    private Button claimButton;
    private Spinner source;
    private UsbDevice selected;
    private String descriptorHash;
    private int candidateId = -1;
    private volatile boolean probing;
    private volatile AudioRecord recorder;
    private volatile AudioTrack player;
    private boolean receiverRegistered;

    private final BroadcastReceiver usbPermission = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    && usb.hasPermission(device)) inspect(device);
            else append("USB permission not granted. No interface was claimed.");
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            String message = CaptureClaimService.status;
            if (CaptureClaimService.held) {
                long seconds = Math.max(0, (CaptureClaimService.deadline - SystemClock.elapsedRealtime()) / 1000);
                message += "\nTime remaining: " + seconds + " seconds.";
            }
            status.setText(message);
            claimButton.setEnabled(candidateId >= 0 && !probing
                    && !CaptureClaimService.held && !CaptureClaimService.starting);
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audio = getSystemService(AudioManager.class);
        usb = getSystemService(UsbManager.class);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding);
        scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(scroll);
        label(body, "USB Mic Inspector", 26);
        label(body, "Diagnostic prototype. No proven system-wide microphone fix. Tests run only when you tap a button. No audio is saved or sent.", 16);
        status = label(body, CaptureClaimService.status, 15);
        button(body, "Refresh device inventory", view -> inventory());
        devices = new LinearLayout(this);
        devices.setOrientation(LinearLayout.VERTICAL);
        body.addView(devices);
        label(body, "Microphone probe (3 seconds, this app only)", 19);
        source = new Spinner(this);
        source.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"MIC source", "VOICE_COMMUNICATION source (no mode change)"}));
        body.addView(source);
        button(body, "Probe default microphone", view -> startMic(false));
        button(body, "Probe preferred built-in microphone", view -> startMic(true));
        label(body, "A preferred device is not proof of the actual route. The report reads the route while recording; Discord and calls need separate verification.", 14);
        button(body, "Test USB playback (quiet 2-second tone)", view -> confirmPlayback());
        button(body, "Stop local probe", view -> stopProbe());
        label(body, "Experimental capture detach", 19);
        label(body, "Only available after inspecting a supported separate capture interface. It may disrupt USB playback or leave stale microphone routing. Release may not reconnect the driver; unplug/replug may be required. Never start during a call or another recording.", 14);
        claimButton = button(body, "Review capture-only experiment", view -> confirmClaim());
        claimButton.setEnabled(false);
        button(body, "Release / restore USB interface", view -> {
            stopProbe();
            startService(new Intent(this, CaptureClaimService.class).setAction(CaptureClaimService.ACTION_RELEASE));
        });
        button(body, "Copy diagnostic report", view -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText("USB microphone diagnostic", completeReport()));
            append("Diagnostic report copied. Review it before sharing.");
        });
        button(body, "Share diagnostic report", view -> {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, completeReport());
            startActivity(Intent.createChooser(send, "Share diagnostic report"));
        });
        output = label(body, "", 13);
        output.setTextIsSelectable(true);
        registerReceiver(usbPermission, new IntentFilter(USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
        append("Device: " + Build.MANUFACTURER + " " + Build.MODEL + "; Android " + Build.VERSION.RELEASE
                + "; API " + Build.VERSION.SDK_INT + "; build " + Build.DISPLAY + "; fingerprint " + Build.FINGERPRINT);
        inventory();
    }

    private TextView label(LinearLayout body, String text, int sp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setPadding(0, 10, 0, 10);
        body.addView(view);
        return view;
    }

    private Button button(LinearLayout body, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        body.addView(button);
        return button;
    }

    private void append(String text) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> append(text));
            return;
        }
        report.append("\n[").append(java.time.Instant.now()).append("] ").append(text).append('\n');
        if (report.length() > 180_000) report.delete(0, report.length() - 150_000);
        if (output != null) output.setText(report.toString());
    }

    private String completeReport() {
        return "USB Mic Inspector 0.2-diagnostic\nNo global routing result has been established by this report.\n"
                + CaptureClaimService.status + "\n" + report;
    }

    private void inventory() {
        if (devices == null) return;
        devices.removeAllViews();
        append("Audio mode=" + audio.getMode() + "; microphone muted=" + audio.isMicrophoneMute()
                + "; visible active recordings=" + audio.getActiveRecordingConfigurations().size()
                + " (visibility is limited; this cannot certify no other app is recording).");
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_INPUTS)) append(audioDevice(device));
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) append(audioDevice(device));
        if (usb.getDeviceList().isEmpty()) label(devices, "No USB devices found.", 16);
        for (UsbDevice device : usb.getDeviceList().values()) {
            String name = usbIdentity(device);
            append(name);
            button(devices, "Inspect " + name, view -> requestInspection(device));
        }
        if (selected != null && !usb.getDeviceList().containsKey(selected.getDeviceName())) {
            selected = null;
            candidateId = -1;
            descriptorHash = null;
        }
    }

    private String usbIdentity(UsbDevice device) {
        return String.format(Locale.ROOT, "%s VID=%04x PID=%04x path=%s", device.getProductName(),
                device.getVendorId(), device.getProductId(), device.getDeviceName());
    }

    private void requestInspection(UsbDevice device) {
        if (probing || CaptureClaimService.held || CaptureClaimService.starting) {
            append("Stop the probe or release the experiment before USB inspection.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            append("Microphone permission is required for USB audio access. After granting it, tap Inspect again. Nothing starts automatically.");
            return;
        }
        if (usb.hasPermission(device)) inspect(device);
        else {
            Intent permission = new Intent(USB_PERMISSION).setPackage(getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(this, 0, permission,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            usb.requestPermission(device, pending);
        }
    }

    private void inspect(UsbDevice device) {
        if (probing || CaptureClaimService.held || CaptureClaimService.starting) {
            append("Inspection deferred: probe or experiment is active. Tap Inspect after stopping it.");
            return;
        }
        candidateId = -1;
        descriptorHash = null;
        selected = device;
        append("Inspecting " + usbIdentity(device) + "; configurations=" + device.getConfigurationCount());
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            append(String.format(Locale.ROOT, "Interface %d alt %d class=%02x subclass=%02x protocol=%02x endpoints=%d",
                    intf.getId(), intf.getAlternateSetting(), intf.getInterfaceClass(), intf.getInterfaceSubclass(),
                    intf.getInterfaceProtocol(), intf.getEndpointCount()));
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint endpoint = intf.getEndpoint(e);
                append(String.format(Locale.ROOT, "  Endpoint %02x %s type=%d attributes=%02x maxPacket=%d interval=%d",
                        endpoint.getAddress(), endpoint.getDirection() == 128 ? "IN (toward phone)" : "OUT (toward DAC)",
                        endpoint.getType(), endpoint.getAttributes(), endpoint.getMaxPacketSize(), endpoint.getInterval()));
            }
        }
        UsbDeviceConnection connection = null;
        try {
            connection = usb.openDevice(device);
            if (connection == null) { append("USB open failed; no experiment available."); return; }
            byte[] raw = connection.getRawDescriptors();
            UsbCapturePolicy.Result result = UsbCapturePolicy.analyze(raw);
            append(result.describe());
            if (raw == null) { append("Raw USB descriptors unavailable."); return; }
            descriptorHash = UsbSafety.sha256(raw);
            append("Raw descriptors SHA-256: " + descriptorHash + "\n" + hex(raw));
            if (result.candidateInterfaceId >= 0 && UsbSafety.captureInterface(device, result.candidateInterfaceId) != null) {
                candidateId = result.candidateInterfaceId;
                append("Descriptor candidate " + candidateId + " passed the framework cross-check. Kernel ownership and global fallback remain unverified.");
            } else append("Capture experiment unavailable: " + result.reason);
        } catch (RuntimeException failure) {
            append("USB inspection failed: " + failure);
        } finally {
            if (connection != null) connection.close();
        }
    }

    private static String hex(byte[] raw) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < raw.length; i++) {
            if (i % 16 == 0) text.append(String.format(Locale.ROOT, "%n%04x: ", i));
            text.append(String.format(Locale.ROOT, "%02x ", raw[i] & 255));
        }
        return text.toString();
    }

    private boolean readyForProbe() {
        if (probing || CaptureClaimService.starting) { append("Another test is running."); return false; }
        if (audio.getMode() != AudioManager.MODE_NORMAL || !audio.getActiveRecordingConfigurations().isEmpty()) {
            append("Call/audio activity detected; local test refused. End calls and recordings first.");
            return false;
        }
        return true;
    }

    private void startMic(boolean builtIn) {
        if (!readyForProbe()) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            append("After granting microphone permission, tap the probe again. No recording starts automatically.");
            return;
        }
        int selectedSource = source.getSelectedItemPosition() == 0 ? MediaRecorder.AudioSource.MIC : MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        AudioDeviceInfo preferred = null;
        if (builtIn) {
            for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) { preferred = device; break; }
            }
            if (preferred == null) { append("No built-in microphone exposed by Android."); return; }
        }
        final AudioDeviceInfo requested = preferred;
        probing = true;
        cancelled.set(false);
        append("Starting explicit 3-second local mic probe, source=" + selectedSource + ", preference=" + (builtIn ? "built-in" : "none/default") + ".");
        new Thread(() -> {
            AudioRecord local = null;
            try {
                int rate = 48000;
                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) throw new IllegalStateException("Unsupported record buffer: " + min);
                local = new AudioRecord.Builder().setAudioSource(selectedSource)
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(Math.max(min, 9600)).build();
                recorder = local;
                if (local.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Recorder not initialized.");
                boolean preferenceAccepted = requested == null || local.setPreferredDevice(requested);
                append("Local preferred-device request accepted=" + preferenceAccepted + "; actual route follows while reading.");
                if (cancelled.get() || audio.getMode() != AudioManager.MODE_NORMAL) return;
                local.startRecording();
                short[] samples = new short[2400];
                long end = SystemClock.elapsedRealtime() + 3000;
                long squareSum = 0;
                long sampleCount = 0;
                String lastRoute = "";
                boolean silenced = false;
                while (!cancelled.get() && SystemClock.elapsedRealtime() < end) {
                    if (audio.getMode() != AudioManager.MODE_NORMAL) { append("Audio mode changed; stopping local mic probe."); break; }
                    int read = local.read(samples, 0, samples.length, AudioRecord.READ_NON_BLOCKING);
                    if (read < 0) throw new IllegalStateException("AudioRecord.read error=" + read);
                    for (int i = 0; i < read; i++) squareSum += (long) samples[i] * samples[i];
                    sampleCount += read;
                    String route = audioDevice(local.getRoutedDevice());
                    if (!route.equals(lastRoute)) { append("Actual active local mic route: " + route); lastRoute = route; }
                    AudioRecordingConfiguration configuration = local.getActiveRecordingConfiguration();
                    if (configuration != null) silenced |= configuration.isClientSilenced();
                    SystemClock.sleep(10);
                }
                double rms = sampleCount == 0 ? 0 : Math.sqrt((double) squareSum / sampleCount);
                append(String.format(Locale.ROOT, "Mic probe: samples=%d RMS=%.2f/32768, clientSilencedObserved=%s. Silence does not prove a phantom mic. App-local result only.", sampleCount, rms, silenced));
            } catch (RuntimeException failure) { append("Mic probe failed: " + failure); }
            finally {
                recorder = null;
                if (local != null) { try { local.stop(); } catch (RuntimeException ignored) {} local.release(); }
                probing = false;
            }
        }, "mic-probe").start();
    }

    private void confirmPlayback() {
        if (!readyForProbe()) return;
        new AlertDialog.Builder(this).setTitle("Play a short test tone?")
                .setMessage("Set a comfortable low media volume first. The app requests USB output and reports the actual route. Android may choose another output. Do not test during calls.")
                .setNegativeButton("Cancel", null).setPositiveButton("Play 2 seconds", (dialog, which) -> startPlayback()).show();
    }

    private void startPlayback() {
        if (!readyForProbe()) return;
        AudioDeviceInfo preferred = null;
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_USB_DEVICE || device.getType() == AudioDeviceInfo.TYPE_USB_HEADSET
                    || device.getType() == AudioDeviceInfo.TYPE_USB_ACCESSORY) { preferred = device; break; }
        }
        if (preferred == null) { append("No USB output is currently exposed by Android."); return; }
        final AudioDeviceInfo requested = preferred;
        cancelled.set(false);
        probing = true;
        new Thread(() -> {
            AudioTrack local = null;
            try {
                int min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) throw new IllegalStateException("Unsupported playback buffer: " + min);
                local = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(min, 9600)).build();
                player = local;
                if (!local.setPreferredDevice(requested)) throw new IllegalStateException("USB output preference rejected.");
                if (cancelled.get() || audio.getMode() != AudioManager.MODE_NORMAL) return;
                append("Playback requested: " + audioDevice(requested));
                local.play();
                short[] samples = new short[2400];
                int position = 0;
                String lastRoute = "";
                long end = SystemClock.elapsedRealtime() + 2500;
                while (position < 96000 && !cancelled.get() && SystemClock.elapsedRealtime() < end) {
                    if (audio.getMode() != AudioManager.MODE_NORMAL) { append("Audio mode changed; stopping playback probe."); break; }
                    for (int i = 0; i < samples.length; i++) samples[i] = (short) (1500 * Math.sin(2 * Math.PI * 440 * (position + i) / 48000));
                    int written = local.write(samples, 0, samples.length, AudioTrack.WRITE_NON_BLOCKING);
                    if (written < 0) throw new IllegalStateException("AudioTrack.write error=" + written);
                    position += written;
                    String route = audioDevice(local.getRoutedDevice());
                    if (!route.equals(lastRoute)) { append("Actual active playback route: " + route); lastRoute = route; }
                    SystemClock.sleep(10);
                }
                append("Playback probe ended. Confirm audibility in the wired headphones yourself; route metadata alone does not prove sound reached them.");
            } catch (RuntimeException failure) { append("Playback probe failed: " + failure); }
            finally {
                player = null;
                if (local != null) { try { local.stop(); } catch (RuntimeException ignored) {} local.release(); }
                probing = false;
            }
        }, "usb-playback-probe").start();
    }

    private void stopProbe() {
        cancelled.set(true);
        AudioRecord localRecorder = recorder;
        AudioTrack localPlayer = player;
        if (localRecorder != null) try { localRecorder.stop(); } catch (RuntimeException ignored) {}
        if (localPlayer != null) try { localPlayer.pause(); localPlayer.flush(); } catch (RuntimeException ignored) {}
    }

    private void confirmClaim() {
        if (!readyForProbe() || selected == null || candidateId < 0 || descriptorHash == null) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
            append("Notification permission is required for the visible Release control. After granting, tap experiment again.");
            return;
        }
        LinearLayout confirmations = new LinearLayout(this);
        confirmations.setOrientation(LinearLayout.VERTICAL);
        CheckBox idle = new CheckBox(this);
        idle.setText(R.string.confirm_idle);
        CheckBox planned = new CheckBox(this);
        planned.setText(R.string.planned_test);
        confirmations.addView(idle);
        confirmations.addView(planned);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Experimental USB capture claim")
                .setMessage("Force-claim capture interface " + candidateId + " only. Playback may fail; Android may keep a stale USB mic route. This cannot guarantee built-in fallback. Release or timeout may need unplug/replug.\n\nDefault: release when audio mode changes. Optional planned communication test keeps the claim until you release it or 2 minutes elapse, which can interrupt that test.")
                .setView(confirmations).setNegativeButton("Cancel", null).setPositiveButton("Claim capture only", null).create();
        dialog.setOnShowListener(ignored -> {
            Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            confirm.setEnabled(false);
            idle.setOnCheckedChangeListener((button, checked) -> confirm.setEnabled(checked));
            confirm.setOnClickListener(view -> {
                if (!idle.isChecked() || !readyForProbe()) return;
                Intent experiment = new Intent(this, CaptureClaimService.class)
                        .putExtra("device", selected.getDeviceName()).putExtra("interface", candidateId)
                        .putExtra("descriptorHash", descriptorHash).putExtra("idleConfirmed", true)
                        .putExtra("plannedCommunication", planned.isChecked());
                CaptureClaimService.starting = true;
                try { startForegroundService(experiment); }
                catch (RuntimeException failure) { CaptureClaimService.starting = false; append("Cannot start experiment: " + failure); }
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    static String audioDevice(AudioDeviceInfo device) {
        if (device == null) return "unavailable/null (no route established)";
        String type;
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: type = "BUILTIN_MIC"; break;
            case AudioDeviceInfo.TYPE_USB_DEVICE: type = "USB_DEVICE"; break;
            case AudioDeviceInfo.TYPE_USB_HEADSET: type = "USB_HEADSET"; break;
            case AudioDeviceInfo.TYPE_USB_ACCESSORY: type = "USB_ACCESSORY"; break;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: type = "BUILTIN_SPEAKER"; break;
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: type = "BUILTIN_EARPIECE"; break;
            default: type = "type=" + device.getType();
        }
        return "Audio id=" + device.getId() + " " + type + " input=" + device.isSource() + " output=" + device.isSink()
                + " name=" + device.getProductName() + " address=" + device.getAddress();
    }

    @Override protected void onStart() { super.onStart(); handler.post(ticker); }
    @Override protected void onStop() { handler.removeCallbacks(ticker); stopProbe(); super.onStop(); }
    @Override protected void onDestroy() {
        stopProbe();
        if (receiverRegistered) unregisterReceiver(usbPermission);
        super.onDestroy();
    }
}
