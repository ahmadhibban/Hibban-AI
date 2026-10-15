package com.gcode.ai;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public class MainActivity extends Activity {
    private WebView webView;
    private WebAppInterface bridge;
    private static final int PERMISSION_REQ_CODE = 1001;
    private static final int FILE_CHOOSER_REQ_CODE = 1002;
    private ValueCallback<Uri[]> uploadMessage;
    private Handler mainHandler;

    private static final long TOTAL_BYTES_EXPECTED = 738560980L;
    private long bytesReadTotal = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());

        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Throwable ignored) {}

        // Dark status bar and navigation bar matching dark theme
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.setStatusBarColor(0xFF07090E);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                window.setNavigationBarColor(0xFF07090E);
            }
        }

        checkAndRequestAllPermissions();
        initWebView();

        File usrBash = new File(getFilesDir(), "usr/bin/bash");
        File libIcu = new File(getFilesDir(), "usr/lib/libicudata.so.78.3");
        File libCares = new File(getFilesDir(), "usr/lib/libcares.so");
        File marker = new File(getFilesDir(), ".installed_v18");

        boolean isAlreadyExtracted = marker.exists() && libIcu.exists() && libCares.exists() && usrBash.exists() && usrBash.length() > 0;

        if (isAlreadyExtracted) {
            ensureNodeLibsAndPermissions();
            bridge.ensureOmniRouteAsync();
            if (bridge.isOmniRouteRunning()) {
                webView.loadUrl("file:///android_asset/index.html");
            } else {
                webView.loadUrl("file:///android_asset/setup.html");
                waitForOmniRouteAndLaunch();
            }
        } else {
            webView.loadUrl("file:///android_asset/setup.html");
            startFirstLaunchExtraction();
        }
    }

    private void waitForOmniRouteAndLaunch() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                int waitSteps = 0;
                boolean isConnected = false;
                while (waitSteps < 90) {
                    final int pct = Math.min(99, 85 + (waitSteps * 14) / 90);
                    final String msg = (waitSteps < 20) ? "Starting AI Engine (Port 20128)..." :
                                       (waitSteps < 55) ? "Initializing OmniRoute Gateway..." :
                                                          "Activating unlimited models...";
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (webView != null) {
                                webView.evaluateJavascript("if(window.updateProgress) window.updateProgress(" + pct + ", 100, 100, '" + msg + "');", null);
                            }
                        }
                    });

                    if (bridge.isOmniRouteRunning()) {
                        try {
                            java.net.URL testUrl = new java.net.URL("http://127.0.0.1:20128/v1/models");
                            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) testUrl.openConnection();
                            conn.setRequestProperty("Authorization", "Bearer sk-5f238e76072d7926-d5c648-d2fcfe6e");
                            conn.setConnectTimeout(800);
                            conn.setReadTimeout(1200);
                            int respCode = conn.getResponseCode();
                            conn.disconnect();
                            if (respCode == 200 || respCode == 401) {
                                isConnected = true;
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    waitSteps++;
                }

                if (isConnected) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (webView != null) {
                                webView.evaluateJavascript("if(window.updateProgress) window.updateProgress(100, 100, 100, 'Active & Ready! Launching...');", null);
                            }
                        }
                    });

                    try { Thread.sleep(400); } catch (InterruptedException ignored) {}

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            webView.loadUrl("file:///android_asset/index.html");
                        }
                    });
                } else {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (webView != null) {
                                webView.evaluateJavascript("if(window.updateProgress) window.updateProgress(99, 100, 100, 'AI Engine is taking longer to start. Please restart the app.');", null);
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private void startFirstLaunchExtraction() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}

                boolean success = extractEnvironment();
                if (success) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            webView.loadUrl("file:///android_asset/index.html");
                        }
                    });
                }
            }
        }).start();
    }

    private void ensureFileLink(File link, File target) {
        if (!link.exists() && target.exists()) {
            try {
                java.nio.file.Files.copy(target.toPath(), link.toPath());
                link.setReadable(true, false);
                link.setExecutable(true, false);
            } catch (Throwable ignored) {}
        }
    }

    private void ensureNodeLibsAndPermissions() {
        try {
            File usrDir = new File(getFilesDir(), "usr");
            File libCares = new File(usrDir, "lib/libcares.so");
            File libIcu = new File(usrDir, "lib/libicudata.so.78.3");
            if (!libCares.exists() || !libIcu.exists()) {
                extractTarArchive("node_libs_bootstrap.tar.gz", usrDir, "Installing Core Node & ICU Libraries...", "[LIBS] Decompressing libcares and libicu");
            }
            File libDir = new File(usrDir, "lib");
            if (libDir.exists()) {
                ensureFileLink(new File(libDir, "libicudata.so.78"), new File(libDir, "libicudata.so.78.3"));
                ensureFileLink(new File(libDir, "libicudata.so"), new File(libDir, "libicudata.so.78.3"));
                ensureFileLink(new File(libDir, "libicui18n.so.78"), new File(libDir, "libicui18n.so.78.3"));
                ensureFileLink(new File(libDir, "libicui18n.so"), new File(libDir, "libicui18n.so.78.3"));
                ensureFileLink(new File(libDir, "libicuuc.so.78"), new File(libDir, "libicuuc.so.78.3"));
                ensureFileLink(new File(libDir, "libicuuc.so"), new File(libDir, "libicuuc.so.78.3"));
                ensureFileLink(new File(libDir, "libz.so.1"), new File(libDir, "libz.so.1.3.2"));
                ensureFileLink(new File(libDir, "libz.so"), new File(libDir, "libz.so.1.3.2"));
                ensureFileLink(new File(libDir, "libsqlite3.so"), new File(libDir, "libsqlite3.so.3.53.4"));
                ensureFileLink(new File(libDir, "libsqlite3.so.0"), new File(libDir, "libsqlite3.so.3.53.4"));
            }

            File binDir = new File(usrDir, "bin");
            if (binDir.exists()) {
                File[] bins = binDir.listFiles();
                if (bins != null) {
                    for (File b : bins) {
                        b.setReadable(true, false);
                        b.setExecutable(true, false);
                    }
                }
            }

            File etcTls = new File(usrDir, "etc/tls");
            if (!etcTls.exists()) etcTls.mkdirs();
            File cleanCnf = new File(etcTls, "openssl.cnf");
            if (!cleanCnf.exists() || cleanCnf.length() == 0) {
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(cleanCnf)) {
                    fos.write("# Minimal OpenSSL Config\nHOME = .\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (Throwable ignored) {}
            }
            cleanCnf.setReadable(true, false);

            new ProcessBuilder("/system/bin/toybox", "chmod", "-R", "755", usrDir.getAbsolutePath())
                    .start().waitFor();
        } catch (Throwable ignored) {}
    }

    private void extractTarArchive(String assetPrefix, File targetDir, final String stageName, final String logLine) {
        try {
            List<InputStream> parts = new ArrayList<>();
            try {
                parts.add(new BufferedInputStream(getAssets().open(assetPrefix), 262144));
            } catch (Throwable notSingle) {
                outer:
                for (char c1 = 'a'; c1 <= 'z'; c1++) {
                    for (char c2 = 'a'; c2 <= 'z'; c2++) {
                        String partName = assetPrefix + ".part_" + c1 + c2;
                        try {
                            InputStream partStream = getAssets().open(partName);
                            parts.add(new BufferedInputStream(partStream, 262144));
                        } catch (Throwable noMoreParts) {
                            break outer;
                        }
                    }
                }
            }

            if (parts.isEmpty()) return;

            Enumeration<InputStream> en = Collections.enumeration(parts);
            InputStream is = new SequenceInputStream(en);

            Process process = new ProcessBuilder("/system/bin/toybox", "tar", "-xzf", "-", "-C", targetDir.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();

            BufferedOutputStream os = new BufferedOutputStream(process.getOutputStream(), 262144);
            byte[] buf = new byte[262144];
            int len;
            long lastReportBytes = bytesReadTotal;

            while ((len = is.read(buf)) != -1) {
                os.write(buf, 0, len);
                bytesReadTotal += len;

                if (bytesReadTotal - lastReportBytes >= 3 * 1024 * 1024) {
                    lastReportBytes = bytesReadTotal;
                    final int pct = (int) Math.min(96, (bytesReadTotal * 96) / TOTAL_BYTES_EXPECTED);
                    final int currentMB = (int) (bytesReadTotal / (1024 * 1024));
                    final int totalMB = (int) (TOTAL_BYTES_EXPECTED / (1024 * 1024));
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (webView != null) {
                                webView.evaluateJavascript("if(window.updateProgress) window.updateProgress(" + pct + ", " + currentMB + ", " + totalMB + ", '" + stageName + "', '" + logLine + "');", null);
                            }
                        }
                    });
                }
            }
            os.flush();
            os.close();
            is.close();
            process.waitFor();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private boolean extractEnvironment() {
        try {
            bytesReadTotal = 0;
            File filesDir = getFilesDir();
            File usrDir = new File(filesDir, "usr");
            File homeDir = new File(filesDir, "home");
            File tmpDir = new File(usrDir, "tmp");
            usrDir.mkdirs();
            homeDir.mkdirs();
            tmpDir.mkdirs();

            extractTarArchive("system_bootstrap.tar.gz", usrDir, "Unpacking Linux Core & Python 3.14...", "[PKG] Decompressing /usr/bin, python3.14, pip, proot");
            extractTarArchive("node_libs_bootstrap.tar.gz", usrDir, "Installing Core Node & ICU Libraries...", "[LIBS] Decompressing libcares and libicu");
            extractTarArchive("omni_bootstrap.tar.gz", usrDir, "Mounting OmniRoute Multi-Model Gateway & Aider...", "[OMNI] Decompressing node, omniroute gateway, better-sqlite3");
            extractTarArchive("home_bootstrap.tar.gz", homeDir, "Configuring Keyless Quotas & Distro Capabilities...", "[HOME] Setting up .omniroute db and unlimited environment");

            // Set executable permissions
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (webView != null) {
                        webView.evaluateJavascript("if(window.updateProgress) window.updateProgress(98, 690, 690, 'Setting permissions & verifying kernel...', '[SYS] Chmod 755 usr/bin/bash and proot binaries');", null);
                    }
                }
            });

            ensureNodeLibsAndPermissions();

            File bashCheck = new File(usrDir, "bin/bash");
            if (bashCheck.exists() && bashCheck.length() > 0) {
                File etcDir = new File(usrDir, "etc");
                if (!etcDir.exists()) etcDir.mkdirs();
                File resolvConf = new File(etcDir, "resolv.conf");
                if (!resolvConf.exists() || resolvConf.length() == 0) {
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(resolvConf)) {
                        fos.write("nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\nnameserver 1.0.0.1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } catch (Throwable ignored) {}
                }

                new File(filesDir, ".installed_v18").createNewFile();
                bridge.ensureOmniRouteAsync();

                final int totalMB = (int) (TOTAL_BYTES_EXPECTED / (1024 * 1024));
                int waitSteps = 0;
                boolean isConnected = false;

                while (waitSteps < 90) {
                    if (bridge.isOmniRouteRunning()) {
                        try {
                            java.net.URL testUrl = new java.net.URL("http://127.0.0.1:20128/v1/models");
                            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) testUrl.openConnection();
                            conn.setRequestProperty("Authorization", "Bearer sk-5f238e76072d7926-d5c648-d2fcfe6e");
                            conn.setConnectTimeout(800);
                            conn.setReadTimeout(1200);
                            int respCode = conn.getResponseCode();
                            conn.disconnect();
                            if (respCode == 200 || respCode == 401) {
                                isConnected = true;
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }

                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    waitSteps++;

                    final int dynamicPct = Math.min(99, 96 + (waitSteps * 3) / 45);
                    final String stageMsg = (dynamicPct <= 97) ? "Starting AI Engine (Port 20128)..." : 
                                            (dynamicPct == 98) ? "Initializing OmniRoute Gateway..." : 
                                                                 "Activating models & unlimited quotas...";
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (webView != null) {
                                webView.evaluateJavascript("if(window.updateProgress) window.updateProgress(" + dynamicPct + ", " + totalMB + ", " + totalMB + ", '" + stageMsg + "');", null);
                            }
                        }
                    });
                }

                if (isConnected) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (webView != null) {
                                webView.evaluateJavascript("if(window.updateProgress) window.updateProgress(100, " + totalMB + ", " + totalMB + ", 'Active & Ready! Launching...');", null);
                            }
                        }
                    });

                    try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                    return true;
                } else {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (webView != null) {
                                webView.evaluateJavascript("if(window.updateProgress) window.updateProgress(99, " + totalMB + ", " + totalMB + ", 'AI Engine is taking longer to start. Please restart the app.');", null);
                            }
                        }
                    });
                    return false;
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return false;
    }

    private void initWebView() {
        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setDatabaseEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);

        try {
            ws.setAllowFileAccessFromFileURLs(true);
            ws.setAllowUniversalAccessFromFileURLs(true);
        } catch (Throwable ignored) {}

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                return super.onConsoleMessage(cm);
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                try {
                    request.grant(request.getResources());
                } catch (Throwable ignored) {}
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                }
                uploadMessage = filePathCallback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                startActivityForResult(Intent.createChooser(intent, "Select Image"), FILE_CHOOSER_REQ_CODE);
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });

        bridge = new WebAppInterface(this);
        webView.addJavascriptInterface(bridge, "AndroidBridge");
    }

    private void checkAndRequestAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] perms = new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            };
            boolean needReq = false;
            for (String p : perms) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    needReq = true;
                    break;
                }
            }
            if (needReq) {
                requestPermissions(perms, PERMISSION_REQ_CODE);
            }
        }

        // Android 11+ (API 30+) Manage External Storage (All Files Access) via reflection
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Method isManagerMethod = Environment.class.getMethod("isExternalStorageManager");
                boolean isManager = (Boolean) isManagerMethod.invoke(null);
                if (!isManager) {
                    Intent intent = new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQ_CODE) {
            if (uploadMessage != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    String dataString = data.getDataString();
                    if (dataString != null) {
                        results = new Uri[]{Uri.parse(dataString)};
                    } else if (data.getClipData() != null) {
                        int num = data.getClipData().getItemCount();
                        results = new Uri[num];
                        for (int i = 0; i < num; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    }
                }
                uploadMessage.onReceiveValue(results);
                uploadMessage = null;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bridge != null) {
            bridge.setAppInForeground(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (bridge != null) {
            bridge.setAppInForeground(false);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
