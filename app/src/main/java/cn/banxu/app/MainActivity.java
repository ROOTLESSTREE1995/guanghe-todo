package cn.banxu.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Only bundled assets can run in this WebView; the bridge is never exposed to websites. */
public final class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String PAGE = "https://" + HOST + "/assets/index.html";
    private WebView web;
    private boolean loaded;
    private String pendingItemId;
    private String pendingRoute;
    @android.annotation.SuppressLint("SetJavaScriptEnabled") // Required by bundled UI; no remote documents or network can load.
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); BanxuApp.attach(this);
        readPendingDestination(getIntent());
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int background = Color.parseColor(dark ? "#15181d" : "#f5f6f8");
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(background);
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        web = new WebView(this); web.setBackgroundColor(background);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1)); setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().getInsetsController().setSystemBarsAppearance(dark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | (dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        }
        getWindow().setStatusBarColor(background); getWindow().setNavigationBarColor(background);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        WebSettings s = web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(false);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setAllowFileAccessFromFileURLs(false); s.setAllowUniversalAccessFromFileURLs(false);
        s.setBlockNetworkLoads(true); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(false); s.setSupportMultipleWindows(false); s.setGeolocationEnabled(false);
        s.setSaveFormData(false); s.setMediaPlaybackRequiresUserGesture(true); s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setTextZoom(Math.round(getResources().getConfiguration().fontScale * 100));
        if (Build.VERSION.SDK_INT >= 33) s.setAlgorithmicDarkeningAllowed(false);
        s.setSafeBrowsingEnabled(true);
        web.setWebViewClient(new LocalClient()); web.addJavascriptInterface(new Bridge(), "Banxu");
        web.setDownloadListener((url, userAgent, disposition, mime, size) -> Toast.makeText(this, "不支持从消息下载文件", Toast.LENGTH_SHORT).show());
        web.loadUrl(PAGE);
    }
    @Override protected void onResume() {
        super.onResume(); BanxuApp.attach(this);
        BanxuApp.IO.execute(() -> { ReminderScheduler.rescheduleAll(this); Repository.process(this); BanxuApp.changed(); });
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        readPendingDestination(intent); notifyWeb(); openPendingDestination();
    }
    private void readPendingDestination(Intent intent) {
        pendingItemId = intent == null ? null : intent.getStringExtra("itemId");
        String route = intent == null ? null : intent.getStringExtra("route");
        pendingRoute = "today".equals(route) || "leaves".equals(route) ? route : null;
    }
    private void openPendingDestination() {
        if (web == null || !loaded || (pendingItemId == null && pendingRoute == null)) return;
        String id = pendingItemId, route = pendingRoute; pendingItemId = null; pendingRoute = null;
        String script = "";
        if (route != null) script += "if(typeof window.onNativeOpenRoute==='function'){window.onNativeOpenRoute(" + JSONObject.quote(route) + ");}";
        if (id != null) script += "if(typeof window.onNativeOpenItem==='function'){window.onNativeOpenItem(" + JSONObject.quote(id) + ");}";
        web.evaluateJavascript(script, null);
    }
    private void reportWidgetPin(String status) {
        if (web != null && loaded) web.evaluateJavascript("if(typeof window.onNativeWidgetPinResult==='function'){window.onNativeWidgetPinResult(" + JSONObject.quote(status) + ");}", null);
    }
    @Override protected void onDestroy() { if (web != null) { web.removeJavascriptInterface("Banxu"); web.destroy(); web = null; } super.onDestroy(); }
    void notifyWeb() { if (web != null && loaded) web.evaluateJavascript("if(typeof window.onNativeUpdate==='function'){window.onNativeUpdate();}", null); }
    @Override public void onBackPressed() {
        if (web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript("typeof window.onNativeBack==='function' ? window.onNativeBack() : false", result -> { if (!"true".equals(result)) MainActivity.super.onBackPressed(); });
    }
    @Override public void onRequestPermissionsResult(int code, String[] names, int[] results) {
        super.onRequestPermissionsResult(code, names, results);
        if (code == 41) { BanxuApp.IO.execute(() -> ReminderScheduler.rescheduleAll(this)); notifyWeb(); }
    }
    private final class LocalClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return true; }
        @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) { handler.cancel(); }
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) { return asset(req.getUrl()); }
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) { return asset(Uri.parse(url)); }
        @Override public void onPageFinished(WebView view, String url) { loaded = PAGE.equals(url); notifyWeb(); openPendingDestination(); }
        private WebResourceResponse asset(Uri uri) {
            String path = uri.getPath();
            if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost()) || uri.getPort() != -1 || path == null || !path.startsWith("/assets/") || path.contains("..") || path.contains("\\")) return denied();
            String name = path.substring(8); if (name.isEmpty()) return denied();
            String mime = name.endsWith(".html") ? "text/html" : name.endsWith(".js") ? "application/javascript" : name.endsWith(".css") ? "text/css" : name.endsWith(".svg") ? "image/svg+xml" : name.endsWith(".png") ? "image/png" : name.endsWith(".woff2") ? "font/woff2" : "application/octet-stream";
            try {
                Map<String,String> headers = new HashMap<>(); headers.put("Cache-Control", "no-store"); headers.put("X-Content-Type-Options", "nosniff");
                headers.put("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'");
                return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers, getAssets().open(name));
            } catch (Exception ex) { return denied(); }
        }
        private WebResourceResponse denied() { return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", new HashMap<>(), new ByteArrayInputStream(new byte[0])); }
    }
    private interface Action { JSONObject run() throws Exception; }
    private String perform(Action action) {
        try { JSONObject extra = action.run(); return extra == null ? "{\"ok\":true}" : extra.put("ok", true).toString(); }
        catch (IllegalArgumentException error) { return failure(error.getMessage()); }
        catch (Exception error) { return failure("操作未完成，请检查输入或存储空间后重试"); }
    }
    private String failure(String message) { try { return new JSONObject().put("ok", false).put("error", message == null ? "操作失败" : message).toString(); } catch (Exception ignored) { return "{\"ok\":false,\"error\":\"操作失败\"}"; } }
    private JSONObject parse(String json) throws Exception {
        if (json == null || json.length() > 65000) throw new IllegalArgumentException("输入内容过长");
        return new JSONObject(json);
    }
    public final class Bridge {
        @JavascriptInterface public String getState() {
            try { return Repository.state(MainActivity.this).toString(); }
            catch (Exception ex) { return "{\"settings\":{},\"permissions\":{},\"items\":[],\"inbox\":[],\"lastError\":\"数据读取失败，请重启应用\",\"processing\":false,\"version\":\"0.3.0\"}"; }
        }
        @JavascriptInterface public String saveSettings(String json) { return perform(() -> { Repository.saveSettings(MainActivity.this, parse(json)); return null; }); }
        @JavascriptInterface public String ingestManual(String json) { return perform(() -> { Repository.manual(MainActivity.this, parse(json)); return null; }); }
        @JavascriptInterface public String saveItem(String json) { return perform(() -> new JSONObject().put("item", Repository.saveItem(MainActivity.this, parse(json)))); }
        @JavascriptInterface public String deleteItem(String json) { return perform(() -> { Repository.delete(MainActivity.this, Repository.requiredText(parse(json), "id", 100)); return null; }); }
        @JavascriptInterface public String deleteInbox(String json) { return perform(() -> { Repository.deleteInbox(MainActivity.this, Repository.requiredText(parse(json), "id", 100)); return null; }); }
        @JavascriptInterface public String retryInbox(String json) { return perform(() -> { Repository.retry(MainActivity.this, Repository.requiredText(parse(json), "id", 100)); return null; }); }
        @JavascriptInterface public String dismissInbox(String json) { return perform(() -> { Repository.dismiss(MainActivity.this, Repository.requiredText(parse(json), "id", 100)); return null; }); }
        @JavascriptInterface public String seedDemo() { return perform(() -> { Repository.seedDemo(MainActivity.this); return null; }); }
        @JavascriptInterface public String clearDemo() { return perform(() -> { Store.get(MainActivity.this).clearDemo(); BanxuApp.changed(); return null; }); }
        @JavascriptInterface public String processInbox() { return perform(() -> { Repository.process(MainActivity.this); return null; }); }
        @JavascriptInterface public String testApi() { return perform(() -> { Repository.testApi(getApplicationContext()); return null; }); }
        @JavascriptInterface public String requestWidget(String json) {
            return perform(() -> {
                String kind = parse(json).optString("kind");
                if (!"todo".equals(kind) && !"leave".equals(kind)) throw new IllegalArgumentException("小组件类型不存在");
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    try {
                        if (!WidgetUpdater.isPinSupported(MainActivity.this)) reportWidgetPin("unsupported");
                        else reportWidgetPin(WidgetUpdater.pin(MainActivity.this, kind) ? "requested" : "unsupported");
                    } catch (Exception ex) { reportWidgetPin("unavailable"); }
                });
                // The launcher owns the final confirmation; queued never means installed.
                return new JSONObject().put("queued", true);
            });
        }
        @JavascriptInterface public String openSystemSettings(String json) {
            return perform(() -> {
                String page = parse(json).optString("page"); Intent intent;
                switch (page) {
                    case "notificationAccess": intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS); break;
                    case "exactAlarms": intent = Build.VERSION.SDK_INT >= 31 ? new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())) : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())); break;
                    case "battery": intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS); break;
                    case "appNotifications": intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()); break;
                    default: throw new IllegalArgumentException("设置页面不存在");
                }
                runOnUiThread(() -> { try { startActivity(intent); } catch (ActivityNotFoundException ex) { Toast.makeText(MainActivity.this, "请在系统设置中搜索光合待办", Toast.LENGTH_LONG).show(); } });
                return null;
            });
        }
        @JavascriptInterface public String requestNotificationPermission() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
                else { try { startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); } catch (ActivityNotFoundException ignored) {} }
            });
            return "{\"ok\":true}";
        }
    }
}
