package com.kuberlaxmi.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Sends a print job to whichever printer is available:
 *  1. the TVS / Urovo i9200 built-in thermal printer (android.device.PrinterManager), or
 *  2. a paired Bluetooth (classic SPP) ESC/POS thermal printer.
 * Mode "AUTO" tries the built-in printer first, then every paired printer until one connects.
 */
final class PrinterRouter {
    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    static final class Job {
        Bitmap bmp;
        byte[] raw;
        final int dots;

        Job(Bitmap b, byte[] r, int d) {
            bmp = b;
            raw = r;
            dots = ((d >= 8 ? d : 384) / 8) * 8;
        }

        Bitmap bitmap() {
            if (bmp == null && raw != null) {
                bmp = RasterUtil.fromEscPos(raw);
                if (bmp == null) bmp = RasterUtil.textToBitmap(RasterUtil.asciiOf(raw), 384);
            }
            return bmp;
        }

        byte[] bytes() {
            if (raw != null) return raw;
            return RasterUtil.toEscPos(bmp, dots);
        }
    }

    private final Context ctx;
    private final SharedPreferences sp;

    PrinterRouter(Context c) {
        ctx = c.getApplicationContext();
        sp = ctx.getSharedPreferences("printer", Context.MODE_PRIVATE);
    }

    String getMode() { return sp.getString("mode", "AUTO"); }

    void setMode(String m) { sp.edit().putString("mode", m).apply(); }

    // ---------------------------------------------------------------- built-in (Urovo / TVS i9200)

    boolean builtinAvailable() {
        try {
            Class.forName("android.device.PrinterManager");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int staticInt(Class<?> c, String name, int def) {
        try {
            return c.getField(name).getInt(null);
        } catch (Throwable t) {
            return def;
        }
    }

    private void printBuiltin(Bitmap src) throws Exception {
        Class<?> c = Class.forName("android.device.PrinterManager");
        Object p = c.getConstructor().newInstance();
        Method open = c.getMethod("open");
        Method close = c.getMethod("close");
        int r = ((Number) open.invoke(p)).intValue();
        if (r != 0) throw new Exception("printer open नाही झाला");
        try {
            int st = ((Number) c.getMethod("getStatus").invoke(p)).intValue();
            if (st != staticInt(c, "PRNSTS_OK", 0)) {
                if (st == staticInt(c, "PRNSTS_OUT_OF_PAPER", -1000)) throw new Exception("कागद संपला आहे");
                if (st == staticInt(c, "PRNSTS_OVER_HEAT", -1000)) throw new Exception("printer गरम झाला आहे");
                if (st == staticInt(c, "PRNSTS_UNDER_VOLTAGE", -1000)) throw new Exception("बॅटरी कमी आहे");
                throw new Exception("printer तयार नाही (status " + st + ")");
            }
            Bitmap mono = RasterUtil.toMono(RasterUtil.scaleToWidth(src, 384));
            Method setup = c.getMethod("setupPage", int.class, int.class);
            Method clear = c.getMethod("clearPage");
            Method draw = c.getMethod("drawBitmap", Bitmap.class, int.class, int.class);
            Method print = c.getMethod("printPage", int.class);
            final int SLICE = 1200;
            for (int y = 0; y < mono.getHeight(); y += SLICE) {
                int h = Math.min(SLICE, mono.getHeight() - y);
                Bitmap part = Bitmap.createBitmap(mono, 0, y, mono.getWidth(), h);
                setup.invoke(p, 384, -1);
                clear.invoke(p);
                draw.invoke(p, part, 0, 0);
                print.invoke(p, 0);
            }
            try {
                c.getMethod("paperFeed", int.class).invoke(p, 12);
            } catch (Throwable ignore) {
            }
        } finally {
            try {
                close.invoke(p);
            } catch (Throwable ignore) {
            }
        }
    }

    // ---------------------------------------------------------------- Bluetooth

    boolean btPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    @SuppressLint("MissingPermission")
    List<BluetoothDevice> bondedDevices() {
        List<BluetoothDevice> out = new ArrayList<>();
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null || !btPermission()) return out;
            Set<BluetoothDevice> set = ad.getBondedDevices();
            if (set != null) out.addAll(set);
        } catch (Throwable ignore) {
        }
        return out;
    }

    @SuppressLint("MissingPermission")
    private int score(BluetoothDevice d, String last) {
        int s = 0;
        if (d.getAddress() != null && d.getAddress().equals(last)) s += 100;
        try {
            BluetoothClass bc = d.getBluetoothClass();
            if (bc != null && bc.getMajorDeviceClass() == BluetoothClass.Device.Major.IMAGING) s += 50;
        } catch (Throwable ignore) {
        }
        String n = nameOf(d).toLowerCase(Locale.ROOT);
        if (n.matches(".*(print|pos|mpt|rpp|pt-|thermal|mini|bt|rp|xp|zj|58|80).*")) s += 20;
        return s;
    }

    @SuppressLint("MissingPermission")
    String nameOf(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? d.getAddress() : n;
        } catch (Throwable t) {
            return d.getAddress();
        }
    }

    @SuppressLint("MissingPermission")
    private List<BluetoothDevice> candidates() {
        final String last = sp.getString("last", "");
        List<BluetoothDevice> out = new ArrayList<>();
        for (BluetoothDevice d : bondedDevices()) {
            try {
                BluetoothClass bc = d.getBluetoothClass();
                int major = bc == null ? -1 : bc.getMajorDeviceClass();
                if (major == BluetoothClass.Device.Major.AUDIO_VIDEO
                        || major == BluetoothClass.Device.Major.PHONE
                        || major == BluetoothClass.Device.Major.COMPUTER
                        || major == BluetoothClass.Device.Major.WEARABLE
                        || major == BluetoothClass.Device.Major.HEALTH) continue;
            } catch (Throwable ignore) {
            }
            out.add(d);
        }
        Collections.sort(out, new Comparator<BluetoothDevice>() {
            @Override
            public int compare(BluetoothDevice a, BluetoothDevice b) {
                return score(b, last) - score(a, last);
            }
        });
        return out;
    }

    @SuppressLint("MissingPermission")
    private void sendBt(BluetoothDevice d, byte[] data) throws Exception {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad != null) ad.cancelDiscovery();
        BluetoothSocket s = null;
        try {
            s = d.createRfcommSocketToServiceRecord(SPP);
            s.connect();
        } catch (IOException first) {
            try {
                if (s != null) s.close();
            } catch (Exception ignore) {
            }
            Method m = d.getClass().getMethod("createRfcommSocket", int.class);
            s = (BluetoothSocket) m.invoke(d, 1);
            s.connect();
        }
        try {
            OutputStream os = s.getOutputStream();
            final int CHUNK = 1024;
            for (int i = 0; i < data.length; i += CHUNK) {
                os.write(data, i, Math.min(CHUNK, data.length - i));
                os.flush();
                Thread.sleep(12);
            }
            Thread.sleep(400);
        } finally {
            try {
                s.close();
            } catch (Exception ignore) {
            }
        }
    }

    // ---------------------------------------------------------------- public

    synchronized String print(Job job) {
        String mode = getMode();
        String builtinErr = null;

        if (mode.equals("AUTO") || mode.equals("BUILTIN")) {
            if (builtinAvailable()) {
                try {
                    Bitmap b = job.bitmap();
                    if (b == null) throw new Exception("चित्र तयार झाले नाही");
                    printBuiltin(b);
                    return "✅ प्रिंट झाले (TVS built-in printer)";
                } catch (Throwable t) {
                    builtinErr = msg(t);
                    if (mode.equals("BUILTIN")) return "❌ Built-in printer: " + builtinErr;
                }
            } else if (mode.equals("BUILTIN")) {
                return "❌ या डिव्हाइसवर built-in printer सापडला नाही";
            }
        }

        if (!btPermission()) return "❌ Bluetooth परवानगी द्या (App Settings) आणि पुन्हा प्रिंट करा";
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) return "❌ या डिव्हाइसमध्ये Bluetooth नाही";
        if (!ad.isEnabled()) return "❌ Bluetooth चालू करा आणि पुन्हा प्रिंट करा";

        List<BluetoothDevice> list = new ArrayList<>();
        if (mode.startsWith("BT:")) {
            try {
                list.add(ad.getRemoteDevice(mode.substring(3)));
            } catch (Throwable t) {
                return "❌ निवडलेला printer सापडला नाही";
            }
        } else {
            list = candidates();
        }
        if (list.isEmpty()) {
            return "❌ कोणताही Bluetooth printer pair केलेला नाही. आधी फोनच्या Bluetooth Settings मध्ये printer pair करा.";
        }

        byte[] data;
        try {
            data = job.bytes();
        } catch (Throwable t) {
            return "❌ प्रिंट तयार झाले नाही";
        }
        String lastErr = "";
        for (BluetoothDevice d : list) {
            try {
                sendBt(d, data);
                sp.edit().putString("last", d.getAddress()).apply();
                return "✅ प्रिंट झाले (" + nameOf(d) + ")";
            } catch (Throwable t) {
                lastErr = nameOf(d) + ": " + msg(t);
            }
        }
        return "❌ प्रिंट झाले नाही – printer चालू आणि जवळ आहे का? (" + lastErr + ")" + (builtinErr != null ? " | Built-in: " + builtinErr : "");
    }

    String currentLabel() {
        String m = getMode();
        if (m.equals("AUTO")) return "Auto";
        if (m.equals("BUILTIN")) return "Built-in";
        return "Bluetooth";
    }

    private static String msg(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : m;
    }
}
