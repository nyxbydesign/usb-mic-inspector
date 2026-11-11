package dev.usbmic.inspector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Conservative descriptor gate. A permitted claim is experimental, never proof of driver isolation. */
public final class UsbCapturePolicy {
    private UsbCapturePolicy() {}

    public static final class Result {
        public final int candidateInterfaceId;
        public final String reason;
        private final String description;

        private Result(int id, String reason, String description) {
            this.candidateInterfaceId = id;
            this.reason = reason;
            this.description = description;
        }

        public String describe() {
            return description + "\nGate: " + reason + "\n"
                    + "Separate descriptors do not prove independent kernel ownership. "
                    + "Forcing a claim may disconnect the entire ALSA sound card and USB playback. "
                    + "Release may not restore it; unplug/replug may be necessary.";
        }
    }

    private static final class Alt {
        int id, alt, expectedEndpoints, endpoints, direction, terminal = -1;
        boolean general, format, classEndpoint;
        Set<Integer> addresses = new HashSet<>();
    }

    private static final class Stream {
        final int id;
        final List<Alt> alts = new ArrayList<>();
        Stream(int id) { this.id = id; }
    }

    private static final class Terminal {
        final int subtype, type;
        Terminal(int subtype, int type) { this.subtype = subtype; this.type = type; }
    }

    private static final class Refusal extends Exception {
        Refusal(String message) { super(message); }
    }

    public static Result analyze(byte[] raw) {
        StringBuilder report = new StringBuilder();
        try {
            return parse(raw, report);
        } catch (Refusal refusal) {
            return new Result(-1, "Experiment unavailable: " + refusal.getMessage(), report.toString());
        } catch (RuntimeException malformed) {
            // Fail closed even for an unforeseen descriptor shape.
            return new Result(-1, "Experiment unavailable: malformed descriptors", report.toString());
        }
    }

    private static Result parse(byte[] raw, StringBuilder report) throws Refusal {
        require(raw != null && raw.length >= 27, "missing complete device/configuration descriptors");
        require(u(raw, 0) == 18 && u(raw, 1) == 1, "expected one 18-byte device descriptor");
        require(u(raw, 17) == 1, "only single-configuration devices are supported");
        require(u(raw, 18) == 9 && u(raw, 19) == 2, "expected one configuration descriptor");
        int configLength = word(raw, 20);
        require(configLength == raw.length - 18, "configuration total length mismatch or extra configurations");
        int expectedInterfaces = u(raw, 22);
        report.append("Device VID:PID ").append(hex(word(raw, 8), 4)).append(':')
                .append(hex(word(raw, 10), 4)).append("; single configuration, bytes=")
                .append(raw.length).append('\n');

        Map<Integer, Stream> streams = new LinkedHashMap<>();
        Map<Integer, Terminal> terminals = new HashMap<>();
        Set<Integer> entityIds = new HashSet<>();
        List<Integer> entitySources = new ArrayList<>();
        Set<Integer> interfaceIds = new HashSet<>();
        Set<String> alternateIds = new HashSet<>();
        Set<Integer> collection = new HashSet<>();
        int controlId = -1, subclass = -1, controlBytes = 0, declaredControlBytes = -1;
        boolean header = false;
        Alt current = null;

        for (int offset = 27; offset < raw.length;) {
            require(offset + 2 <= raw.length, "truncated descriptor header");
            int length = u(raw, offset), type = u(raw, offset + 1);
            require(length >= 2 && offset + length <= raw.length, "invalid descriptor length at " + offset);
            report.append('@').append(offset).append(" type=0x").append(hex(type, 2))
                    .append(" length=").append(length);
            if (type == 4) {
                finishAlt(current);
                require(length == 9, "invalid interface descriptor");
                int id = u(raw, offset + 2), alternate = u(raw, offset + 3);
                int count = u(raw, offset + 4), clazz = u(raw, offset + 5);
                subclass = u(raw, offset + 6);
                int protocol = u(raw, offset + 7);
                report.append(" interface=").append(id).append(" alt=").append(alternate)
                        .append(" class=").append(clazz).append(" subclass=").append(subclass)
                        .append(" protocol=").append(protocol).append(" endpoints=").append(count);
                require(clazz == 1 && protocol == 0 && (subclass == 1 || subclass == 2),
                        "only UAC1 AudioControl and AudioStreaming interfaces are supported");
                require(alternateIds.add(id + ":" + alternate), "duplicate interface alternate setting");
                interfaceIds.add(id);
                current = null;
                if (subclass == 1) {
                    require(controlId == -1 && alternate == 0 && count == 0,
                            "control interface must be unique, alt zero and endpoint-free");
                    controlId = id;
                } else {
                    require(id != controlId, "control and streaming share an interface number");
                    Stream stream = streams.get(id);
                    if (stream == null) { stream = new Stream(id); streams.put(id, stream); }
                    current = new Alt();
                    current.id = id; current.alt = alternate; current.expectedEndpoints = count;
                    stream.alts.add(current);
                }
            } else if (type == 0x24) {
                require(length >= 3, "short class-specific interface descriptor");
                int subtype = u(raw, offset + 2);
                report.append(" subtype=").append(subtype);
                if (subclass == 1) {
                    controlBytes += length;
                    if (subtype == 1) {
                        require(!header && length >= 8 && word(raw, offset + 3) == 0x0100,
                                "missing or unsupported UAC1 control header");
                        header = true;
                        declaredControlBytes = word(raw, offset + 5);
                        int number = u(raw, offset + 7);
                        require(length == 8 + number && number > 0, "invalid control interface collection");
                        for (int i = 0; i < number; i++) {
                            require(collection.add(u(raw, offset + 8 + i)), "duplicate control collection entry");
                        }
                    } else if (subtype == 2 || subtype == 3) {
                        require(length == (subtype == 2 ? 12 : 9), "invalid UAC1 terminal descriptor");
                        int id = u(raw, offset + 3);
                        require(id != 0 && entityIds.add(id), "duplicate or zero audio entity ID");
                        terminals.put(id, new Terminal(subtype, word(raw, offset + 4)));
                        if (subtype == 3) { entitySources.add(u(raw, offset + 7)); }
                        report.append(" terminal=").append(id).append(" terminalType=0x")
                                .append(hex(word(raw, offset + 4), 4));
                    } else if (subtype == 6) {
                        require(length >= 8, "short UAC1 feature unit");
                        int id = u(raw, offset + 3), size = u(raw, offset + 5);
                        require(id != 0 && entityIds.add(id), "duplicate or zero audio entity ID");
                        require(size > 0 && size <= 4 && (length - 7) % size == 0,
                                "invalid feature unit controls");
                        entitySources.add(u(raw, offset + 4));
                    } else {
                        throw new Refusal("unsupported control unit or descriptor subtype");
                    }
                } else {
                    require(subclass == 2 && current != null && current.alt != 0,
                            "class-specific descriptor outside active audio stream");
                    if (subtype == 1) {
                        require(!current.general && length == 7 && word(raw, offset + 5) == 1,
                                "expected unique UAC1 PCM stream general descriptor");
                        current.general = true;
                        current.terminal = u(raw, offset + 3);
                    } else if (subtype == 2) {
                        require(!current.format && length >= 8 && u(raw, offset + 3) == 1,
                                "only a unique Type I format is supported");
                        int rates = u(raw, offset + 7);
                        require(length == (rates == 0 ? 14 : 8 + rates * 3), "invalid Type I frequency list");
                        require(u(raw, offset + 4) > 0 && u(raw, offset + 5) > 0
                                && u(raw, offset + 6) > 0, "invalid Type I format");
                        current.format = true;
                    } else { throw new Refusal("unsupported streaming descriptor subtype"); }
                }
            } else if (type == 5) {
                require(current != null && current.alt != 0 && (length == 7 || length == 9),
                        "endpoint outside a supported active streaming interface");
                int address = u(raw, offset + 2), attributes = u(raw, offset + 3);
                report.append(" endpoint=0x").append(hex(address, 2))
                        .append((address & 0x80) != 0 ? " IN (capture)" : " OUT (playback)")
                        .append(" attributes=0x").append(hex(attributes, 2));
                require((address & 0x0f) != 0 && (address & 0x70) == 0,
                        "invalid endpoint address");
                require((attributes & 3) == 1 && (attributes & 0xf0) == 0,
                        "non-data isochronous or feedback endpoint is unsupported");
                require(length != 9 || (u(raw, offset + 7) == 0 && u(raw, offset + 8) == 0),
                        "synchronized/feedback endpoint linkage is unsupported");
                require(word(raw, offset + 4) > 0, "zero packet size");
                require(current.addresses.add(address), "duplicate endpoint address");
                current.endpoints++;
                int direction = (address & 0x80) != 0 ? 1 : 2;
                current.direction |= direction;
            } else if (type == 0x25) {
                require(current != null && !current.classEndpoint && current.endpoints == 1 && length == 7
                                && u(raw, offset + 2) == 1,
                        "unsupported class-specific endpoint descriptor");
                current.classEndpoint = true;
            } else {
                throw new Refusal("unsupported descriptor type 0x" + hex(type, 2));
            }
            report.append('\n');
            offset += length;
        }
        finishAlt(current);
        require(controlId >= 0 && !streams.containsKey(controlId), "missing or shared control interface");
        require(header && declaredControlBytes == controlBytes, "control descriptor total length mismatch");
        for (int source : entitySources) {
            require(entityIds.contains(source), "audio entity references a missing source");
        }
        require(interfaceIds.size() == expectedInterfaces, "configuration interface count mismatch");
        require(collection.equals(streams.keySet()), "control collection does not exactly cover streams");
        require(streams.size() == 2, "only exactly one playback and one separate capture interface are supported");
        int capture = -1, playback = -1;
        for (Stream stream : streams.values()) {
            boolean zero = false, active = false;
            int direction = 0;
            for (Alt alt : stream.alts) {
                if (alt.alt == 0) { zero = true; continue; }
                active = true;
                direction |= alt.direction;
                Terminal terminal = terminals.get(alt.terminal);
                require(terminal != null && terminal.type == 0x0101,
                        "stream terminal link is not a USB streaming terminal");
                require((alt.direction == 1 && terminal.subtype == 3)
                                || (alt.direction == 2 && terminal.subtype == 2),
                        "stream endpoint direction disagrees with terminal direction");
            }
            require(zero && active, "each stream needs an idle alt zero and active alternate");
            require(direction == 1 || direction == 2, "input and output share one interface across alternates");
            if (direction == 1) { require(capture < 0, "ambiguous capture interfaces"); capture = stream.id; }
            else { require(playback < 0, "ambiguous playback interfaces"); playback = stream.id; }
        }
        require(capture >= 0 && playback >= 0 && capture != playback,
                "separate capture and playback interfaces were not demonstrated");
        return new Result(capture, "Descriptors show separate UAC1 capture-only interface " + capture
                + " and playback interface " + playback + ". Manual experiment only.", report.toString());
    }

    private static void finishAlt(Alt alt) throws Refusal {
        if (alt == null) { return; }
        require(alt.endpoints == alt.expectedEndpoints, "endpoint count mismatch");
        if (alt.alt == 0) {
            require(alt.endpoints == 0, "idle alt zero has endpoints");
        } else {
            require(alt.endpoints == 1 && alt.general && alt.format,
                    "active stream needs exactly one data endpoint, general and format descriptors");
        }
    }

    private static int u(byte[] data, int offset) { return data[offset] & 0xff; }
    private static int word(byte[] data, int offset) { return u(data, offset) | u(data, offset + 1) << 8; }
    private static String hex(int value, int width) {
        return String.format(Locale.ROOT, "%0" + width + "x", value);
    }
    private static void require(boolean condition, String message) throws Refusal {
        if (!condition) { throw new Refusal(message); }
    }
}
