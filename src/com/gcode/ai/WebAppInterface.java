package com.gcode.ai;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.PlaybackParams;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.Vibrator;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.media.MediaPlayer;
import android.telephony.SmsManager;
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
    private android.webkit.WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private InAppSpeechRecognizer inAppSpeechRecognizer;
    private SpeechRecognizer systemSpeechRecognizer = null;
    private Intent speechIntent = null;
    private boolean isUsingSystemSpeech = false;
    private String currentSpeechLang = "bn-BD";
    private MediaPlayer voicePlayer;
    private TextToSpeech textToSpeech;
    private volatile boolean isListeningVoice = false;

    public void setWebView(android.webkit.WebView webView) {
        this.webView = webView;
    }

    private void dispatchJs(final String js) {
        if (webView != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        webView.evaluateJavascript(js, null);
                    } catch (Throwable ignored) {}
                }
            });
        }
    }

    public void terminateVoiceMode() {
        this.isVoiceModeActive = false;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (systemSpeechRecognizer != null && isUsingSystemSpeech) {
                        try { systemSpeechRecognizer.stopListening(); } catch (Throwable ignored) {}
                        isUsingSystemSpeech = false;
                    }
                    if (inAppSpeechRecognizer != null) {
                        inAppSpeechRecognizer.stopListening();
                    }
                    isListeningVoice = false;
                    stopBengaliVoice();
                    releaseWakeLock();
                    dispatchJs("window.endVoiceMode && window.endVoiceMode();");
                } catch (Throwable ignored) {}
            }
        });
    }

    private volatile boolean isVoiceModeActive = false;
    private volatile boolean isModalOpen = false;

    @JavascriptInterface
    public void setVoiceModeActive(boolean active) {
        this.isVoiceModeActive = active;
    }

    public boolean isVoiceModeActive() {
        return this.isVoiceModeActive;
    }

    @JavascriptInterface
    public void setModalOpen(boolean open) {
        this.isModalOpen = open;
    }

    public boolean isModalOpen() {
        return this.isModalOpen;
    }

    public void closeActiveModal() {
        dispatchJs("if (window.closeAllModals) { window.closeAllModals(); } else { const cm = document.getElementById('clear-modal'); if (cm) cm.style.display = 'none'; }");
    }

    public void setAppInForeground(boolean inForeground) {
        this.isAppInForeground = inForeground;
        if (!inForeground) {
            terminateVoiceMode();
        }
    }

    @JavascriptInterface
    public boolean isAppInForeground() {
        return this.isAppInForeground;
    }

    public WebAppInterface(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("hibban_prefs", Context.MODE_PRIVATE);
        this.inAppSpeechRecognizer = new InAppSpeechRecognizer(context);
        this.inAppSpeechRecognizer.setListener(new InAppSpeechRecognizer.Listener() {
            @Override
            public void onReadyForSpeech() {
                dispatchJs("window.onVoiceStateChange && window.onVoiceStateChange('ready');");
            }

            @Override
            public void onBeginningOfSpeech() {
                dispatchJs("window.onVoiceStateChange && window.onVoiceStateChange('listening');");
            }

            @Override
            public void onRmsChanged(float normRms) {
                dispatchJs("window.onVoiceRmsChanged && window.onVoiceRmsChanged(" + normRms + ");");
            }

            @Override
            public void onPartialResult(String partialText) {
                JSONObject obj = new JSONObject();
                try {
                    obj.put("partial", partialText);
                } catch (Exception ignored) {}
                dispatchJs("window.onVoicePartialResult && window.onVoicePartialResult(" + obj.toString() + ");");
            }

            @Override
            public void onEndOfSpeech() {
                dispatchJs("window.onVoiceStateChange && window.onVoiceStateChange('processing');");
            }

            @Override
            public void onResult(String text, float confidence) {
                JSONObject res = new JSONObject();
                try {
                    res.put("text", text);
                    res.put("confidence", confidence);
                } catch (Exception ignored) {}
                dispatchJs("window.onVoiceFinalResult && window.onVoiceFinalResult(" + res.toString() + ");");
            }

            @Override
            public void onError(int errorCode, String message) {
                dispatchJs("window.onVoiceError && window.onVoiceError(" + errorCode + ");");
            }
        });
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
                conn.setReadTimeout(45000);
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
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nexhib:TaskLock");
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

            String channelId = "nexhib_notifications";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        channelId,
                        "Nexhib Notifications",
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

            String notifTitle = (title != null && !title.trim().isEmpty()) ? title.trim() : "Nexhib";
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
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", 20128), 1200);
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
                            if (omniProcess.isAlive()) {
                                for (int i = 0; i < 8; i++) {
                                    if (isOmniRouteRunning()) return;
                                    try { Thread.sleep(300); } catch (Exception ignored) {}
                                }
                                if (isOmniRouteRunning()) return;
                            }
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

    /**
     * Generates an image using free, unmetered AI image generation API (Pollinations.ai)
     * and saves it directly to local device storage.
     */
    @JavascriptInterface
    public String generateImage(String prompt, String targetPath, int width, int height, String model) {
        JSONObject result = new JSONObject();
        if (prompt == null || prompt.trim().isEmpty()) {
            try {
                result.put("success", false);
                result.put("error", "Image prompt cannot be empty.");
            } catch (Exception ignored) {}
            return result.toString();
        }
        try {
            int w = (width > 0) ? width : 1024;
            int h = (height > 0) ? height : 1024;
            String m = (model != null && !model.trim().isEmpty()) ? model.trim() : "flux";
            long seed = System.currentTimeMillis();

            String encodedPrompt = URLEncoder.encode(prompt.trim(), "UTF-8");
            String resolvedTarget = targetPath;
            if (resolvedTarget == null || resolvedTarget.trim().isEmpty()) {
                File dlDir = new File(Environment.getExternalStorageDirectory(), "Download");
                if (!dlDir.exists()) dlDir.mkdirs();
                resolvedTarget = new File(dlDir, "hibban_ai_" + System.currentTimeMillis() + ".jpg").getAbsolutePath();
            }

            // Waterfall fallback models: preferred -> turbo -> pollinations (without watermark)
            String[] candidateModels = new String[] { m, "turbo", "pollinations" };
            String lastError = "Image generation failed";
            String successfulUrl = "";

            for (String cm : candidateModels) {
                // Strictly disable watermarks using nologo=true, no-logo=true, and nofeed=true
                String imageUrl = "https://image.pollinations.ai/prompt/" + encodedPrompt 
                        + "?width=" + w + "&height=" + h + "&model=" + cm + "&nologo=true&no-logo=true&nofeed=true&seed=" + seed;

                String dlRes = downloadFile(imageUrl, resolvedTarget);
                try {
                    JSONObject dlObj = new JSONObject(dlRes);
                    if (dlObj.optBoolean("success", false)) {
                        File checkF = new File(resolvedTarget);
                        if (checkF.exists() && checkF.length() > 500) {
                            successfulUrl = imageUrl;
                            break;
                        }
                    } else {
                        lastError = dlObj.optString("error", "HTTP error on model " + cm);
                    }
                } catch (Throwable parseErr) {
                    lastError = parseErr.getMessage();
                }
            }

            File finalF = new File(resolvedTarget);
            if (finalF.exists() && finalF.length() > 500) {
                result.put("success", true);
                result.put("path", resolvedTarget);
                result.put("prompt", prompt.trim());
                result.put("url", successfulUrl);
                result.put("message", "Image successfully generated and saved without watermark to: " + resolvedTarget);
            } else {
                result.put("success", false);
                result.put("error", lastError);
            }
            return result.toString();
        } catch (Throwable t) {
            try {
                result.put("success", false);
                result.put("error", "Image generation failed: " + t.getMessage());
            } catch (Exception ignored) {}
            return result.toString();
        }
    }

    // ==========================================
    // SYSTEM TELEMETRY & HARDWARE CONTROL APIS
    // ==========================================

    @JavascriptInterface
    public String getBatteryTelemetry() {
        JSONObject res = new JSONObject();
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = context.registerReceiver(null, ifilter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int pct = (level >= 0 && scale > 0) ? (level * 100 / scale) : -1;
                int voltage = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
                int temp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                int health = batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);

                String statusStr = (status == BatteryManager.BATTERY_STATUS_CHARGING) ? "Charging" :
                                   (status == BatteryManager.BATTERY_STATUS_DISCHARGING) ? "Discharging" :
                                   (status == BatteryManager.BATTERY_STATUS_FULL) ? "Full" : "Not Charging";

                String plugStr = (chargePlug == BatteryManager.BATTERY_PLUGGED_USB) ? "USB" :
                                 (chargePlug == BatteryManager.BATTERY_PLUGGED_AC) ? "AC Adapter" :
                                 (chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS) ? "Wireless" : "Battery";

                res.put("success", true);
                res.put("percentage", pct);
                res.put("voltage_mv", voltage);
                res.put("temperature_celsius", temp / 10.0);
                res.put("status", statusStr);
                res.put("plugged", plugStr);
                res.put("health", health == BatteryManager.BATTERY_HEALTH_GOOD ? "Good" : "Degraded");

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
                    if (bm != null) {
                        long currentNow = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                        res.put("current_now_microamps", currentNow);
                    }
                }
            } else {
                res.put("success", false);
                res.put("error", "Unable to read battery state.");
            }
        } catch (Throwable e) {
            try {
                res.put("success", false);
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res.toString();
    }

    @JavascriptInterface
    public String getNetworkTelemetry() {
        JSONObject res = new JSONObject();
        try {
            res.put("success", true);
            res.put("mobile_rx_bytes", TrafficStats.getMobileRxBytes());
            res.put("mobile_tx_bytes", TrafficStats.getMobileTxBytes());
            res.put("total_rx_bytes", TrafficStats.getTotalRxBytes());
            res.put("total_tx_bytes", TrafficStats.getTotalTxBytes());

            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni != null && ni.isConnected()) {
                    res.put("connected", true);
                    res.put("network_type", ni.getTypeName());
                    res.put("subtype", ni.getSubtypeName());
                } else {
                    res.put("connected", false);
                    res.put("network_type", "None");
                }
            }
        } catch (Throwable e) {
            try {
                res.put("success", false);
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res.toString();
    }

    @JavascriptInterface
    public String getAudioTelemetry() {
        JSONObject res = new JSONObject();
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                res.put("success", true);
                res.put("media_volume", am.getStreamVolume(AudioManager.STREAM_MUSIC));
                res.put("media_volume_max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                res.put("ring_volume", am.getStreamVolume(AudioManager.STREAM_RING));
                res.put("ring_volume_max", am.getStreamMaxVolume(AudioManager.STREAM_RING));
                res.put("notification_volume", am.getStreamVolume(AudioManager.STREAM_NOTIFICATION));
                res.put("notification_volume_max", am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION));
                int rm = am.getRingerMode();
                res.put("ringer_mode", rm == AudioManager.RINGER_MODE_SILENT ? "Silent" :
                                        rm == AudioManager.RINGER_MODE_VIBRATE ? "Vibrate" : "Normal");
            } else {
                res.put("success", false);
                res.put("error", "AudioManager unavailable.");
            }
        } catch (Throwable e) {
            try {
                res.put("success", false);
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res.toString();
    }

    @JavascriptInterface
    public String setAudioVolume(String streamType, int volumePercent) {
        JSONObject res = new JSONObject();
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                res.put("success", false);
                res.put("error", "AudioManager unavailable.");
                return res.toString();
            }
            int stream = AudioManager.STREAM_MUSIC;
            if ("ring".equalsIgnoreCase(streamType)) stream = AudioManager.STREAM_RING;
            else if ("notification".equalsIgnoreCase(streamType)) stream = AudioManager.STREAM_NOTIFICATION;
            else if ("voice".equalsIgnoreCase(streamType) || "call".equalsIgnoreCase(streamType)) stream = AudioManager.STREAM_VOICE_CALL;

            int max = am.getStreamMaxVolume(stream);
            int target = Math.max(0, Math.min(max, (int) Math.round((volumePercent / 100.0) * max)));
            am.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI);

            res.put("success", true);
            res.put("stream", streamType);
            res.put("new_volume", target);
            res.put("max_volume", max);
        } catch (Throwable e) {
            try {
                res.put("success", false);
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res.toString();
    }

    @JavascriptInterface
    public String setRingerMode(String mode) {
        JSONObject res = new JSONObject();
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (am == null) {
                res.put("success", false);
                res.put("error", "AudioManager unavailable.");
                return res.toString();
            }

            boolean isSilentTarget = "silent".equalsIgnoreCase(mode);
            boolean hasPolicyAccess = (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) || (nm != null && nm.isNotificationPolicyAccessGranted());

            if (isSilentTarget && !hasPolicyAccess) {
                // If setting silent mode without DND policy access, Android throws SecurityException.
                // Auto-open permission screen and fallback to Vibrate safely
                openNotificationPolicySettings();
                try {
                    am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                    res.put("success", true);
                    res.put("ringer_mode", "vibrate");
                    res.put("notice", "Silent mode requires Notification Policy Access. Opened settings screen automatically and temporarily switched to Vibrate.");
                    return res.toString();
                } catch (Throwable fallbackErr) {
                    res.put("success", false);
                    res.put("need_permission", true);
                    res.put("permission_type", "ACCESS_NOTIFICATION_POLICY");
                    res.put("error", "Notification Policy Access is required for Silent Mode. Settings screen has been opened automatically.");
                    return res.toString();
                }
            }

            if ("silent".equalsIgnoreCase(mode)) {
                am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            } else if ("vibrate".equalsIgnoreCase(mode)) {
                am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
            } else {
                am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            }
            res.put("success", true);
            res.put("ringer_mode", mode);
        } catch (Throwable e) {
            try {
                res.put("success", false);
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res.toString();
    }

    @JavascriptInterface
    public String setDoNotDisturb(boolean enabled, String dndMode) {
        JSONObject res = new JSONObject();
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

            boolean hasPolicyAccess = (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) || (nm != null && nm.isNotificationPolicyAccessGranted());

            if (!hasPolicyAccess) {
                // Auto-open the DND Permission settings screen directly
                openNotificationPolicySettings();

                // Also attempt accessibility fallback if enabled
                HibbanAccessibilityService service = HibbanAccessibilityService.getInstance();
                if (service != null) {
                    service.triggerGlobal(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
                    try { Thread.sleep(400); } catch (Exception ignored) {}
                    boolean clicked = service.clickByText("Do Not Disturb") || service.clickByText("DND") || service.clickByText("ডোন্ট ডিস্টার্ব");
                    if (clicked) {
                        res.put("success", true);
                        res.put("dnd_active", enabled);
                        res.put("via", "accessibility");
                        res.put("message", "Toggled Do Not Disturb via Accessibility Service.");
                        return res.toString();
                    }
                }

                res.put("success", false);
                res.put("need_permission", true);
                res.put("permission_type", "ACCESS_NOTIFICATION_POLICY");
                res.put("error", "Do Not Disturb (Notification Policy) access is not granted. Nexhib has automatically opened the 'Do Not Disturb Access' settings screen. Please toggle Nexhib to Allowed.");
                return res.toString();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && nm != null) {
                if (enabled) {
                    int filter = NotificationManager.INTERRUPTION_FILTER_PRIORITY;
                    if ("total_silence".equalsIgnoreCase(dndMode)) filter = NotificationManager.INTERRUPTION_FILTER_NONE;
                    else if ("alarms_only".equalsIgnoreCase(dndMode)) filter = NotificationManager.INTERRUPTION_FILTER_ALARMS;
                    nm.setInterruptionFilter(filter);
                } else {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                }
            }
            if (am != null) {
                if (enabled) {
                    try { am.setRingerMode(AudioManager.RINGER_MODE_SILENT); } catch (Throwable ignored) {}
                } else {
                    try { am.setRingerMode(AudioManager.RINGER_MODE_NORMAL); } catch (Throwable ignored) {}
                }
            }
            res.put("success", true);
            res.put("dnd_active", enabled);
            res.put("mode", dndMode != null ? dndMode : (enabled ? "priority" : "off"));
        } catch (Throwable e) {
            try {
                res.put("success", false);
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res.toString();
    }

    @JavascriptInterface
    public String setFlashlight(boolean enabled) {
        JSONObject res = new JSONObject();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                CameraManager camManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                if (camManager != null) {
                    String[] ids = camManager.getCameraIdList();
                    if (ids != null && ids.length > 0) {
                        camManager.setTorchMode(ids[0], enabled);
                        res.put("success", true);
                        res.put("flashlight", enabled);
                        res.put("message", enabled ? "Flashlight turned on." : "Flashlight turned off.");
                        return res.toString();
                    }
                }
            }
            res.put("success", false);
            res.put("error", "Flashlight/Torch not supported on this device.");
        } catch (Throwable e) {
            try {
                res.put("success", false);
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res.toString();
    }

    @JavascriptInterface
    public String dispatchIntent(String action, String dataUri, String packageName, String extraKey, String extraValue) {
        JSONObject res = new JSONObject();
        try {
            Intent intent = new Intent();
            if (action != null && !action.trim().isEmpty()) {
                intent.setAction(action.trim());
            } else {
                intent.setAction(Intent.ACTION_VIEW);
            }
            if (dataUri != null && !dataUri.trim().isEmpty()) {
                intent.setData(Uri.parse(dataUri.trim()));
            }
            if (packageName != null && !packageName.trim().isEmpty()) {
                intent.setPackage(packageName.trim());
            }
            if (extraKey != null && !extraKey.trim().isEmpty() && extraValue != null) {
                intent.putExtra(extraKey.trim(), extraValue);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            res.put("success", true);
            res.put("message", "Intent dispatched successfully.");
        } catch (Throwable e) {
            try {
                res.put("success", false);
                res.put("error", "Failed to dispatch intent: " + e.getMessage());
            } catch (Exception ignored) {}
        }
        return res.toString();
    }

    private void requestRuntimePermissionOnDemand(final String permission) {
        if (context instanceof Activity) {
            ((Activity) context).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        ((Activity) context).requestPermissions(new String[]{permission}, 1001);
                    }
                }
            });
        }
    }

    @JavascriptInterface
    public String interactScreen(String actionType, String targetText, String targetId, String textToType, int x, int y) {
        JSONObject res = new JSONObject();
        try {
            HibbanAccessibilityService service = HibbanAccessibilityService.getInstance();
            if (service == null) {
                openAccessibilitySettings();
                res.put("success", false);
                res.put("need_permission", true);
                res.put("error", "Accessibility Service is not enabled. Nexhib has automatically opened Accessibility settings on screen. Please toggle 'Nexhib' on and try again.");
                return res.toString();
            }

            String action = actionType != null ? actionType.trim().toLowerCase() : "dump";
            if ("dump".equals(action) || "read_screen".equals(action)) {
                return service.dumpScreenHierarchy().toString();
            } else if ("click_text".equals(action)) {
                boolean ok = service.clickByText(targetText);
                res.put("success", ok);
                res.put("message", ok ? "Clicked node with text: " + targetText : "Text node not found on screen: " + targetText);
            } else if ("click_id".equals(action)) {
                boolean ok = service.clickById(targetId);
                res.put("success", ok);
                res.put("message", ok ? "Clicked node with id: " + targetId : "View id not found on screen: " + targetId);
            } else if ("type".equals(action) || "type_text".equals(action)) {
                boolean ok = service.typeText(textToType);
                res.put("success", ok);
                res.put("message", ok ? "Typed text into focused field." : "No focused input field found.");
            } else if ("click_coords".equals(action)) {
                boolean ok = service.clickCoordinates(x, y);
                res.put("success", ok);
                res.put("message", ok ? "Dispatched click at (" + x + ", " + y + ")" : "Failed to dispatch coordinate gesture.");
            } else if ("back".equals(action)) {
                boolean ok = service.triggerGlobal(AccessibilityService.GLOBAL_ACTION_BACK);
                res.put("success", ok);
            } else if ("home".equals(action)) {
                boolean ok = service.triggerGlobal(AccessibilityService.GLOBAL_ACTION_HOME);
                res.put("success", ok);
            } else if ("recents".equals(action)) {
                boolean ok = service.triggerGlobal(AccessibilityService.GLOBAL_ACTION_RECENTS);
                res.put("success", ok);
            } else if ("notifications".equals(action)) {
                boolean ok = service.triggerGlobal(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
                res.put("success", ok);
            } else {
                res.put("success", false);
                res.put("error", "Unknown screen action: " + actionType);
            }
        } catch (Throwable e) {
            try {
                res.put("success", false);
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res.toString();
    }

    @JavascriptInterface
    public String getRecentNotifications(int limit) {
        JSONObject res = new JSONObject();
        try {
            HibbanNotificationListener listener = HibbanNotificationListener.getInstance();
            if (listener == null) {
                openNotificationListenerSettings();
                res.put("success", false);
                res.put("need_permission", true);
                res.put("error", "Notification Access is not enabled. Nexhib has automatically opened Notification Access settings on screen. Please toggle 'Nexhib' on and try again.");
                return res.toString();
            }
            res.put("success", true);
            res.put("notifications", listener.getActiveNotificationsJson(limit));
        } catch (Throwable e) {
            try {
                res.put("success", false);
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res.toString();
    }

    @JavascriptInterface
    public String getCallLogs(int limit) {
        JSONObject res = new JSONObject();
        try {
            if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
                requestRuntimePermissionOnDemand(Manifest.permission.READ_CALL_LOG);
                res.put("success", false);
                res.put("error", "READ_CALL_LOG permission not granted. Nexhib has automatically requested permission on screen now. Please tap Allow and retry.");
                return res.toString();
            }
            int max = (limit > 0) ? limit : 15;
            Cursor cursor = context.getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION},
                    null, null, CallLog.Calls.DATE + " DESC"
            );
            JSONArray arr = new JSONArray();
            if (cursor != null) {
                int count = 0;
                while (cursor.moveToNext() && count < max) {
                    JSONObject c = new JSONObject();
                    c.put("number", cursor.getString(0));
                    c.put("name", cursor.getString(1));
                    int type = cursor.getInt(2);
                    c.put("type", type == CallLog.Calls.INCOMING_TYPE ? "Incoming" :
                                  type == CallLog.Calls.OUTGOING_TYPE ? "Outgoing" :
                                  type == CallLog.Calls.MISSED_TYPE ? "Missed" : "Other");
                    c.put("date", cursor.getLong(3));
                    c.put("duration_sec", cursor.getLong(4));
                    arr.put(c);
                    count++;
                }
                cursor.close();
            }
            res.put("success", true);
            res.put("calls", arr);
        } catch (Throwable e) {
            try {
                res.put("success", false);
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res.toString();
    }

    @JavascriptInterface
    public String getContacts(String query, int limit) {
        JSONObject res = new JSONObject();
        try {
            if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                requestRuntimePermissionOnDemand(Manifest.permission.READ_CONTACTS);
                res.put("success", false);
                res.put("error", "READ_CONTACTS permission not granted. Nexhib has automatically requested permission on screen now. Please tap Allow and retry.");
                return res.toString();
            }
            int max = (limit > 0) ? limit : 25;
            Uri uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
            String[] projection = new String[]{
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE
            };
            String selection = null;
            String[] selectionArgs = null;
            if (query != null && !query.trim().isEmpty()) {
                selection = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ? OR " +
                            ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?";
                String q = "%" + query.trim() + "%";
                selectionArgs = new String[]{q, q};
            }
            Cursor cursor = context.getContentResolver().query(
                    uri,
                    projection,
                    selection,
                    selectionArgs,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            );
            JSONArray arr = new JSONArray();
            if (cursor != null) {
                int count = 0;
                while (cursor.moveToNext() && count < max) {
                    JSONObject c = new JSONObject();
                    c.put("name", cursor.getString(0));
                    c.put("number", cursor.getString(1));
                    int type = cursor.getInt(2);
                    String label = (type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) ? "Mobile" :
                                   (type == ContactsContract.CommonDataKinds.Phone.TYPE_HOME) ? "Home" :
                                   (type == ContactsContract.CommonDataKinds.Phone.TYPE_WORK) ? "Work" : "Other";
                    c.put("type", label);
                    arr.put(c);
                    count++;
                }
                cursor.close();
            }
            res.put("success", true);
            res.put("contacts", arr);
        } catch (Throwable e) {
            try {
                res.put("success", false);
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res.toString();
    }

    @JavascriptInterface
    public String makePhoneCall(String phoneNumber, boolean directCall) {
        JSONObject res = new JSONObject();
        try {
            if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
                res.put("success", false);
                res.put("error", "Phone number is empty");
                return res.toString();
            }
            String cleanNum = phoneNumber.trim().replaceAll("[^0-9+*#]", "");
            Intent intent;
            if (directCall) {
                if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(cleanNum)));
                } else {
                    requestRuntimePermissionOnDemand(Manifest.permission.CALL_PHONE);
                    intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(cleanNum)));
                }
            } else {
                intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(cleanNum)));
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            res.put("success", true);
            res.put("status", directCall ? "Direct call placed to " + cleanNum : "Dialer opened with " + cleanNum);
        } catch (Throwable e) {
            try {
                res.put("success", false);
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res.toString();
    }

    @JavascriptInterface
    public String sendSms(String phoneNumber, String message) {
        JSONObject res = new JSONObject();
        try {
            if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
                res.put("success", false);
                res.put("error", "Phone number is empty");
                return res.toString();
            }
            String cleanNum = phoneNumber.trim().replaceAll("[^0-9+*#]", "");
            if (context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                SmsManager sms = SmsManager.getDefault();
                sms.sendTextMessage(cleanNum, null, message != null ? message : "", null, null);
                res.put("success", true);
                res.put("status", "SMS sent successfully to " + cleanNum);
            } else {
                requestRuntimePermissionOnDemand(Manifest.permission.SEND_SMS);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse("sms:" + cleanNum));
                intent.putExtra("sms_body", message != null ? message : "");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                res.put("success", true);
                res.put("status", "SMS permission prompt requested on screen. Opened SMS draft with " + cleanNum);
            }
        } catch (Throwable e) {
            try {
                res.put("success", false);
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res.toString();
    }

    @JavascriptInterface
    public String getSmsInbox(int limit) {
        JSONObject res = new JSONObject();
        JSONArray list = new JSONArray();
        int max = limit > 0 ? Math.min(limit, 50) : 15;
        try {
            if (context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
                Uri inboxUri = Uri.parse("content://sms/inbox");
                try (Cursor cursor = context.getContentResolver().query(inboxUri, new String[]{"_id", "address", "body", "date"}, null, null, "date DESC LIMIT " + max)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int addrIdx = cursor.getColumnIndex("address");
                        int bodyIdx = cursor.getColumnIndex("body");
                        int dateIdx = cursor.getColumnIndex("date");
                        do {
                            JSONObject item = new JSONObject();
                            item.put("sender", addrIdx != -1 ? cursor.getString(addrIdx) : "Unknown");
                            item.put("body", bodyIdx != -1 ? cursor.getString(bodyIdx) : "");
                            item.put("timestamp", dateIdx != -1 ? cursor.getLong(dateIdx) : 0L);
                            list.put(item);
                        } while (cursor.moveToNext());
                    }
                }
                res.put("success", true);
                res.put("count", list.length());
                res.put("messages", list);
            } else {
                requestRuntimePermissionOnDemand(Manifest.permission.READ_SMS);
                res.put("success", false);
                res.put("error", "READ_SMS permission not granted. Permission dialog requested.");
            }
        } catch (Throwable e) {
            try {
                res.put("success", false);
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res.toString();
    }

    @JavascriptInterface
    public void requestAllPermissions() {
        if (context instanceof MainActivity) {
            ((MainActivity) context).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ((MainActivity) context).checkAndRequestAllPermissions();
                }
            });
        }
    }

    @JavascriptInterface
    public String checkPermissionsStatus() {
        JSONObject res = new JSONObject();
        try {
            res.put("contacts", context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED);
            res.put("callLog", context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED);
            res.put("phone", context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED);
            res.put("sms", context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED);
            res.put("storage", context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
            res.put("audio", context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
            res.put("camera", context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED);

            res.put("accessibility", HibbanAccessibilityService.getInstance() != null);
            res.put("notificationListener", HibbanNotificationListener.getInstance() != null);

            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            boolean hasPolicy = (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) || (nm != null && nm.isNotificationPolicyAccessGranted());
            res.put("notificationPolicy", hasPolicy);

            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            boolean isIgnoringBattery = (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) || (pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName()));
            res.put("batteryOptimization", isIgnoringBattery);

            boolean allFiles = true;
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    java.lang.reflect.Method isManagerMethod = Environment.class.getMethod("isExternalStorageManager");
                    allFiles = (Boolean) isManagerMethod.invoke(null);
                } catch (Throwable ignored) {
                    allFiles = false;
                }
            }
            res.put("allFiles", allFiles);
        } catch (Throwable e) {
            try { res.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return res.toString();
    }

    @JavascriptInterface
    public void openAllFilesSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent intent = new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void openBatteryOptimizationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void openNotificationPolicySettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void openNotificationListenerSettings() {
        try {
            Intent intent = null;
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    intent = new Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS");
                    intent.putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                            new ComponentName(context, HibbanNotificationListener.class).flattenToString());
                } catch (Throwable ignored) {}
            }
            if (intent == null) {
                intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception ignored) {}
        }
    }

    // ==========================================
    // JARVIS & SIRI VOICE ENGINE (SPEECH & TTS)
    // ==========================================

    @JavascriptInterface
    public boolean isSpeechRecognitionAvailable() {
        return true;
    }



    private static volatile boolean isRequestingAudio = false;

    public static void resetRequestingAudio() {
        isRequestingAudio = false;
    }

    @JavascriptInterface
    public boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    @JavascriptInterface
    public void requestAudioPermission() {
        if (hasAudioPermission() || isRequestingAudio) return;
        isRequestingAudio = true;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (context instanceof Activity && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        ((Activity) context).requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1005);
                    } else {
                        isRequestingAudio = false;
                    }
                } catch (Throwable ignored) {
                    isRequestingAudio = false;
                }
            }
        });
    }

    @JavascriptInterface
    public void startSpeechRecognition(final String lang) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!hasAudioPermission()) {
                        requestAudioPermission();
                        dispatchJs("window.onVoiceError && window.onVoiceError(9);");
                        return;
                    }

                    final String speechLang = (lang != null && !lang.trim().isEmpty()) ? lang.trim() : "bn-BD";
                    currentSpeechLang = speechLang;
                    isListeningVoice = true;

                    // 100% Standalone In-App Speech Recognition (Zero external Google Keyboard or speech app dependencies)
                    if (inAppSpeechRecognizer != null) {
                        inAppSpeechRecognizer.startListening(speechLang);
                    } else {
                        dispatchJs("window.onVoiceError && window.onVoiceError(7);");
                    }
                } catch (Throwable e) {
                    dispatchJs("window.onVoiceError && window.onVoiceError(-1);");
                }
            }
        });
    }

    @JavascriptInterface
    public void stopSpeechRecognition() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (inAppSpeechRecognizer != null) {
                        inAppSpeechRecognizer.stopListening();
                    }
                    isListeningVoice = false;
                } catch (Throwable ignored) {}
            }
        });
    }

    @JavascriptInterface
    public void finishSpeechNow() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (inAppSpeechRecognizer != null) {
                        inAppSpeechRecognizer.finishSpeechNow();
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    @JavascriptInterface
    public void speakBengali(final String text, final String callbackId) {
        if (text == null || text.trim().isEmpty()) {
            dispatchJs("window.onVoicePlaybackFinished && window.onVoicePlaybackFinished('" + callbackId + "');");
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean synthSuccess = false;
                boolean isFallback = false;
                File cacheDir = context.getCacheDir();
                if (!cacheDir.exists()) cacheDir.mkdirs();
                File outMp3 = new File(cacheDir, "hibban_bn_voice.mp3");
                if (outMp3.exists()) outMp3.delete();

                File filesDir = context.getFilesDir();
                File usrDir = new File(filesDir, "usr");
                File binDir = new File(usrDir, "bin");
                File bashBin = new File(binDir, "bash");
                File appPython = new File(binDir, "python3");
                File ttsScript = new File(binDir, "tts_synthesizer.py");
                File homeDir = new File(filesDir, "home");
                File tmpDir = new File(usrDir, "tmp");

                File libPython = new File(usrDir, "lib/libpython3.14.so");
                File publicLogFile = new File("/storage/emulated/0/Download/omniroute_debug.log");

                if (ttsScript.exists() && (appPython.exists() || bashBin.exists())) {
                    try {
                        String cleanPrompt = text.replaceAll("[*#_`~>\\[\\](){}]", " ").replaceAll("\\s+", " ").trim();
                        List<String> cmd = new ArrayList<>();
                        if (bashBin.exists()) {
                            cmd.add(bashBin.getAbsolutePath());
                            cmd.add("--noprofile");
                            cmd.add("--norc");
                            cmd.add("-c");
                            cmd.add("exec '" + appPython.getAbsolutePath() + "' '" + ttsScript.getAbsolutePath() + "' \"$1\" \"$2\" \"$3\" \"$4\" \"$5\"");
                            cmd.add("bash");
                            cmd.add(cleanPrompt);
                            cmd.add(outMp3.getAbsolutePath());
                            cmd.add("bn-BD-PradeepNeural");
                            cmd.add("+0%");
                            cmd.add("+0Hz");
                        } else {
                            cmd.add(appPython.getAbsolutePath());
                            cmd.add(ttsScript.getAbsolutePath());
                            cmd.add(cleanPrompt);
                            cmd.add(outMp3.getAbsolutePath());
                            cmd.add("bn-BD-PradeepNeural");
                            cmd.add("+0%");
                            cmd.add("+0Hz");
                        }

                        ProcessBuilder pb = new ProcessBuilder(cmd);
                        pb.directory(homeDir.exists() ? homeDir : filesDir);
                        Map<String, String> env = pb.environment();
                        env.put("PATH", binDir.getAbsolutePath() + ":/system/bin:/system/xbin");
                        env.put("LD_LIBRARY_PATH", new File(usrDir, "lib").getAbsolutePath() + ":/system/lib64:/vendor/lib64");
                        if (libPython.exists()) {
                            env.put("LD_PRELOAD", libPython.getAbsolutePath());
                        }
                        env.put("PREFIX", usrDir.getAbsolutePath());
                        env.put("HOME", homeDir.getAbsolutePath());
                        env.put("TMPDIR", tmpDir.getAbsolutePath());
                        env.put("PYTHONHOME", usrDir.getAbsolutePath());
                        env.put("PYTHONPATH", new File(usrDir, "lib/python3.14/site-packages").getAbsolutePath());
                        File certPem = new File(usrDir, "lib/python3.14/site-packages/certifi/cacert.pem");
                        if (certPem.exists()) {
                            env.put("SSL_CERT_FILE", certPem.getAbsolutePath());
                        }
                        pb.redirectErrorStream(true);
                        Process proc = pb.start();

                        StringBuilder procLog = new StringBuilder();
                        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                            String l;
                            while ((l = r.readLine()) != null) {
                                procLog.append(l).append("\n");
                            }
                        }

                        boolean finished = proc.waitFor(9, TimeUnit.SECONDS);
                        if (finished && proc.exitValue() == 0 && outMp3.exists() && outMp3.length() > 800) {
                            synthSuccess = true;
                            isFallback = false;
                        } else {
                            try (java.io.FileWriter dfw = new java.io.FileWriter(publicLogFile, true)) {
                                dfw.write("[TTS-Edge] Failed. exit=" + (finished ? proc.exitValue() : "timeout") + " outMp3=" + outMp3.exists() + " log=" + procLog.toString() + "\n");
                                dfw.flush();
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ttsEx) {
                        try (java.io.FileWriter dfw = new java.io.FileWriter(publicLogFile, true)) {
                            dfw.write("[TTS-Edge] Exception: " + ttsEx.getMessage() + "\n");
                            dfw.flush();
                        } catch (Throwable ignored) {}
                    }
                }

                if (!synthSuccess) {
                    // Ultra-fast Google Translate Bengali Voice HTTP Fallback (0.15s latency)
                    synthSuccess = synthesizeWithGoogleHttpTts(text, outMp3);
                    isFallback = true;
                }

                if (synthSuccess && outMp3.exists() && outMp3.length() > 400) {
                    playAudioFile(outMp3.getAbsolutePath(), callbackId, isFallback);
                } else {
                    speakWithNativeTts(text, callbackId);
                }
            }
        }).start();
    }

    private boolean synthesizeWithGoogleHttpTts(String text, File outMp3) {
        if (text == null || text.trim().isEmpty()) return false;
        try {
            String clean = text.replaceAll("[*#_`~>\\[\\](){}]", " ").replaceAll("\\s+", " ").trim();
            if (clean.length() > 220) {
                clean = clean.substring(0, 220);
            }
            String encoded = java.net.URLEncoder.encode(clean, "UTF-8");
            java.net.URL url = new java.net.URL("https://translate.google.com/translate_tts?ie=UTF-8&tl=bn&client=tw-ob&q=" + encoded);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(6000);
            if (conn.getResponseCode() == 200) {
                try (InputStream in = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(outMp3)) {
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        fos.write(buf, 0, r);
                    }
                    fos.flush();
                }
                conn.disconnect();
                return outMp3.exists() && outMp3.length() > 500;
            }
            conn.disconnect();
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return false;
    }

    private void playAudioFile(final String path, final String callbackId, final boolean isFallback) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    // Ensure media stream volume is audible
                    try {
                        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                        if (am != null) {
                            int curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                            if (curVol == 0) {
                                int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(1, (int)(maxVol * 0.7)), 0);
                            }
                        }
                    } catch (Throwable ignored) {}

                    if (voicePlayer != null) {
                        try { voicePlayer.stop(); voicePlayer.release(); } catch (Throwable ignored) {}
                    }
                    voicePlayer = new MediaPlayer();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        AudioAttributes attrs = new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build();
                        voicePlayer.setAudioAttributes(attrs);
                    } else {
                        voicePlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                    }
                    voicePlayer.setDataSource(path);
                    voicePlayer.setVolume(1.0f, 1.0f);

                    voicePlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                        @Override
                        public void onCompletion(MediaPlayer mp) {
                            dispatchJs("window.onVoicePlaybackFinished && window.onVoicePlaybackFinished('" + callbackId + "');");
                            try { mp.release(); } catch (Throwable ignored) {}
                            voicePlayer = null;
                        }
                    });
                    voicePlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        @Override
                        public boolean onError(MediaPlayer mp, int what, int extra) {
                            dispatchJs("window.onVoicePlaybackFinished && window.onVoicePlaybackFinished('" + callbackId + "');");
                            try { mp.release(); } catch (Throwable ignored) {}
                            voicePlayer = null;
                            return true;
                        }
                    });

                    // Prepare player first
                    voicePlayer.prepare();

                    // Voice Calibration (Calibrated male voice + natural pacing):
                    // MUST be set AFTER prepare() to avoid IllegalStateException!
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            PlaybackParams params = new PlaybackParams();
                            params.setPitch(1.0f);
                            params.setSpeed(1.0f); // Calibrated to 1.0f as requested (natural pacing)
                            voicePlayer.setPlaybackParams(params);
                        } catch (Throwable ignored) {}
                    }

                    voicePlayer.start();
                    dispatchJs("window.onVoicePlaybackStarted && window.onVoicePlaybackStarted('" + callbackId + "');");
                } catch (Throwable e) {
                    dispatchJs("window.onVoicePlaybackFinished && window.onVoicePlaybackFinished('" + callbackId + "');");
                }
            }
        });
    }

    @JavascriptInterface
    public void stopBengaliVoice() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (voicePlayer != null) {
                        try { voicePlayer.stop(); voicePlayer.release(); } catch (Throwable ignored) {}
                        voicePlayer = null;
                    }
                    if (textToSpeech != null) {
                        textToSpeech.stop();
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private void speakWithNativeTts(final String text, final String callbackId) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (textToSpeech == null) {
                    textToSpeech = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
                        @Override
                        public void onInit(int status) {
                            if (status == TextToSpeech.SUCCESS) {
                                initAndSpeak(text, callbackId);
                            } else {
                                dispatchJs("window.onVoicePlaybackFinished && window.onVoicePlaybackFinished('" + callbackId + "');");
                            }
                        }
                    });
                } else {
                    initAndSpeak(text, callbackId);
                }
            }
        });
    }

    private void initAndSpeak(String text, final String callbackId) {
        try {
            java.util.Locale bnLocale = new java.util.Locale("bn", "BD");
            textToSpeech.setLanguage(bnLocale);
            textToSpeech.setSpeechRate(1.12f); // Slightly faster speech
            textToSpeech.setPitch(0.80f); // Masculine male pitch

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    java.util.Set<android.speech.tts.Voice> voices = textToSpeech.getVoices();
                    if (voices != null) {
                        for (android.speech.tts.Voice v : voices) {
                            if (v.getLocale() != null && "bn".equalsIgnoreCase(v.getLocale().getLanguage())) {
                                String vName = v.getName().toLowerCase();
                                if (vName.contains("male") || vName.contains("man") || vName.contains("-b-") || vName.contains("-d-")) {
                                    textToSpeech.setVoice(v);
                                    break;
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}

                textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {
                        dispatchJs("window.onVoicePlaybackStarted && window.onVoicePlaybackStarted('" + callbackId + "');");
                    }
                    @Override public void onDone(String utteranceId) {
                        dispatchJs("window.onVoicePlaybackFinished && window.onVoicePlaybackFinished('" + callbackId + "');");
                    }
                    @Override public void onError(String utteranceId) {
                        dispatchJs("window.onVoicePlaybackFinished && window.onVoicePlaybackFinished('" + callbackId + "');");
                    }
                });
                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, callbackId);
            } else {
                java.util.HashMap<String, String> map = new java.util.HashMap<>();
                map.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, callbackId);
                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, map);
            }
        } catch (Throwable e) {
            dispatchJs("window.onVoicePlaybackFinished && window.onVoicePlaybackFinished('" + callbackId + "');");
        }
    }
}


