package com.kuberlaxmi.app;

import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Exposed to the web page as window.AndroidPrint. */
final class PrintBridge {
    private final MainActivity act;
    private final PrinterRouter router;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    PrintBridge(MainActivity a, PrinterRouter r) {
        act = a;
        router = r;
    }

    private void say(final String m) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(act, m, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void submit(final PrinterRouter.Job job) {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    say(router.print(job));
                } catch (Throwable t) {
                    say("❌ प्रिंट झाले नाही");
                }
            }
        });
    }

    /** Called by MainActivity when the web page sends ESC/POS bytes (intent:base64 URL). */
    void printRaw(byte[] data) {
        submit(new PrinterRouter.Job(null, data, 384));
    }

    @JavascriptInterface
    public void printPng(String b64, int dots) {
        try {
            byte[] d = Base64.decode(b64, Base64.DEFAULT);
            Bitmap b = BitmapFactory.decodeByteArray(d, 0, d.length);
            if (b == null) {
                say("❌ चित्र तयार झाले नाही");
                return;
            }
            submit(new PrinterRouter.Job(b, null, dots));
        } catch (Throwable t) {
            say("❌ प्रिंट झाले नाही");
        }
    }

    @JavascriptInterface
    public void printText(String text) {
        submit(new PrinterRouter.Job(RasterUtil.textToBitmap(text == null ? "" : text, 384), null, 384));
    }

    @JavascriptInterface
    public void toast(String m) {
        say(m);
    }

    @JavascriptInterface
    public void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Throwable t) {
            say("लिंक उघडता आली नाही");
        }
    }

    @JavascriptInterface
    public void choosePrinter() {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showPicker();
            }
        });
    }

    private void showPicker() {
        final List<String> labels = new ArrayList<>();
        final List<String> values = new ArrayList<>();
        labels.add("🔄 Auto (जो printer connected असेल तो)");
        values.add("AUTO");
        labels.add("🧾 TVS i9200 Built-in printer" + (router.builtinAvailable() ? "  ✅ सापडला" : "  (या डिव्हाइसवर नाही)"));
        values.add("BUILTIN");
        for (BluetoothDevice d : router.bondedDevices()) {
            labels.add("📶 " + router.nameOf(d) + "  [" + d.getAddress() + "]");
            values.add("BT:" + d.getAddress());
        }
        labels.add("🧪 Test Print (सध्याच्या निवडीने)");
        values.add("TEST");

        new AlertDialog.Builder(act)
                .setTitle("Printer निवडा (सध्या: " + router.currentLabel() + ")")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String v = values.get(which);
                        if (v.equals("TEST")) {
                            String t = "KUBERLAXMI\nTEST PRINT\n"
                                    + new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US).format(new Date())
                                    + "\n1234567890\n--------------------------------\n";
                            submit(new PrinterRouter.Job(RasterUtil.textToBitmap(t, 384), null, 384));
                        } else {
                            router.setMode(v);
                            say("Printer निवड सेव्ह झाली");
                        }
                    }
                })
                .setNegativeButton("बंद करा", null)
                .show();
    }
}
