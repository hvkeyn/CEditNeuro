package com.hvkeyn.ceditneuro.background;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import java.io.FileOutputStream;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

/**
 * Runs through app_process as the shell user. It creates a public, off-screen display
 * so apps can be launched with `am start --display` without taking over the phone screen.
 */
public final class AgentDisplayMain {
    private AgentDisplayMain() {}

    public static void main(String[] args) {
        int width = clamp(parse(args, 0, 1080), 480, 4096);
        int height = clamp(parse(args, 1, 2400), 480, 4096);
        int dpi = clamp(parse(args, 2, 420), 120, 640);
        File out = new File("/data/local/tmp/ceditneuro-agent-display.id");
        File log = new File("/data/local/tmp/ceditneuro-agent-display.log");
        File frame = new File("/data/local/tmp/ceditneuro-agent-frame.png");
        File frameTmp = new File("/data/local/tmp/ceditneuro-agent-frame.tmp");
        VirtualDisplay display = null;
        ImageReader reader = null;
        try {
            android.os.Looper.prepareMainLooper();
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            Context system = (Context) at.getMethod("getSystemContext").invoke(thread);
            Context context = system;
            try {
                context = system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
            } catch (Throwable ignored) { }
            DisplayManager manager = context.getSystemService(DisplayManager.class);
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            display = manager.createVirtualDisplay(
                    "CEditNeuro Agent", width, height, dpi, reader.getSurface(), flags);
            ImageReader frameReader = reader;
            Thread drain = new Thread(() -> {
                while (true) {
                    try {
                        Image image = frameReader.acquireLatestImage();
                        if (image != null) {
                            try {
                                int stride = image.getPlanes()[0].getRowStride()
                                        / image.getPlanes()[0].getPixelStride();
                                Bitmap padded = Bitmap.createBitmap(stride, height, Bitmap.Config.ARGB_8888);
                                padded.copyPixelsFromBuffer(image.getPlanes()[0].getBuffer().rewind());
                                Bitmap upright = Bitmap.createBitmap(padded, 0, 0, width, height);
                                try (FileOutputStream stream = new FileOutputStream(frameTmp)) {
                                    upright.compress(Bitmap.CompressFormat.PNG, 90, stream);
                                }
                                frameTmp.renameTo(frame);
                                upright.recycle();
                                padded.recycle();
                            } finally {
                                image.close();
                            }
                        }
                        Thread.sleep(700L);
                    } catch (Throwable ignored) { }
                }
            }, "agent-display-drain");
            drain.setDaemon(true);
            drain.start();
            int id = display.getDisplay().getDisplayId();
            write(out, Integer.toString(id));
            append(log, "display=" + id + " " + width + "x" + height + " dpi=" + dpi + "\n");
            while (true) Thread.sleep(1000L);
        } catch (Throwable error) {
            append(log, "error=" + error.getClass().getName() + ": " + error.getMessage() + "\n");
            try (PrintWriter writer = new PrintWriter(new FileWriter(log, true))) {
                error.printStackTrace(writer);
                if (error.getCause() != null) error.getCause().printStackTrace(writer);
                writer.flush();
            } catch (Exception ignored) { }
            write(out, "-1");
            if (display != null) display.release();
        }
    }

    private static int parse(String[] args, int index, int fallback) {
        try { return Integer.parseInt(args[index]); } catch (Exception ignored) { return fallback; }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void write(File file, String text) {
        try (FileWriter writer = new FileWriter(file, false)) { writer.write(text); }
        catch (Exception ignored) { }
    }

    private static void append(File file, String text) {
        try (FileWriter writer = new FileWriter(file, true)) { writer.write(text); }
        catch (Exception ignored) { }
    }
}
