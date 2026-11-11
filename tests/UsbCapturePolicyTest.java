import dev.usbmic.inspector.UsbCapturePolicy;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Synthetic fixtures exercise refusal boundaries; they are not observed phone descriptors. */
public final class UsbCapturePolicyTest {
    private static int checks;

    public static void main(String[] args) {
        byte[] valid = fixture();
        expect(valid, 2, "separate UAC1 capture/playback");
        expect(withFeatureUnit(valid), 2, "UAC1 capture feature unit");
        expect(null, -1, "null");
        expect(new byte[0], -1, "empty");
        for (int length = 0; length < valid.length; length++) {
            expect(Arrays.copyOf(valid, length), -1, "truncation " + length);
        }
        byte[] changed = valid.clone(); changed[17] = 2;
        expect(changed, -1, "multiple configurations advertised");
        changed = Arrays.copyOf(valid, valid.length + 9);
        System.arraycopy(valid, 18, changed, valid.length, 9);
        expect(changed, -1, "second configuration appended");
        changed = valid.clone(); changed[20]--;
        expect(changed, -1, "bad configuration total length");
        changed = valid.clone(); changed[27] = 0;
        expect(changed, -1, "zero descriptor length");
        changed = valid.clone(); changed[22] = 2;
        expect(changed, -1, "bad interface count");
        int captureInterface = find(valid, 4, 2, 2, 1);
        changed = valid.clone(); changed[captureInterface + 7] = 0x20;
        expect(changed, -1, "UAC2 interface protocol");
        changed = valid.clone(); changed[captureInterface + 5] = (byte) 0xff;
        expect(changed, -1, "mixed class alternate");
        changed = valid.clone(); changed[captureInterface + 2] = 1;
        expect(changed, -1, "shared playback/capture number across alternates");
        int captureEndpoint = find(valid, 5, 2, 0x82, -1);
        changed = valid.clone(); changed[captureEndpoint + 3] = 0x11;
        expect(changed, -1, "explicit feedback IN endpoint");
        changed = valid.clone(); changed[captureEndpoint + 3] = 0x21;
        expect(changed, -1, "implicit feedback IN endpoint");
        changed = valid.clone(); changed[captureEndpoint + 3] = (byte) 0x81;
        expect(changed, -1, "reserved endpoint attribute bits");
        changed = valid.clone(); changed[captureEndpoint + 2] = 2;
        expect(changed, -1, "OUT endpoint on capture terminal");
        changed = valid.clone(); changed[captureInterface + 4] = 2;
        expect(changed, -1, "declared extra endpoint");
        int general = captureInterface + 9;
        changed = valid.clone(); changed[general + 3] = 99;
        expect(changed, -1, "missing terminal link");
        changed = valid.clone(); changed[general + 3] = 3;
        expect(changed, -1, "capture links playback terminal");
        int header = 36;
        changed = valid.clone(); changed[header + 4] = 2;
        expect(changed, -1, "UAC2 control version");
        changed = valid.clone(); changed[header + 9] = 1;
        expect(changed, -1, "duplicate collection entry");
        changed = valid.clone(); changed[header + 5]--;
        expect(changed, -1, "control total length");
        changed = valid.clone(); changed[27 + 6] = 2;
        expect(changed, -1, "control descriptor treated as streaming");
        changed = valid.clone(); changed[captureInterface - 9 + 3] = 1;
        expect(changed, -1, "duplicate active alternate and missing idle alternate");
        changed = valid.clone(); changed[find(valid, 0x24, 3, 2, -1) + 7] = 99;
        expect(changed, -1, "missing output terminal source entity");
        byte[] controlOnly = Arrays.copyOf(valid, 88);
        setWord(controlOnly, 20, controlOnly.length - 18);
        expect(controlOnly, -1, "control only");
        System.out.println("PASS: " + checks + " descriptor-policy assertions; synthetic descriptors only");
    }

    private static void expect(byte[] raw, int id, String label) {
        UsbCapturePolicy.Result result = UsbCapturePolicy.analyze(raw);
        if (result.candidateInterfaceId != id) {
            throw new AssertionError(label + ": expected " + id + ", got "
                    + result.candidateInterfaceId + " " + result.reason + "\n" + result.describe());
        }
        if (result.describe() == null || result.reason == null) { throw new AssertionError(label); }
        checks++;
    }

    private static int find(byte[] raw, int type, int field, int value, int alt) {
        for (int p = 0; p < raw.length; p += raw[p] & 255) {
            if ((raw[p + 1] & 255) == type && (raw[p + field] & 255) == value
                    && (alt < 0 || (raw[p + 3] & 255) == alt)) { return p; }
        }
        throw new AssertionError("fixture descriptor missing");
    }

    private static byte[] fixture() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        add(b, 18,1,0,2,0,0,0,64,0x34,0x12,0x78,0x56,0,1,0,0,0,1);
        add(b, 9,2,0,0,3,1,0,0x80,50);
        add(b, 9,4,0,0,0,1,1,0,0);
        add(b, 10,0x24,1,0,1,52,0,2,1,2);
        add(b, 12,0x24,2,1,1,2,0,1,0,0,0,0); // physical microphone input
        add(b, 9,0x24,3,2,1,1,0,1,0); // capture's USB output terminal
        add(b, 12,0x24,2,3,1,1,0,2,3,0,0,0); // playback's USB input terminal
        add(b, 9,0x24,3,4,1,3,0,3,0); // physical speaker output
        stream(b, 1, 3, 1);
        stream(b, 2, 2, 0x82);
        byte[] raw = b.toByteArray();
        setWord(raw, 20, raw.length - 18);
        return raw;
    }

    private static byte[] withFeatureUnit(byte[] valid) {
        byte[] result = new byte[valid.length + 9];
        System.arraycopy(valid, 0, result, 0, 58);
        byte[] feature = {9, 0x24, 6, 5, 1, 1, 3, 0, 0};
        System.arraycopy(feature, 0, result, 58, feature.length);
        System.arraycopy(valid, 58, result, 67, valid.length - 58);
        result[74] = 5; // USB capture output terminal sources feature unit 5.
        setWord(result, 20, result.length - 18);
        setWord(result, 41, 61);
        return result;
    }

    private static void stream(ByteArrayOutputStream b, int id, int terminal, int endpoint) {
        add(b, 9,4,id,0,0,1,2,0,0);
        add(b, 9,4,id,1,1,1,2,0,0);
        add(b, 7,0x24,1,terminal,1,1,0);
        add(b, 11,0x24,2,1,1,2,16,1,0x80,0xbb,0); // mono PCM16 48kHz
        add(b, 9,5,endpoint,1,96,0,1,0,0);
        add(b, 7,0x25,1,0,0,0,0);
    }

    private static void setWord(byte[] raw, int at, int value) {
        raw[at] = (byte) value; raw[at + 1] = (byte) (value >> 8);
    }
    private static void add(ByteArrayOutputStream out, int... bytes) {
        for (int value : bytes) { out.write(value); }
    }
}
