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
        try { capture(sbn); }
        catch (RuntimeException malformed) { /* Third-party extras may have invalid types; never crash the listener. */ }
    }
    private void capture(StatusBarNotification sbn) {
        if (sbn == null || getPackageName().equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null || (n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_GROUP_SUMMARY)) != 0) return;
        Bundle extras = n.extras; if (extras == null) return;
        String conversation = chars(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        if (conversation.isEmpty()) conversation = chars(extras.getCharSequence(Notification.EXTRA_TITLE));
        final String heading = DomainRules.clipped(conversation, 300), pkg = sbn.getPackageName(), key = sbn.getKey();
        final long now = System.currentTimeMillis();
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
        for (Notification.MessagingStyle.Message msg : structured) {
            String content = chars(msg.getText()), sender = chars(msg.getSender());
            if (content.isEmpty() || sender.isEmpty()) continue; // null sender denotes the phone owner's outgoing message.
            long at = msg.getTimestamp() > 0 && msg.getTimestamp() <= now + 60000 ? msg.getTimestamp() : now;
            messages.add(new Message(sender + "：" + content, "message:" + sender + ":" + msg.getTimestamp(), at));
        }
        if (!structured.isEmpty() && messages.isEmpty()) return;
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
        if (messages.isEmpty()) return;
        BanxuApp.IO.execute(() -> {
            try {
                JSONObject settings = new SecureSettings(this).publicJson();
                if (!settings.optBoolean("captureEnabled")) return;
                JSONArray allowed = settings.getJSONArray("allowedPackages"); boolean selected = false;
                for (int i = 0; i < allowed.length(); i++) if (pkg.equals(allowed.optString(i))) selected = true;
                if (!selected || !DomainRules.matchesConversation(heading, settings.optString("conversationFilters"))) return;
                boolean inserted = false;
                for (Message message : messages) {
                    String hash = digest(heading + "\n" + message.body);
                    String identity = key + "|" + digest(message.identity);
                    if (Store.get(this).addInbox(pkg, heading, message.body, message.at, identity, hash) != null) inserted = true;
                }
                if (inserted) { Store.get(this).clearErrorStartingWith("通知保存失败"); BanxuApp.changed(); Repository.process(this); }
            } catch (Exception ex) { Store.get(this).setMeta("lastError", "通知保存失败，请检查存储空间；可手动粘贴补录"); BanxuApp.changed(); }
        });
    }
    private static String digest(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(); for (byte b : hash) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return hex.toString();
    }
    private static String chars(CharSequence value) { return value == null ? "" : value.toString().trim(); }
    @Override public void onListenerConnected() { super.onListenerConnected(); BanxuApp.changed(); }
    @Override public void onListenerDisconnected() { super.onListenerDisconnected(); BanxuApp.changed(); }
}
