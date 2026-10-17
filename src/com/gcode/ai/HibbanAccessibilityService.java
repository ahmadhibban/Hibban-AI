package com.gcode.ai;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.Executor;

public class HibbanAccessibilityService extends AccessibilityService {
    private static volatile HibbanAccessibilityService instance = null;

    public static HibbanAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isServiceActive() {
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Active event listening
    }

    @Override
    public void onInterrupt() {
        instance = null;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    public JSONObject dumpScreenHierarchy() {
        JSONObject res = new JSONObject();
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                res.put("success", false);
                res.put("error", "Unable to access active window. Screen may be locked or secure.");
                return res;
            }
            res.put("success", true);
            res.put("packageName", root.getPackageName() != null ? root.getPackageName().toString() : "");
            JSONArray nodesArray = new JSONArray();
            parseNodeRecursive(root, nodesArray, 0);
            res.put("nodes", nodesArray);
        } catch (Throwable e) {
            try {
                res.put("success", false);
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res;
    }

    private void parseNodeRecursive(AccessibilityNodeInfo node, JSONArray array, int depth) {
        if (node == null || depth > 20) return;
        try {
            JSONObject obj = new JSONObject();
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            CharSequence viewId = node.getViewIdResourceName();
            CharSequence cls = node.getClassName();

            boolean hasContent = (text != null && text.length() > 0) || 
                                 (desc != null && desc.length() > 0) || 
                                 node.isClickable() || node.isEditable();

            if (hasContent) {
                if (text != null) obj.put("text", text.toString());
                if (desc != null) obj.put("desc", desc.toString());
                if (viewId != null) obj.put("id", viewId.toString());
                if (cls != null) obj.put("class", cls.toString());
                obj.put("clickable", node.isClickable());
                obj.put("editable", node.isEditable());
                obj.put("enabled", node.isEnabled());
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                obj.put("bounds", r.left + "," + r.top + "," + r.right + "," + r.bottom);
                array.put(obj);
            }

            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    parseNodeRecursive(child, array, depth + 1);
                    child.recycle();
                }
            }
        } catch (Throwable ignored) {}
    }

    public boolean clickByText(String targetText) {
        if (targetText == null || targetText.trim().isEmpty()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        String query = targetText.trim().toLowerCase();
        try {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(targetText.trim());
            if (list != null && !list.isEmpty()) {
                for (AccessibilityNodeInfo n : list) {
                    if (clickNodeRobustly(n)) return true;
                }
            }
            // Fallback: recursive search matching text or contentDescription
            AccessibilityNodeInfo matched = findNodeRecursive(root, query, 0);
            if (matched != null) {
                return clickNodeRobustly(matched);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private AccessibilityNodeInfo findNodeRecursive(AccessibilityNodeInfo node, String query, int depth) {
        if (node == null || depth > 25) return null;
        try {
            CharSequence text = node.getText();
            if (text != null && text.toString().toLowerCase().contains(query)) {
                return node;
            }
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.toString().toLowerCase().contains(query)) {
                return node;
            }
            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                AccessibilityNodeInfo res = findNodeRecursive(child, query, depth + 1);
                if (res != null) return res;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean clickNodeRobustly(AccessibilityNodeInfo n) {
        if (n == null) return false;
        try {
            AccessibilityNodeInfo clickable = n;
            while (clickable != null && !clickable.isClickable()) {
                clickable = clickable.getParent();
            }
            if (clickable != null && clickable.isClickable()) {
                if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
            }
            if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            // Gesture fallback if action fails or node isn't declared clickable
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() > 0 && r.height() > 0) {
                return clickCoordinates(r.centerX(), r.centerY());
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public boolean clickById(String viewId) {
        if (viewId == null || viewId.trim().isEmpty()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(viewId.trim());
            if (list != null && !list.isEmpty()) {
                for (AccessibilityNodeInfo n : list) {
                    if (clickNodeRobustly(n)) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public boolean typeText(String text) {
        if (text == null) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public boolean clickCoordinates(int x, int y) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100));
            return dispatchGesture(builder.build(), null, null);
        }
        return false;
    }

    public boolean swipe(int startX, int startY, int endX, int endY, int durationMs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(startX, startY);
            path.lineTo(endX, endY);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            int dur = (durationMs > 0) ? Math.min(durationMs, 5000) : 350;
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, dur));
            return dispatchGesture(builder.build(), null, null);
        }
        return false;
    }

    public boolean scrollDirection(String direction) {
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int width = dm.widthPixels;
            int height = dm.heightPixels;
            int midX = width / 2;
            int midY = height / 2;
            if (direction == null || "down".equalsIgnoreCase(direction)) {
                return swipe(midX, (int) (height * 0.75), midX, (int) (height * 0.25), 350);
            } else if ("up".equalsIgnoreCase(direction)) {
                return swipe(midX, (int) (height * 0.25), midX, (int) (height * 0.75), 350);
            } else if ("right".equalsIgnoreCase(direction)) {
                return swipe((int) (width * 0.8), midY, (int) (width * 0.2), midY, 350);
            } else if ("left".equalsIgnoreCase(direction)) {
                return swipe((int) (width * 0.2), midY, (int) (width * 0.8), midY, 350);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public boolean longPress(int x, int y, int durationMs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            int dur = (durationMs > 0) ? durationMs : 1000;
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, dur));
            return dispatchGesture(builder.build(), null, null);
        }
        return false;
    }

    public boolean triggerGlobal(int action) {
        return performGlobalAction(action);
    }

    public boolean triggerNamedGlobal(String actionName) {
        if (actionName == null) return false;
        String a = actionName.trim().toLowerCase();
        int action = -1;
        if (a.contains("back")) action = GLOBAL_ACTION_BACK;
        else if (a.contains("home")) action = GLOBAL_ACTION_HOME;
        else if (a.contains("recent") || a.contains("app_switch") || a.contains("overview")) action = GLOBAL_ACTION_RECENTS;
        else if (a.contains("notif")) action = GLOBAL_ACTION_NOTIFICATIONS;
        else if (a.contains("quick") || a.contains("setting")) action = GLOBAL_ACTION_QUICK_SETTINGS;
        else if (a.contains("power")) action = GLOBAL_ACTION_POWER_DIALOG;
        else if (a.contains("lock")) action = 8; // GLOBAL_ACTION_LOCK_SCREEN
        else if (a.contains("shot") || a.contains("screen")) action = 9; // GLOBAL_ACTION_TAKE_SCREENSHOT
        else if (a.contains("split")) action = 7; // GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
        if (action != -1) {
            return performGlobalAction(action);
        }
        return false;
    }

    public boolean takeScreenCapture(final String targetFilePath) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Class<?> serviceClass = AccessibilityService.class;
                Class<?> callbackClass = Class.forName("android.accessibilityservice.AccessibilityService$TakeScreenshotCallback");
                Method takeScreenshotMethod = serviceClass.getMethod("takeScreenshot", int.class, Executor.class, callbackClass);

                InvocationHandler handler = new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("onSuccess".equals(method.getName()) && args != null && args.length > 0) {
                            Object screenshotResult = args[0];
                            try {
                                Method getHardwareBuffer = screenshotResult.getClass().getMethod("getHardwareBuffer");
                                Object hardwareBuffer = getHardwareBuffer.invoke(screenshotResult);
                                Method getColorSpace = screenshotResult.getClass().getMethod("getColorSpace");
                                Object colorSpace = getColorSpace.invoke(screenshotResult);

                                Class<?> hwBufferClass = Class.forName("android.hardware.HardwareBuffer");
                                Class<?> colorSpaceClass = Class.forName("android.graphics.ColorSpace");
                                Method wrapMethod = Bitmap.class.getMethod("wrapHardwareBuffer", hwBufferClass, colorSpaceClass);
                                Bitmap hwBitmap = (Bitmap) wrapMethod.invoke(null, hardwareBuffer, colorSpace);

                                if (hwBitmap != null) {
                                    Bitmap copy = hwBitmap.copy(Bitmap.Config.ARGB_8888, false);
                                    File outFile = new File(targetFilePath);
                                    if (outFile.getParentFile() != null && !outFile.getParentFile().exists()) {
                                        outFile.getParentFile().mkdirs();
                                    }
                                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                        copy.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                                    }
                                    hwBitmap.recycle();
                                    copy.recycle();
                                }
                                Method closeBuffer = hwBufferClass.getMethod("close");
                                closeBuffer.invoke(hardwareBuffer);
                            } catch (Throwable e) {
                                e.printStackTrace();
                            }
                        }
                        return null;
                    }
                };

                Object callbackProxy = Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass},
                    handler
                );

                Executor directExecutor = new Executor() {
                    @Override
                    public void execute(Runnable command) {
                        new Thread(command).start();
                    }
                };

                takeScreenshotMethod.invoke(this, Display.DEFAULT_DISPLAY, directExecutor, callbackProxy);
                return true;
            } catch (Throwable ignored) {}
        }
        return performGlobalAction(9); // Fallback to GLOBAL_ACTION_TAKE_SCREENSHOT
    }
}
