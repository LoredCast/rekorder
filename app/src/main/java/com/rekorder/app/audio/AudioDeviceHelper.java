// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * Discovers physical microphone and USB audio capture devices and probes
 * Android's HAL unprocessed-audio capability properties.
 */
public final class AudioDeviceHelper {

    public static final class InputDeviceItem {
        public final int id;
        public final String label;
        public final String detail;
        public final AudioDeviceInfo info; // null for Default

        public InputDeviceItem(int id, String label, String detail, AudioDeviceInfo info) {
            this.id = id;
            this.label = label;
            this.detail = detail;
            this.info = info;
        }
    }

    public static final class OutputDeviceItem {
        public final int id;
        public final String label;
        public final String detail;
        public final AudioDeviceInfo info; // null for System Default

        public OutputDeviceItem(int id, String label, String detail, AudioDeviceInfo info) {
            this.id = id;
            this.label = label;
            this.detail = detail;
            this.info = info;
        }
    }

    public static final class HalDiagnostics {
        public final boolean supportsUnprocessed;
        public final String nativeSampleRate;
        public final String framesPerBuffer;
        public final boolean aaudioAvailable;

        public HalDiagnostics(boolean supportsUnprocessed, String nativeSampleRate,
                              String framesPerBuffer, boolean aaudioAvailable) {
            this.supportsUnprocessed = supportsUnprocessed;
            this.nativeSampleRate = nativeSampleRate;
            this.framesPerBuffer = framesPerBuffer;
            this.aaudioAvailable = aaudioAvailable;
        }
    }

    private AudioDeviceHelper() {}

    public static HalDiagnostics probeHal(Context ctx) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        boolean unproc = false;
        String rate = "?";
        String fpb = "?";
        if (am != null) {
            String u = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED);
            unproc = "true".equalsIgnoreCase(u);
            String r = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            if (r != null && !r.isEmpty()) rate = r + " Hz";
            String f = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            if (f != null && !f.isEmpty()) fpb = f + " frames";
        }
        return new HalDiagnostics(unproc, rate, fpb, NativeAAudioEngine.isNativeAvailable());
    }

    public static List<InputDeviceItem> listInputDevices(Context ctx) {
        List<InputDeviceItem> list = new ArrayList<>();
        list.add(new InputDeviceItem(0, "Default Hardware Input", "Automatic primary mic / USB", null));

        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return list;

        AudioDeviceInfo[] devs = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
        int micIndex = 1;
        for (AudioDeviceInfo d : devs) {
            int type = d.getType();
            if (type == AudioDeviceInfo.TYPE_TELEPHONY
                    || type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX
                    || type == AudioDeviceInfo.TYPE_FM_TUNER) {
                continue;
            }
            String typeName = describeDeviceType(type);
            String addr = "";
            if (Build.VERSION.SDK_INT >= 28) {
                String a = d.getAddress();
                if (a != null && !a.isEmpty() && !"0".equals(a)) {
                    addr = " (" + a + ")";
                }
            }
            CharSequence prod = d.getProductName();
            String label;
            if (type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                label = "USB: " + (prod != null && prod.length() > 0 ? prod : typeName);
            } else if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                label = "Built-in Mic #" + (micIndex++) + addr;
            } else {
                label = typeName + (prod != null && prod.length() > 0 ? " · " + prod : "");
            }

            StringBuilder det = new StringBuilder();
            int[] chCounts = d.getChannelCounts();
            if (chCounts != null && chCounts.length > 0) {
                int maxCh = 1;
                for (int c : chCounts) if (c > maxCh) maxCh = c;
                det.append("up to ").append(maxCh).append("ch");
            }
            int[] rates = d.getSampleRates();
            if (rates != null && rates.length > 0) {
                int maxRate = 0;
                for (int r : rates) if (r > maxRate) maxRate = r;
                if (det.length() > 0) det.append(" · ");
                det.append("max ").append(AudioConfig.formatSampleRate(maxRate));
            }
            if (det.length() == 0) {
                det.append("ID ").append(d.getId());
            }
            list.add(new InputDeviceItem(d.getId(), label, det.toString(), d));
        }
        return list;
    }

    public static AudioDeviceInfo findDeviceById(Context ctx, int deviceId) {
        if (deviceId <= 0) return null;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return null;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (d.getId() == deviceId) return d;
        }
        return null;
    }

    public static List<OutputDeviceItem> listOutputDevices(Context ctx) {
        List<OutputDeviceItem> list = new ArrayList<>();
        list.add(new OutputDeviceItem(0, "System Default Output",
                "Automatic Android routing (speaker / headphones / USB / BT)", null));

        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return list;

        AudioDeviceInfo[] devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo d : devs) {
            int type = d.getType();
            if (type == AudioDeviceInfo.TYPE_TELEPHONY
                    || type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX
                    || type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    || type == AudioDeviceInfo.TYPE_BUS) {
                continue;
            }
            CharSequence prod = d.getProductName();
            String prodStr = (prod != null && prod.length() > 0) ? prod.toString() : "";
            String label;
            if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                label = "Phone Speaker";
            } else if (type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                label = "Wired Headphones" + (!prodStr.isEmpty() ? " · " + prodStr : "");
            } else if (type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                label = "USB Out: " + (!prodStr.isEmpty() ? prodStr : describeOutputType(type));
            } else if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || (Build.VERSION.SDK_INT >= 31 && (type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    || type == AudioDeviceInfo.TYPE_BLE_SPEAKER))) {
                label = "Bluetooth: " + (!prodStr.isEmpty() ? prodStr : "Audio");
            } else {
                label = describeOutputType(type) + (!prodStr.isEmpty() ? " · " + prodStr : "");
            }

            StringBuilder det = new StringBuilder();
            int[] chCounts = d.getChannelCounts();
            if (chCounts != null && chCounts.length > 0) {
                int maxCh = 1;
                for (int c : chCounts) if (c > maxCh) maxCh = c;
                det.append("up to ").append(maxCh).append("ch");
            }
            int[] rates = d.getSampleRates();
            if (rates != null && rates.length > 0) {
                int maxRate = 0;
                for (int r : rates) if (r > maxRate) maxRate = r;
                if (det.length() > 0) det.append(" · ");
                det.append("max ").append(AudioConfig.formatSampleRate(maxRate));
            }
            if (det.length() == 0) {
                det.append("ID ").append(d.getId());
            }
            list.add(new OutputDeviceItem(d.getId(), label, det.toString(), d));
        }
        return list;
    }

    public static AudioDeviceInfo findOutputDeviceById(Context ctx, int deviceId) {
        return findOutputDevice(ctx, deviceId, 0);
    }

    /**
     * Resolves the selected output sink by exact ID first, falling back to device type
     * (so e.g. "Phone Speaker" stays pinned across phone reboots or USB plug/unplug).
     */
    public static AudioDeviceInfo findOutputDevice(Context ctx, int deviceId, int deviceType) {
        if (deviceId <= 0 && deviceType <= 0) return null;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return null;
        AudioDeviceInfo[] devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        if (deviceId > 0) {
            for (AudioDeviceInfo d : devs) {
                if (d.getId() == deviceId) return d;
            }
        }
        if (deviceType > 0) {
            for (AudioDeviceInfo d : devs) {
                if (d.getType() == deviceType) return d;
            }
        }
        return null;
    }

    public static boolean isHeadphoneOutput(AudioDeviceInfo d) {
        if (d == null) return false;
        int t = d.getType();
        return t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                || t == AudioDeviceInfo.TYPE_USB_HEADSET
                || t == AudioDeviceInfo.TYPE_USB_DEVICE
                || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET);
    }

    /**
     * Resolves a safe (non-speaker) output device for live microphone monitoring:
     * honors the user's selected output device if it is headphones/USB/BT; returns null if speaker
     * is selected (to prevent loud acoustic feedback) or if no headphones are connected.
     */
    public static AudioDeviceInfo resolveSafeMonitorOutput(Context ctx, int preferredOutputDeviceId,
                                                           int preferredOutputDeviceType) {
        if (preferredOutputDeviceId > 0 || preferredOutputDeviceType > 0) {
            AudioDeviceInfo chosen = findOutputDevice(ctx, preferredOutputDeviceId, preferredOutputDeviceType);
            if (chosen != null) {
                return isHeadphoneOutput(chosen) ? chosen : null;
            }
        }
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return null;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (isHeadphoneOutput(d)) return d;
        }
        return null;
    }

    public static boolean hasHeadphonesConnected(Context ctx) {
        return resolveSafeMonitorOutput(ctx, 0, 0) != null;
    }

    public static boolean isBluetoothInput(AudioDeviceInfo d) {
        if (d == null) return false;
        int t = d.getType();
        return t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET);
    }

    /**
     * Activates Bluetooth HFP/SCO or BLE Headset mic routing before capture opens.
     * Uses setCommunicationDevice() on API 31+ and startBluetoothSco() on API 26-30.
     * Returns true if Bluetooth routing was activated and must be cleared on stop.
     */
    @SuppressWarnings("deprecation")
    public static boolean activateBluetoothMic(Context ctx, AudioDeviceInfo inputDev) {
        if (ctx == null || !isBluetoothInput(inputDev)) return false;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                // Find matching communication device (SCO / BLE headset)
                AudioDeviceInfo match = null;
                for (AudioDeviceInfo c : am.getAvailableCommunicationDevices()) {
                    if (c.getType() == inputDev.getType()) {
                        match = c;
                        if (c.getAddress() != null && c.getAddress().equals(inputDev.getAddress())) {
                            break;
                        }
                    }
                }
                if (match != null && am.setCommunicationDevice(match)) {
                    try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                    return true;
                }
            } else {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                am.startBluetoothSco();
                am.setBluetoothScoOn(true);
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Cleanly tears down Bluetooth HFP/SCO mic routing after recording stops. */
    @SuppressWarnings("deprecation")
    public static void deactivateBluetoothMic(Context ctx) {
        if (ctx == null) return;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice();
            } else {
                am.setBluetoothScoOn(false);
                am.stopBluetoothSco();
                am.setMode(AudioManager.MODE_NORMAL);
            }
        } catch (Throwable ignored) {
        }
    }

    public static String describeOutputType(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:  return "Phone Speaker";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "Wired Headphones";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:    return "Wired Headset";
            case AudioDeviceInfo.TYPE_USB_DEVICE:       return "USB Audio Output";
            case AudioDeviceInfo.TYPE_USB_HEADSET:      return "USB Headset";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:   return "Bluetooth Audio";
            case AudioDeviceInfo.TYPE_LINE_ANALOG:      return "Analog Line-Out";
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:     return "Digital Line-Out";
            case AudioDeviceInfo.TYPE_HDMI:             return "HDMI Audio";
            default:                                    return "Audio Output (" + type + ")";
        }
    }

    public static String describeDeviceType(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:      return "Built-in Mic";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:    return "Wired Headset Mic";
            case AudioDeviceInfo.TYPE_USB_DEVICE:       return "USB Audio Interface";
            case AudioDeviceInfo.TYPE_USB_HEADSET:      return "USB Headset Mic";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:    return "Bluetooth Mic";
            case AudioDeviceInfo.TYPE_LINE_ANALOG:      return "Analog Line-In";
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:     return "Digital Line-In";
            default:                                    return "Audio Input (" + type + ")";
        }
    }
}
