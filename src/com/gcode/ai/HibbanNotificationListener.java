package com.gcode.ai;

import android.app.Notification;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HibbanNotificationListener extends NotificationListenerService {
    private static volatile HibbanNotificationListener instance = null;
    private static final List<JSONObject> notificationHistory = Collections.synchronizedList(new ArrayList<>());

    public static HibbanNotificationListener getInstance() {
        return instance;
    }

    public static boolean isServiceActive() {
        return instance != null;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        try {
            Notification n = sbn.getNotification();
            if (n == null) return;

            JSONObject obj = new JSONObject();
            obj.put("package", sbn.getPackageName());
            obj.put("postTime", sbn.getPostTime());
            obj.put("id", sbn.getId());

            Bundle extras = n.extras;
            if (extras != null) {
                CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
                CharSequence subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
                CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);

                if (title != null) obj.put("title", title.toString());
                if (text != null) obj.put("text", text.toString());
                if (subText != null) obj.put("subText", subText.toString());
                if (bigText != null) obj.put("bigText", bigText.toString());
            }

            notificationHistory.add(0, obj);
            while (notificationHistory.size() > 50) {
                notificationHistory.remove(notificationHistory.size() - 1);
            }
        } catch (Throwable ignored) {}
    }

    public JSONArray getActiveNotificationsJson(int limit) {
        JSONArray arr = new JSONArray();
        try {
            int max = (limit > 0) ? limit : 20;
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                int count = Math.min(active.length, max);
                for (int i = 0; i < count; i++) {
                    StatusBarNotification sbn = active[i];
                    JSONObject obj = new JSONObject();
                    obj.put("package", sbn.getPackageName());
                    obj.put("postTime", sbn.getPostTime());
                    Notification n = sbn.getNotification();
                    if (n != null && n.extras != null) {
                        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
                        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
                        if (title != null) obj.put("title", title.toString());
                        if (text != null) obj.put("text", text.toString());
                    }
                    arr.put(obj);
                }
            } else {
                int count = Math.min(notificationHistory.size(), max);
                for (int i = 0; i < count; i++) {
                    arr.put(notificationHistory.get(i));
                }
            }
        } catch (Throwable ignored) {}
        return arr;
    }
}
