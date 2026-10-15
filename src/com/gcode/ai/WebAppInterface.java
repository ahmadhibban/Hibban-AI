package com.gcode.ai;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebAppInterface {
    private final Context context;
    private final SharedPreferences prefs;
    private volatile boolean isAppInForeground = true;

    public void setAppInForeground(boolean inForeground) {
        this.isAppInForeground = inForeground;
    }

    @JavascriptInterface
    public boolean isAppInForeground() {
        return this.isAppInForeground;
    }

    public WebAppInterface(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("gcode_prefs", Context.MODE_PRIVATE);
    }

    private File resolveFile(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return Environment.getExternalStorageDirectory();
        }
        String cleanPath = filePath.trim();
        if (cleanPath.startsWith("file://")) {
            cleanPath = cleanPath.substring(7);
        }
        File f = new File(cleanPath);
        if (f.isAbsolute()) {
            return f;
        }
        File f1 = new File(Environment.getExternalStorageDirectory(), cleanPath);
        if (f1.exists()) {
            return f1;
        }
        File f2 = new File(new File(Environment.getExternalStorageDirectory(), "Download"), cleanPath);
        if (f2.exists()) {
            return f2;
        }
        File f3 = new File(context.getFilesDir(), cleanPath);
        if (f3.exists()) {
            return f3;
        }
        return f1;
    }

    @JavascriptInterface
    public String getStorageDirectory() {
        try {
            return Environment.getExternalStorageDirectory().getAbsolutePath();
        } catch (Exception e) {
            return "/storage/emulated/0";
        }
    }

    @JavascriptInterface
    public String getDownloadDirectory() {
        try {
            File dl = new File(Environment.getExternalStorageDirectory(), "Download");
            return dl.getAbsolutePath();
        } catch (Exception e) {
            return "/storage/emulated/0/Download";
        }
    }

    @JavascriptInterface
    public boolean fileExists(String filePath) {
        if (filePath == null) return false;
        try {
            return resolveFile(filePath).exists();
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public String readImageBase64(String filePath, int maxWidth, int maxHeight) {
        JSONObject result = new JSONObject();
        try {
            File file = resolveFile(filePath);
            if (!file.exists() || !file.isFile()) {
                result.put("success", false);
                result.put("error", "Image file does not exist: " + filePath);
                return result.toString();
            }

            int reqWidth = (maxWidth > 0) ? maxWidth : 1280;
            int reqHeight = (maxHeight > 0) ? maxHeight : 1280;

            BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
            boundsOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), boundsOptions);

            int origWidth = boundsOptions.outWidth;
            int origHeight = boundsOptions.outHeight;

            if (origWidth <= 0 || origHeight <= 0) {
                result.put("success", false);
                result.put("error", "Failed to decode image dimensions for: " + file.getAbsolutePath());
                return result.toString();
            }

            int inSampleSize = 1;
            while ((origWidth / (inSampleSize * 2)) >= reqWidth && (origHeight / (inSampleSize * 2)) >= reqHeight) {
                inSampleSize *= 2;
            }

            BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
            decodeOptions.inSampleSize = inSampleSize;
            decodeOptions.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap sampledBitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), decodeOptions);

            if (sampledBitmap == null) {
                result.put("success", false);
                result.put("error", "Failed to decode bitmap from: " + file.getAbsolutePath());
                return result.toString();
            }

            Bitmap finalBitmap = sampledBitmap;
            int currentW = sampledBitmap.getWidth();
            int currentH = sampledBitmap.getHeight();

            if (currentW > reqWidth || currentH > reqHeight) {
                float ratio = Math.min((float) reqWidth / currentW, (float) reqHeight / currentH);
                int targetW = Math.max(1, Math.round(currentW * ratio));
                int targetH = Math.max(1, Math.round(currentH * ratio));
                finalBitmap = Bitmap.createScaledBitmap(sampledBitmap, targetW, targetH, true);
                if (finalBitmap != sampledBitmap) {
                    sampledBitmap.recycle();
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            byte[] imageBytes = baos.toByteArray();
            baos.close();
            finalBitmap.recycle();

            String base64Str = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

            result.put("success", true);
            result.put("mimeType", "image/jpeg");
            result.put("base64", base64Str);
            result.put("width", finalBitmap.getWidth());
            result.put("height", finalBitmap.getHeight());
            result.put("sizeBytes", imageBytes.length);
        } catch (Throwable e) {
            try {
                result.put("success", false);
                result.put("error", "Error reading image: " + e.getMessage());
            } catch (Exception ignored) {}
        }
        return result.toString();
    }

    @JavascriptInterface
    public String readFileBase64(String filePath) {
        JSONObject result = new JSONObject();
        try {
            File file = resolveFile(filePath);
            if (!file.exists()) {
                result.put("success", false);
                result.put("error", "File does not exist: " + filePath);
                return result.toString();
            }
            if (file.length() > 10 * 1024 * 1024) {
                result.put("success", false);
                result.put("error", "File exceeds 10MB limit");
                return result.toString();
            }

            byte[] buffer = new byte[(int) file.length()];
            FileInputStream fis = new FileInputStream(file);
            int read = 0;
            int offset = 0;
            while (offset < buffer.length && (read = fis.read(buffer, offset, buffer.length - offset)) >= 0) {
                offset += read;
            }
            fis.close();

            String b64 = Base64.encodeToString(buffer, Base64.NO_WRAP);
            result.put("success", true);
            result.put("base64", b64);
            result.put("sizeBytes", buffer.length);
        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return result.toString();
    }

    /**
     * High-speed native file downloader. Downloads files directly (APKs, archives, models, etc.)
     * from any HTTP/HTTPS URL into storage without depending on external tools.
     */
    @JavascriptInterface
    public String downloadFile(String fileUrl, String targetPath) {
        JSONObject result = new JSONObject();
        if (fileUrl == null || fileUrl.trim().isEmpty()) {
            try {
                result.put("success", false);
                result.put("error", "URL cannot be empty.");
            } catch (Exception ignored) {}
            return result.toString();
        }
        try {
            File dest = resolveFile(targetPath);
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            // Follow redirects up to 8 times
            String curUrl = fileUrl.trim();
            HttpURLConnection conn = null;
            for (int r = 0; r < 8; r++) {
                URL u = new URL(curUrl);
                conn = (HttpURLConnection) u.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
                conn.setRequestProperty("Accept", "*/*");

                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc != null) {
                        if (loc.startsWith("/")) {
                            URL old = new URL(curUrl);
                            curUrl = old.getProtocol() + "://" + old.getHost() + loc;
                        } else {
                            curUrl = loc;
                        }
                        continue;
                    }
                }
                break;
            }

            if (conn == null) {
                result.put("success", false);
                result.put("error", "Failed to connect to server.");
                return result.toString();
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) {
                conn.disconnect();
                result.put("success", false);
                result.put("error", "HTTP error " + code + " from server.");
                return result.toString();
            }

            long total = conn.getContentLengthLong();
            try (InputStream in = new BufferedInputStream(conn.getInputStream(), 65536);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(dest), 65536)) {
                byte[] buf = new byte[65536];
                int read;
                long written = 0;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    written += read;
                }
                out.flush();
                result.put("success", true);
                result.put("bytesDownloaded", written);
                result.put("path", dest.getAbsolutePath());
                result.put("message", "File successfully downloaded (" + (written / (1024 * 1024)) + " MB) to " + dest.getAbsolutePath());
            } finally {
                conn.disconnect();
            }
        } catch (Throwable e) {
            try {
                result.put("success", false);
                result.put("error", "Download failed: " + e.getMessage());
            } catch (Exception ignored) {}
        }
        return result.toString();
    }

    /**
     * Executes shell commands in the 100% STANDALONE embedded Linux environment.
     * Runs directly via user-space PRoot with zero external Termux dependencies.
     */
    @JavascriptInterface
    public String executeCommand(String command, String workingDir) {
        JSONObject result = new JSONObject();
        StringBuilder output = new StringBuilder();
        int exitCode = -1;

        File filesDir = context.getFilesDir();
        File usrDir = new File(filesDir, "usr");
        File homeDir = new File(filesDir, "home");
        File tmpDir = new File(usrDir, "tmp");
        if (!homeDir.exists()) homeDir.mkdirs();
        if (!tmpDir.exists()) tmpDir.mkdirs();

        // Ensure resolv.conf exists for flawless DNS inside PRoot
        File etcDir = new File(usrDir, "etc");
        if (!etcDir.exists()) etcDir.mkdirs();
        File resolvConf = new File(etcDir, "resolv.conf");
        if (!resolvConf.exists() || resolvConf.length() == 0) {
            try (FileOutputStream fos = new FileOutputStream(resolvConf)) {
                fos.write("nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\nnameserver 1.0.0.1\n".getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }

        // Determine working directory inside guest proot and on host
        File hostDir = homeDir;
        String guestWorkDir = homeDir.getAbsolutePath();

        if (workingDir != null && !workingDir.trim().isEmpty()) {
            String wd = workingDir.trim();
            if (wd.startsWith("/storage/") || wd.startsWith("/sdcard/")) {
                File target = new File(wd);
                if (target.exists() && target.isDirectory()) {
                    hostDir = target;
                    guestWorkDir = target.getAbsolutePath();
                } else {
                    hostDir = homeDir;
                    guestWorkDir = wd;
                }
            } else if (wd.startsWith(usrDir.getAbsolutePath()) || wd.contains("/files/usr")) {
                hostDir = usrDir;
                guestWorkDir = usrDir.getAbsolutePath();
            } else if (wd.startsWith(homeDir.getAbsolutePath()) || wd.contains("/files/home") || wd.equals("~")) {
                hostDir = homeDir;
                guestWorkDir = homeDir.getAbsolutePath();
            } else {
                File target = new File(wd);
                if (target.exists() && target.isDirectory()) {
                    hostDir = target;
                    guestWorkDir = target.getAbsolutePath();
                } else {
                    hostDir = homeDir;
                    guestWorkDir = homeDir.getAbsolutePath();
                }
            }
        }

        try {
            File prootBin = new File(usrDir, "bin/proot");
            File bashBin = new File(usrDir, "bin/bash");

            List<String> cmdList = new ArrayList<>();
            if (bashBin.exists()) {
                cmdList.add(bashBin.getAbsolutePath());
                cmdList.add("-c");
                cmdList.add(command);
            } else if (prootBin.exists()) {
                cmdList.add(prootBin.getAbsolutePath());
                cmdList.add("-0");
                cmdList.add("-b"); cmdList.add("/dev");
                cmdList.add("-b"); cmdList.add("/proc");
                File sys = new File("/system");
                if (sys.exists()) { cmdList.add("-b"); cmdList.add("/system"); }
                File vendor = new File("/vendor");
                if (vendor.exists()) { cmdList.add("-b"); cmdList.add("/vendor"); }
                cmdList.add("-w"); cmdList.add(guestWorkDir);
                cmdList.add("/system/bin/sh");
                cmdList.add("-c");
                cmdList.add(command);
            } else {
                cmdList.add("/system/bin/sh");
                cmdList.add("-c");
                cmdList.add(command);
            }

            ProcessBuilder pb = new ProcessBuilder(cmdList);
            pb.directory(hostDir.exists() ? hostDir : filesDir);

            Map<String, String> env = pb.environment();
            String appLibDir = new File(usrDir, "lib").getAbsolutePath();
            String appBinDir = new File(usrDir, "bin").getAbsolutePath();
            env.put("PATH", appBinDir + ":/system/bin:/system/xbin");
            env.put("LD_LIBRARY_PATH", appLibDir + ":/system/lib64:/vendor/lib64");
            env.put("PREFIX", usrDir.getAbsolutePath());
            env.put("HOME", homeDir.getAbsolutePath());
            env.put("TMPDIR", tmpDir.getAbsolutePath());
            env.put("PROOT_TMP_DIR", tmpDir.getAbsolutePath());
            env.put("TERM", "xterm-256color");
            env.put("LANG", "en_US.UTF-8");
            env.put("SHELL", bashBin.exists() ? bashBin.getAbsolutePath() : "/system/bin/sh");
            env.put("OPENSSL_CONF", new File(usrDir, "etc/tls/openssl.cnf").getAbsolutePath());
            File certPem = new File(usrDir, "etc/tls/cert.pem");
            if (certPem.exists()) {
                env.put("SSL_CERT_FILE", certPem.getAbsolutePath());
            }
            env.put("PYTHONHOME", usrDir.getAbsolutePath());
            env.put("PYTHONPATH", new File(usrDir, "lib/python3.14/site-packages").getAbsolutePath());
            env.put("DEBIAN_FRONTEND", "noninteractive");
            env.put("OPENAI_BASE_URL", "http://127.0.0.1:20128/v1");
            env.put("OPENAI_API_BASE", "http://127.0.0.1:20128/v1");
            env.put("OPENAI_API_KEY", "sk-5f238e76072d7926-d5c648-d2fcfe6e");
            env.put("AIDER_MODEL", "openai/auto/best-free");
            env.put("NODE_PATH", new File(usrDir, "lib/node_modules").getAbsolutePath());

            pb.redirectErrorStream(true);
            Process process = pb.start();

            try {
                process.getOutputStream().close();
            } catch (Exception ignored) {}

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            );

            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < 120000) {
                    output.append(line).append("\n");
                }
            }
            reader.close();

            boolean finished = process.waitFor(180, TimeUnit.SECONDS);
            if (finished) {
                exitCode = process.exitValue();
            } else {
                process.destroyForcibly();
                output.append("\n[Command timed out after 180 seconds]");
                exitCode = -1;
            }

            result.put("success", exitCode == 0);
            result.put("stdout", output.toString());
            result.put("stderr", "");
            result.put("exitCode", exitCode);
            return result.toString();
        } catch (Throwable e) {
            try {
                result.put("success", false);
                result.put("stdout", output.toString());
                result.put("stderr", "Execution error: " + e.getMessage());
                result.put("exitCode", -1);
            } catch (Exception ignored) {}
            return result.toString();
        }
    }

    @JavascriptInterface
    public String readFile(String filePath, int startLine, int endLine) {
        JSONObject result = new JSONObject();
        try {
            File file = resolveFile(filePath);
            if (!file.exists()) {
                result.put("success", false);
                result.put("error", "File does not exist: " + filePath);
                return result.toString();
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)
            );

            StringBuilder content = new StringBuilder();
            String line;
            int currentLine = 1;
            int actualStart = (startLine > 0) ? startLine : 1;
            int actualEnd = (endLine > 0) ? endLine : Integer.MAX_VALUE;

            while ((line = reader.readLine()) != null) {
                if (currentLine >= actualStart && currentLine <= actualEnd) {
                    content.append(currentLine).append(": ").append(line).append("\n");
                }
                currentLine++;
            }
            reader.close();

            result.put("success", true);
            result.put("content", content.toString());
            result.put("totalLines", currentLine - 1);
        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return result.toString();
    }

    @JavascriptInterface
    public String writeFile(String filePath, String content) {
        JSONObject result = new JSONObject();
        try {
            File file = resolveFile(filePath);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            FileOutputStream fos = new FileOutputStream(file, false);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();

            result.put("success", true);
            result.put("bytesWritten", content.length());
            result.put("path", file.getAbsolutePath());
        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return result.toString();
    }

    @JavascriptInterface
    public String editFile(String filePath, String targetText, String replacementText) {
        JSONObject result = new JSONObject();
        try {
            File file = resolveFile(filePath);
            if (!file.exists()) {
                result.put("success", false);
                result.put("error", "File not found: " + filePath);
                return result.toString();
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)
            );
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();

            String full = sb.toString();
            if (!full.contains(targetText)) {
                result.put("success", false);
                result.put("error", "Target text snippet not found in file.");
                return result.toString();
            }

            int index = full.indexOf(targetText);
            int nextIndex = full.indexOf(targetText, index + targetText.length());
            if (nextIndex != -1) {
                result.put("success", false);
                result.put("error", "Target text occurs multiple times. Provide a larger unique snippet.");
                return result.toString();
            }

            String updated = full.replace(targetText, replacementText);
            FileOutputStream fos = new FileOutputStream(file, false);
            fos.write(updated.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();

            result.put("success", true);
            result.put("message", "Successfully replaced target text.");
        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return result.toString();
    }

    @JavascriptInterface
    public String listDirectory(String dirPath) {
        JSONObject result = new JSONObject();
        try {
            File dir = resolveFile(dirPath);
            if (!dir.exists() || !dir.isDirectory()) {
                result.put("success", false);
                result.put("error", "Directory does not exist: " + dirPath);
                return result.toString();
            }

            File[] files = dir.listFiles();
            JSONArray list = new JSONArray();
            if (files != null) {
                Arrays.sort(files, new Comparator<File>() {
                    @Override
                    public int compare(File f1, File f2) {
                        if (f1.isDirectory() && !f2.isDirectory()) return -1;
                        if (!f1.isDirectory() && f2.isDirectory()) return 1;
                        return f1.getName().compareToIgnoreCase(f2.getName());
                    }
                });

                for (File f : files) {
                    JSONObject item = new JSONObject();
                    item.put("name", f.getName());
                    item.put("isDirectory", f.isDirectory());
                    item.put("size", f.isDirectory() ? 0 : f.length());
                    list.put(item);
                }
            }

            result.put("success", true);
            result.put("files", list);
            result.put("path", dir.getAbsolutePath());
        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return result.toString();
    }

    @JavascriptInterface
    public void vibrate(int milliseconds) {
        try {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(milliseconds > 0 ? milliseconds : 50);
            }
        } catch (Exception ignored) {}
    }

    private PowerManager.WakeLock wakeLock = null;

    @JavascriptInterface
    public void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GCodeAI:TaskLock");
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(15 * 60 * 1000L); // 15 mins max
            }
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void playResponseTone() {
        try {
            Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone r = RingtoneManager.getRingtone(context, notification);
            if (r != null) {
                r.play();
            }
        } catch (Exception e) {
            try {
                ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
                tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 180);
            } catch (Exception ignored) {}
        }
        vibrate(55);
    }

    @JavascriptInterface
    public void postNotification(String title, String message) {
        // Only post system notification if the user is outside the app (backgrounded)
        if (isAppInForeground) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            String channelId = "gcode_ai_notifications";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        channelId,
                        "GCode AI Notifications",
                        NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("Completed AI tasks");
                channel.enableVibration(true);
                channel.setVibrationPattern(new long[]{0, 250, 150, 250});
                nm.createNotificationChannel(channel);
            }

            Intent intent = new Intent(context, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(context, 101, intent, pendingFlags);

            android.app.Notification.Builder nb;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nb = new android.app.Notification.Builder(context, channelId);
            } else {
                nb = new android.app.Notification.Builder(context);
            }

            String notifTitle = (title != null && !title.trim().isEmpty()) ? title.trim() : "GCode AI";
            String notifText = (message != null && !message.trim().isEmpty()) ? message.trim() : "Your response is ready.";

            nb.setContentTitle(notifTitle)
              .setContentText(notifText)
              .setSmallIcon(R.drawable.icon)
              .setContentIntent(pi)
              .setAutoCancel(true)
              .setPriority(android.app.Notification.PRIORITY_HIGH)
              .setDefaults(android.app.Notification.DEFAULT_ALL);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                nb.setStyle(new android.app.Notification.BigTextStyle().bigText(notifText));
            }

            nm.notify(1001, nb.build());
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @JavascriptInterface
    public void saveConfig(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    @JavascriptInterface
    public String getConfig(String key, String defaultValue) {
        return prefs.getString(key, defaultValue);
    }

    @JavascriptInterface
    public boolean isTermuxPermissionGranted() {
        return true;
    }

    private static volatile Process omniProcess = null;
    private static final Object OMNI_LOCK = new Object();
    private static volatile boolean isStartingOmni = false;

    @JavascriptInterface
    public boolean isOmniRouteRunning() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", 20128), 350);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public String getOmniRouteKey() {
        return "sk-5f238e76072d7926-d5c648-d2fcfe6e";
    }

    @JavascriptInterface
    public boolean startOmniRoute() {
        ensureOmniRouteAsync();
        return isOmniRouteRunning();
    }

    public void ensureOmniRouteAsync() {
        if (isOmniRouteRunning()) return;
        if (isStartingOmni) return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (OMNI_LOCK) {
                    if (isOmniRouteRunning()) return;
                    if (omniProcess != null) {
                        try {
                            if (omniProcess.isAlive()) return;
                        } catch (Throwable ignored) {}
                    }

                    isStartingOmni = true;
                    File publicLogFile = new File("/storage/emulated/0/Download/omniroute_debug.log");
                    try (java.io.FileWriter dfw = new java.io.FileWriter(publicLogFile, true)) {
                        dfw.write("\n=========================================\n");
                        dfw.write("[OmniRoute] ensureOmniRouteAsync invoked at " + new java.util.Date() + "\n");
                        dfw.flush();
                    } catch (Throwable ignored) {}

                    try {
                        File filesDir = context.getFilesDir();
                        File usrDir = new File(filesDir, "usr");
                        File homeDir = new File(filesDir, "home");
                        File tmpDir = new File(usrDir, "tmp");

                        if (!tmpDir.exists()) tmpDir.mkdirs();
                        if (!homeDir.exists()) homeDir.mkdirs();

                        File appNode = new File(usrDir, "bin/node");
                        File appOmni = new File(usrDir, "lib/node_modules/omniroute/bin/omniroute.mjs");
                        if (!appOmni.exists()) {
                            appOmni = new File(usrDir, "bin/omniroute");
                        }

                        if (appNode.exists()) {
                            appNode.setReadable(true, false);
                            appNode.setExecutable(true, false);
                        }

                        File bashBin = new File(usrDir, "bin/bash");
                        if (bashBin.exists()) {
                            bashBin.setReadable(true, false);
                            bashBin.setExecutable(true, false);
                        }

                        String appLibDir = new File(usrDir, "lib").getAbsolutePath();
                        String appBinDir = new File(usrDir, "bin").getAbsolutePath();

                        try (java.io.FileWriter dfw = new java.io.FileWriter(publicLogFile, true)) {
                            dfw.write("[OmniRoute] appNode: " + appNode.getAbsolutePath() + " (exists=" + appNode.exists() + ", canExec=" + appNode.canExecute() + ")\n");
                            dfw.write("[OmniRoute] appOmni: " + appOmni.getAbsolutePath() + " (exists=" + appOmni.exists() + ")\n");
                            dfw.write("[OmniRoute] homeDir: " + homeDir.getAbsolutePath() + "\n");
                            dfw.flush();
                        } catch (Throwable ignored) {}

                        File etcTls = new File(usrDir, "etc/tls");
                        if (!etcTls.exists()) etcTls.mkdirs();
                        File cleanCnf = new File(etcTls, "openssl.cnf");
                        if (!cleanCnf.exists() || cleanCnf.length() == 0) {
                            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(cleanCnf)) {
                                fos.write("# Minimal OpenSSL Config\nHOME = .\n".getBytes(StandardCharsets.UTF_8));
                            } catch (Throwable ignored) {}
                        }
                        cleanCnf.setReadable(true, false);

                        if (appNode.exists() && appOmni.exists()) {
                            ProcessBuilder pb = new ProcessBuilder(
                                appNode.getAbsolutePath(),
                                "--openssl-config=" + cleanCnf.getAbsolutePath(),
                                appOmni.getAbsolutePath(),
                                "serve", "--no-open", "--port", "20128"
                            );
                            pb.directory(homeDir);
                            Map<String, String> env = pb.environment();
                            env.put("PATH", appBinDir + ":/system/bin:/system/xbin");
                            env.put("LD_LIBRARY_PATH", appLibDir + ":/system/lib64:/vendor/lib64");
                            env.put("HOME", homeDir.getAbsolutePath());
                            env.put("PREFIX", usrDir.getAbsolutePath());
                            env.put("TMPDIR", tmpDir.getAbsolutePath());
                            env.put("SHELL", bashBin.exists() ? bashBin.getAbsolutePath() : "/system/bin/sh");
                            env.put("OPENSSL_CONF", cleanCnf.getAbsolutePath());
                            File certPem = new File(etcTls, "cert.pem");
                            if (certPem.exists()) {
                                certPem.setReadable(true, false);
                                env.put("SSL_CERT_FILE", certPem.getAbsolutePath());
                            }
                            env.put("NODE_PATH", new File(usrDir, "lib/node_modules").getAbsolutePath());
                            env.put("STORAGE_ENCRYPTION_KEY", "e5ef31d70a381ca9d02f8332737e86b451eedf5b4aabe5700ef33cc058251bb3");
                            env.put("REQUIRE_API_KEY", "false");
                            env.put("OMNIROUTE_REQUIRE_KEY", "false");
                            env.put("OMNIROUTE_SERVER_HOST", "127.0.0.1");
                            env.put("PORT", "20128");
                            pb.redirectErrorStream(true);

                            try {
                                omniProcess = pb.start();
                            } catch (Throwable directEx) {
                                try (java.io.FileWriter dfw = new java.io.FileWriter(publicLogFile, true)) {
                                    dfw.write("[OmniRoute] Direct launch failed: " + directEx.getMessage() + ", attempting bash fallback...\n");
                                    dfw.flush();
                                } catch (Throwable ignored) {}

                                if (bashBin.exists()) {
                                    ProcessBuilder pbBash = new ProcessBuilder(bashBin.getAbsolutePath(), "-c", 
                                        "exec '" + appNode.getAbsolutePath() + "' --openssl-config='" + cleanCnf.getAbsolutePath() + "' '" + appOmni.getAbsolutePath() + "' serve --no-open --port 20128");
                                    pbBash.directory(homeDir);
                                    pbBash.environment().putAll(env);
                                    pbBash.redirectErrorStream(true);
                                    omniProcess = pbBash.start();
                                } else {
                                    throw directEx;
                                }
                            }

                            final File logFile = new File(homeDir, "omniroute.log");
                            new Thread(() -> {
                                try (BufferedReader r = new BufferedReader(new InputStreamReader(omniProcess.getInputStream(), StandardCharsets.UTF_8));
                                     java.io.FileWriter fw = new java.io.FileWriter(logFile, false);
                                     java.io.FileWriter pfw = new java.io.FileWriter(publicLogFile, true)) {
                                    String line;
                                    while ((line = r.readLine()) != null) {
                                        android.util.Log.i("OmniRoute", line);
                                        fw.write(line + "\n");
                                        fw.flush();
                                        pfw.write("[OmniRoute STDOUT] " + line + "\n");
                                        pfw.flush();
                                    }
                                } catch (Exception ignored) {}
                            }).start();

                            for (int i = 0; i < 90; i++) {
                                if (isOmniRouteRunning()) {
                                    try (java.io.FileWriter dfw = new java.io.FileWriter(publicLogFile, true)) {
                                        dfw.write("[OmniRoute] SUCCESS: Port 20128 is listening after step " + i + "!\n");
                                        dfw.flush();
                                    } catch (Throwable ignored) {}
                                    break;
                                }
                                try { Thread.sleep(400); } catch (Exception ignored) {}
                            }
                        }
                    } catch (Throwable e) {
                        e.printStackTrace();
                        try (java.io.FileWriter dfw = new java.io.FileWriter(publicLogFile, true)) {
                            dfw.write("[OmniRoute EXCEPTION]: " + e.toString() + "\n");
                            for (StackTraceElement ste : e.getStackTrace()) {
                                dfw.write("  at " + ste.toString() + "\n");
                            }
                            dfw.flush();
                        } catch (Throwable ignored) {}
                    } finally {
                        isStartingOmni = false;
                    }
                }
            }
        }).start();
    }

    @JavascriptInterface
    public void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {}
    }

    /**
     * Google AI Mode style live web search without requiring any API keys.
     * Fetches real-time search snippets, titles, and links via fast POST scraping.
     */
    @JavascriptInterface
    public String searchWeb(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "Error: Empty search query.";
        }
        try {
            URL url = new URL("https://html.duckduckgo.com/html/");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            String postData = "q=" + URLEncoder.encode(query.trim(), "UTF-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(postData.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                return "Web search request returned HTTP status: " + code;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            conn.disconnect();

            String html = sb.toString();
            Pattern pSnip = Pattern.compile("<a class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);
            Pattern pTitle = Pattern.compile("<a class=\"result__url\"[^>]*>(.*?)</a>", Pattern.DOTALL);

            Matcher mSnip = pSnip.matcher(html);
            Matcher mTitle = pTitle.matcher(html);

            List<String> snips = new ArrayList<>();
            List<String> titles = new ArrayList<>();

            while (mSnip.find()) {
                snips.add(cleanHtml(mSnip.group(1)));
            }
            while (mTitle.find()) {
                titles.add(cleanHtml(mTitle.group(1)));
            }

            if (snips.isEmpty()) {
                return "No search results found for: " + query;
            }

            StringBuilder out = new StringBuilder();
            out.append("[Live Web Search Results for: \"").append(query.trim()).append("\"]\n\n");
            int count = Math.min(snips.size(), 6);
            for (int i = 0; i < count; i++) {
                String t = i < titles.size() ? titles.get(i) : "";
                out.append("[").append(i + 1).append("] ").append(t).append("\n");
                out.append(snips.get(i)).append("\n\n");
            }
            return out.toString().trim();
        } catch (Throwable e) {
            return "Web search error: " + e.getMessage();
        }
    }

    private static String cleanHtml(String text) {
        if (text == null) return "";
        String s = text.replaceAll("<[^>]+>", " ");
        s = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
             .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        return s.replaceAll("\\s+", " ").trim();
    }
}
