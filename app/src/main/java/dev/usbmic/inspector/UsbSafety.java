package dev.usbmic.inspector;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import java.security.MessageDigest;

/** Cross-check the public framework view independently of the raw-descriptor gate. */
final class UsbSafety {
    private UsbSafety() {}

    static UsbInterface captureInterface(UsbDevice device, int id) {
        if (device.getConfigurationCount() != 1 || id < 0) return null;
        UsbInterface selected = null;
        boolean captureData = false;
        boolean separatePlayback = false;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getId() == id) {
                if (intf.getInterfaceClass() != UsbConstants.USB_CLASS_AUDIO
                        || intf.getInterfaceSubclass() != 2 || intf.getInterfaceProtocol() != 0) return null;
                if (selected == null || intf.getAlternateSetting() == 0) selected = intf;
                for (int e = 0; e < intf.getEndpointCount(); e++) {
                    UsbEndpoint endpoint = intf.getEndpoint(e);
                    if (endpoint.getDirection() != UsbConstants.USB_DIR_IN
                            || endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_ISOC
                            || (endpoint.getAttributes() & 0x30) != 0) return null;
                    captureData = true;
                }
            } else if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO
                    && intf.getInterfaceSubclass() == 2) {
                for (int e = 0; e < intf.getEndpointCount(); e++) {
                    UsbEndpoint endpoint = intf.getEndpoint(e);
                    if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT
                            && endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC) separatePlayback = true;
                }
            }
        }
        return captureData && separatePlayback ? selected : null;
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
