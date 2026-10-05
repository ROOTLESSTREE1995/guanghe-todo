package cn.banxu.app;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public final class CaptureService extends NotificationListenerService {
    private static final class Message {
        final String body, identity;
        final long at;
        Message(String body, String identity, long at) { this.body = DomainRules.clipped(body, 16000); this.identity = identity; this.at = at; }
    }
    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || getPackageName().equals(sbn.getPackageName())) return;
        final long receivedAt = System.currentTimeMillis();
        // Source checks, extras parsing, diagnostics and SQLite all stay off Android's main thread.
        BanxuApp.IO.execute(() -> {
            boolean selected = false;
            try {
                JSONObject settings = new SecureSettings(this).publicJson();
                if (!settings.optBoolean("captureEnabled")) return;
                JSONArray allowed = settings.optJSONArray("allowedPackages");
                if (allowed != null) for (int i = 0; i < allowed.length(); i++) if (sbn.getPackageName().equals(allowed.optString(i))) selected = true;
                if (!selected) return;
                ListenerHealth.event(this, receivedAt);
                capture(sbn, settings, receivedAt);
            } catch (Exception malformed) {
                // Third-party extras can contain invalid types. Never persist them in a diagnostic.
                if (selected) ListenerHealth.outcome(this, "malformed", 0, 0);
            }
        });
    }
    private void capture(StatusBarNotification sbn, JSONObject settings, long now) {
        Notification n = sbn.getNotification();
        if (n == null) { ListenerHealth.outcome(this, "no_text", 0, 0); return; }
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) { ListenerHealth.outcome(this, "group_summary", 0, 0); return; }
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) { ListenerHealth.outcome(this, "ongoing", 0, 0); return; }
        Bundle extras = n.extras;
        if (extras == null) { ListenerHealth.outcome(this, "no_text", 0, 0); return; }
        String conversation = chars(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        if (conversation.isEmpty()) conversation = chars(extras.getCharSequence(Notification.EXTRA_TITLE));
        final String heading = DomainRules.clipped(conversation, 300), pkg = sbn.getPackageName(), key = sbn.getKey();
        if (!DomainRules.matchesConversation(heading, settings.optString("conversationFilters"))) {
            ListenerHealth.outcome(this, "conversation_filtered", 0, 0); return;
        }
        final List<Message> messages = new ArrayList<>();
        // MessagingStyle retains individual messages even when EXTRA_TEXT is only an unread-count summary.
        List<Notification.MessagingStyle.Message> structured = java.util.Collections.emptyList();
        try {
            android.os.Parcelable[] messageBundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (android.os.Build.VERSION.SDK_INT >= 30 && messageBundles != null) structured = Notification.MessagingStyle.Message.getMessagesFromBundleArray(messageBundles);
            else if (android.os.Build.VERSION.SDK_INT >= 28) {
                Notification.Style recovered = Notification.Builder.recoverBuilder(this, n).getStyle();
                if (recovered instanceof Notification.MessagingStyle) structured = ((Notification.MessagingStyle)recovered).getMessages();
            }
        } catch (RuntimeException malformedStyle) { /* Fall back to text fields exposed by the source app. */ }
        boolean hasIncomingSender = false;
        for (Notification.MessagingStyle.Message msg : structured) {
            String content = chars(msg.getText()), sender = chars(msg.getSender());
            if (!sender.isEmpty()) hasIncomingSender = true;
            if (content.isEmpty() || sender.isEmpty()) continue; // null sender denotes the phone owner's outgoing message.
            long at = msg.getTimestamp() > 0 && msg.getTimestamp() <= now + 60000 ? msg.getTimestamp() : now;
            messages.add(new Message(sender + "：" + content, "message:" + sender + ":" + msg.getTimestamp(), at));
        }
        if (!structured.isEmpty() && messages.isEmpty()) {
            ListenerHealth.outcome(this, hasIncomingSender ? "no_text" : "outgoing_only", 0, 0); return;
        }
        if (messages.isEmpty()) {
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) for (CharSequence line : lines) {
                String content = chars(line); if (!content.isEmpty()) messages.add(new Message(content, "line:" + content, now));
            }
        }
        if (messages.isEmpty()) {
            String body = chars(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (body.isEmpty()) body = chars(extras.getCharSequence(Notification.EXTRA_TEXT));
            if (!body.isEmpty()) messages.add(new Message(body, "snapshot", now));
        }
        if (messages.isEmpty()) { ListenerHealth.outcome(this, "no_text", 0, 0); return; }
        int inserted = 0, duplicates = 0;
        try {
            for (Message message : messages) {
                String hash = digest(heading + "\n" + message.body);
                String identity = key + "|" + digest(message.identity);
                if (Store.get(this).addInbox(pkg, heading, message.body, message.at, identity, hash) != null) inserted++;
                else duplicates++;
            }
            if (inserted > 0) Store.get(this).clearErrorStartingWith("通知保存失败");
        } catch (Exception ex) {
            ListenerHealth.outcome(this, "storage_error", inserted, duplicates);
            try { Store.get(this).setMeta("lastError", "通知保存失败，请检查存储空间；可手动粘贴补录"); }
            catch (RuntimeException storageUnavailable) { /* The diagnostic remains available without SQLite. */ }
            BanxuApp.changed();
            return;
        }
        ListenerHealth.outcome(this, inserted > 0 ? "saved" : "duplicate", inserted, duplicates);
        if (inserted > 0) Repository.process(this);
    }
    private static String digest(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(); for (byte b : hash) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return hex.toString();
    }
    private static String chars(CharSequence value) { return value == null ? "" : value.toString().trim(); }
    @Override public void onListenerConnected() { super.onListenerConnected(); ListenerHealth.onConnected(this); }
    @Override public void onListenerDisconnected() { super.onListenerDisconnected(); ListenerHealth.onDisconnected(this); }
    @Override public void onDestroy() { ListenerHealth.onDestroyed(this); super.onDestroy(); }
}
