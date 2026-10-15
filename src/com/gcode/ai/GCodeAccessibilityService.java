package com.gcode.ai;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public class GCodeAccessibilityService extends AccessibilityService {
    private static volatile GCodeAccessibilityService instance = null;

    public static GCodeAccessibilityService getInstance() {
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
        try {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(targetText.trim());
            if (list != null && !list.isEmpty()) {
                for (AccessibilityNodeInfo n : list) {
                    AccessibilityNodeInfo clickable = n;
                    while (clickable != null && !clickable.isClickable()) {
                        clickable = clickable.getParent();
                    }
                    if (clickable != null && clickable.isClickable()) {
                        return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                    if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true;
                    }
                }
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
                    if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true;
                    }
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

    public boolean triggerGlobal(int action) {
        return performGlobalAction(action);
    }
}
