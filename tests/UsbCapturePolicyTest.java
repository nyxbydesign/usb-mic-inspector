import dev.usbmic.inspector.UsbCapturePolicy;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Synthetic and anonymized descriptor fixtures; no fixture proves physical routing behavior. */
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
        separateHidCases();
        System.out.println("PASS: " + checks + " descriptor-policy assertions; synthetic/anonymized descriptors only");
    }

    private static void separateHidCases() {
        byte[] hid = separateHidFixture();
        if (hid.length != 289 || (hid[8] & 255) != 0x34 || (hid[9] & 255) != 0x12
                || (hid[10] & 255) != 0x78 || (hid[11] & 255) != 0x56
                || hid[14] != 0 || hid[15] != 0 || hid[16] != 0) {
            throw new AssertionError("regression fixture must remain anonymized");
        }
        expect(hid, 1, "separate capture 1, playback 2, HID 3");
        int hidInterface = find(hid, 4, 2, 3, 0);
        int hidDescriptor = hidInterface + 9;
        int hidEndpoint = hidDescriptor + 9;
        byte[] hidFirst = new byte[hid.length];
        System.arraycopy(hid, 0, hidFirst, 0, 27);
        System.arraycopy(hid, hidInterface, hidFirst, 27, hid.length - hidInterface);
        System.arraycopy(hid, 27, hidFirst, 27 + hid.length - hidInterface, hidInterface - 27);
        expect(hidFirst, 1, "HID first: no stale audio parser context");
        expect(fixtureWithHid(), 2, "synthetic original topology plus optional HID");

        for (int length = hidInterface + 1; length < hid.length; length++) {
            byte[] truncated = Arrays.copyOf(hid, length);
            setWord(truncated, 20, length - 18);
            expect(truncated, -1, "truncated HID with reconciled configuration length " + length);
        }
        mutate(hid, hidInterface + 2, 1, "HID aliases capture interface number");
        mutate(hid, hidInterface + 2, 0, "HID aliases control interface number");
        mutate(hid, hidInterface + 2, 2, "HID aliases playback interface number");
        mutate(hid, hidInterface + 3, 1, "HID alternate one");
        mutate(hid, hidInterface + 4, 0, "HID endpoint count zero");
        mutate(hid, hidInterface + 4, 2, "HID endpoint count two");
        mutate(hid, hidInterface + 5, 0xff, "unknown extra interface class");
        mutate(hid, hidInterface + 6, 1, "boot HID subclass");
        mutate(hid, hidInterface + 7, 1, "keyboard HID protocol");
        mutate(hid, hidDescriptor, 8, "short HID descriptor");
        mutate(hid, hidDescriptor + 1, 0x23, "unknown descriptor type");
        mutate(hid, hidDescriptor + 2, 0x10, "unsupported HID version");
        mutate(hid, hidDescriptor + 4, 36, "reserved HID country code");
        mutate(hid, hidDescriptor + 5, 0, "HID report descriptor count zero");
        mutate(hid, hidDescriptor + 5, 2, "HID report descriptor count two");
        mutate(hid, hidDescriptor + 6, 0x23, "HID non-report descriptor");
        mutate(hid, hidDescriptor + 7, 0, "HID report length zero");
        mutate(hid, hidEndpoint + 2, 5, "HID OUT endpoint");
        mutate(hid, hidEndpoint + 2, 0x80, "HID endpoint zero");
        mutate(hid, hidEndpoint + 2, 0x95, "HID endpoint reserved address bits");
        mutate(hid, hidEndpoint + 2, 0x83, "HID endpoint aliases capture endpoint");
        mutate(hidFirst, 47, 0x83, "HID-first endpoint aliases later capture endpoint");
        mutate(hid, hidEndpoint + 3, 1, "HID isochronous endpoint");
        mutate(hid, hidEndpoint + 3, 2, "HID bulk endpoint");
        mutate(hid, hidEndpoint + 3, 0x83, "HID reserved endpoint attribute bits");
        mutate(hid, hidEndpoint + 4, 0, "HID zero packet size");
        mutate(hid, hidEndpoint + 4, 65, "HID packet exceeds supported 64 bytes");
        mutate(hid, hidEndpoint + 5, 8, "HID reserved/high bandwidth packet bits");
        mutate(hid, hidEndpoint + 6, 0, "HID zero interval");
        mutate(hid, 45, 3, "HID inserted in audio control collection");
        mutate(hid, 22, 3, "HID omitted from interface count");

        byte[] duplicate = insert(hid, hidEndpoint, Arrays.copyOfRange(hid, hidDescriptor, hidEndpoint));
        expect(duplicate, -1, "duplicate HID class descriptor");
        duplicate = insert(hid, hid.length, Arrays.copyOfRange(hid, hidInterface, hid.length));
        expect(duplicate, -1, "duplicate HID interface alternate");
        duplicate[hid.length + 2] = 4;
        duplicate[22] = 5;
        expect(duplicate, -1, "second separate HID interface");
        duplicate = insert(hid, hid.length, Arrays.copyOfRange(hid, hidEndpoint, hid.length));
        expect(duplicate, -1, "extra HID endpoint");
        byte[] missing = remove(hid, hidDescriptor, 9);
        expect(missing, -1, "HID endpoint without HID descriptor");
        missing = remove(hid, hidEndpoint, 7);
        expect(missing, -1, "HID descriptor without endpoint");
        byte[] misplaced = insert(hid, hidInterface, Arrays.copyOfRange(hid, hidDescriptor, hidEndpoint));
        expect(misplaced, -1, "HID descriptor in audio streaming context");
        byte[] roleConflict = hid.clone();
        roleConflict[hidInterface + 2] = 1;
        roleConflict[hidInterface + 3] = 2;
        expect(roleConflict, -1, "HID role conflicts with capture across distinct alternates");
        byte[] secondCapture = hid.clone();
        secondCapture[hidInterface + 5] = 1;
        secondCapture[hidInterface + 6] = 2;
        expect(secondCapture, -1, "extra HID cannot become audio streaming by class mutation");
    }

    private static void mutate(byte[] original, int offset, int value, String label) {
        byte[] changed = original.clone();
        changed[offset] = (byte) value;
        expect(changed, -1, label);
    }

    private static byte[] insert(byte[] original, int offset, byte[] addition) {
        byte[] changed = new byte[original.length + addition.length];
        System.arraycopy(original, 0, changed, 0, offset);
        System.arraycopy(addition, 0, changed, offset, addition.length);
        System.arraycopy(original, offset, changed, offset + addition.length, original.length - offset);
        setWord(changed, 20, changed.length - 18);
        return changed;
    }

    private static byte[] remove(byte[] original, int offset, int length) {
        byte[] changed = new byte[original.length - length];
        System.arraycopy(original, 0, changed, 0, offset);
        System.arraycopy(original, offset + length, changed, offset, original.length - offset - length);
        setWord(changed, 20, changed.length - 18);
        return changed;
    }

    private static byte[] fixtureWithHid() {
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        add(tail, 9,4,3,0,1,3,0,0,0);
        add(tail, 9,0x21,0x11,1,0,1,0x22,74,0);
        add(tail, 7,5,0x85,3,64,0,8);
        byte[] base = fixture();
        byte[] changed = insert(base, base.length, tail.toByteArray());
        changed[22] = 4;
        return changed;
    }

    /** Anonymized regression topology: identifiers 1234:5678 and device string indices cleared. */
    private static byte[] separateHidFixture() {
        String hex = "12010002000000403412785600010000"
                + "000109020f0104010080320904000000"
                + "010100000a2401000148000201020c24"
                + "021e01020002030000000a24060e1e01"
                + "02020200092403020101000e000c2402"
                + "0301010002030000000a24060a030103"
                + "000000092403040103000a0009040100"
                + "00010200000904010101010200000724"
                + "01020101000e2402010202100244ac00"
                + "80bb0009058305c00001000007250101"
                + "00000009040200000102000009040201"
                + "0101020000072401030101000e240201"
                + "0202100244ac0080bb00090502095802"
                + "01000007250101020800090402020101"
                + "020000072401030101000e2402010203"
                + "180244ac0080bb000905020958020100"
                + "00072501010208000904030001030000"
                + "00092111010001224a00070585034000"
                + "08";
        byte[] raw = new byte[hex.length() / 2];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return raw;
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
