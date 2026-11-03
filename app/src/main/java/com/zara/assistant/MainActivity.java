package com.zara.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_SPEECH = 401;
    private static final int REQ_MIC = 402;
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private android.content.SharedPreferences prefs;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("zara_notes", MODE_PRIVATE);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "ZaraAndroid");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(new Locale("te", "IN"));
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(new Locale("en", "IN"));
                }
                // Prefer a female voice when the device has one available.
                try {
                    for (android.speech.tts.Voice v : tts.getVoices()) {
                        String n = v.getName().toLowerCase(Locale.ROOT);
                        if (!v.isNetworkConnectionRequired() && (n.contains("female") || n.contains("woman") || n.contains("feminine"))) { tts.setVoice(v); break; }
                    }
                } catch (Exception ignored) {}
                ttsReady = true;
            }
        });
    }

    private void js(String function, String value) {
        if (web == null) return;
        String safe = JSONObject.quote(value == null ? "" : value);
        runOnUiThread(() -> web.evaluateJavascript(function + "(" + safe + ")", null));
    }

    public class Bridge {
        @JavascriptInterface public String getNotes() {
            try { return prefs.getString("notes", "[]"); } catch (Exception e) { return "[]"; }
        }
        @JavascriptInterface public String saveNote(String id, String title, String body) {
            synchronized (MainActivity.this) {
                try {
                    JSONArray arr = new JSONArray(prefs.getString("notes", "[]"));
                    String noteId = (id == null || id.isEmpty()) ? UUID.randomUUID().toString() : id;
                    JSONArray next = new JSONArray(); boolean found = false;
                    JSONObject item = new JSONObject(); item.put("id", noteId); item.put("title", title == null ? "Untitled" : title); item.put("body", body == null ? "" : body); item.put("updated", System.currentTimeMillis());
                    for (int i=0;i<arr.length();i++) { JSONObject old=arr.getJSONObject(i); if (noteId.equals(old.optString("id"))) { next.put(item); found=true; } else next.put(old); }
                    if (!found) next.put(0, item);
                    prefs.edit().putString("notes", next.toString()).apply(); return item.toString();
                } catch (Exception e) { return "{\"error\":\"Could not save note\"}"; }
            }
        }
        @JavascriptInterface public boolean deleteNote(String id) {
            synchronized (MainActivity.this) {
                try { JSONArray arr=new JSONArray(prefs.getString("notes","[]")); JSONArray next=new JSONArray(); for(int i=0;i<arr.length();i++){JSONObject n=arr.getJSONObject(i);if(!id.equals(n.optString("id")))next.put(n);} prefs.edit().putString("notes",next.toString()).apply(); return true; } catch(Exception e){return false;}
            }
        }
        @JavascriptInterface public void speak(String text) {
            runOnUiThread(() -> { if (ttsReady && tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "zara-utterance"); else Toast.makeText(MainActivity.this, "Speech engine is starting. Try again.", Toast.LENGTH_SHORT).show(); });
        }
        @JavascriptInterface public void listen() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC); return; }
                startSpeechIntent();
            });
        }
        @JavascriptInterface public void launchApp(String appName) { runOnUiThread(() -> launchAppInternal(appName)); }
        @JavascriptInterface public void playMusic(String query) { runOnUiThread(() -> playMusicInternal(query)); }
        @JavascriptInterface public void askAI(String baseUrl, String provider, String message) {
            executor.execute(() -> {
                HttpURLConnection c = null;
                try {
                    String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
                    if (base.isEmpty()) { js("onAIReply", "Offline mode: I can manage notes, open apps, and start music. Set your ZARA server URL in Settings to enable full AI chat."); return; }
                    URL url = new URL(base + "/api/chat"); c = (HttpURLConnection) url.openConnection(); c.setRequestMethod("POST"); c.setConnectTimeout(12000); c.setReadTimeout(90000); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    JSONObject body = new JSONObject(); body.put("message", message); body.put("provider", provider == null || provider.isEmpty() ? "ollama" : provider);
                    try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                    int code = c.getResponseCode(); BufferedReader r = new BufferedReader(new InputStreamReader(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream(), StandardCharsets.UTF_8)); StringBuilder sb=new StringBuilder(); String line; while((line=r.readLine())!=null)sb.append(line);
                    JSONObject data = new JSONObject(sb.toString()); String reply=data.optString("reply", data.optString("error", "The AI server returned no reply.")); js("onAIReply", reply);
                } catch(Exception e) { js("onAIReply", "Could not reach the AI server. Check the server URL and internet connection. " + e.getMessage()); }
                finally { if(c!=null)c.disconnect(); }
            });
        }
        @JavascriptInterface public String getAppVersion() { return "ZARA Android 1.0"; }
    }

    private void startSpeechIntent() {
        try { Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH); i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "te-IN"); i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to ZARA"); startActivityForResult(i, REQ_SPEECH); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "Speech recognition is not available on this phone.", Toast.LENGTH_LONG).show(); }
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) { super.onRequestPermissionsResult(requestCode, permissions, results); if(requestCode==REQ_MIC && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) startSpeechIntent(); }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQ_SPEECH && resultCode==RESULT_OK && data!=null){ ArrayList<String> results=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS); if(results!=null&&!results.isEmpty()) js("onVoiceResult",results.get(0)); }
    }
    private void launchAppInternal(String raw) {
        String name = raw == null ? "" : raw.toLowerCase(Locale.ROOT).trim();
        String pkg = null;
        if(name.contains("whatsapp")) pkg="com.whatsapp";
        else if(name.contains("youtube music")) pkg="com.google.android.apps.youtube.music";
        else if(name.contains("youtube")) pkg="com.google.android.youtube";
        else if(name.contains("spotify")) pkg="com.spotify.music";
        else if(name.contains("chrome")) pkg="com.google.android.chrome";
        else if(name.contains("maps")) pkg="com.google.android.apps.maps";
        else if(name.contains("phone" )||name.contains("dialer")) pkg="com.google.android.dialer";
        else if(name.contains("settings")) pkg="com.android.settings";
        try {
            Intent i = pkg == null ? null : getPackageManager().getLaunchIntentForPackage(pkg);
            if(i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return; }
            Intent search = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode("open " + raw + " app"))); startActivity(search);
        } catch(Exception e) { Toast.makeText(this,"Could not open " + raw,Toast.LENGTH_SHORT).show(); }
    }
    private void playMusicInternal(String query) {
        String q = query == null ? "" : query.trim();
        try {
            if(q.isEmpty() || q.equalsIgnoreCase("my songs") || q.equalsIgnoreCase("music")) {
                Intent i = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.youtube.music");
                if(i==null) i=getPackageManager().getLaunchIntentForPackage("com.spotify.music");
                if(i==null) i=new Intent(Intent.ACTION_VIEW,Uri.parse("https://music.youtube.com/"));
                startActivity(i);
            } else {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q="+Uri.encode(q))); startActivity(i);
            }
        } catch(Exception e) { try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.youtube.com/results?search_query="+Uri.encode(q.isEmpty()?"music":q)))); } catch(Exception ignored){} }
    }
    @Override public void onBackPressed() { if(web!=null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() { if(tts!=null){tts.stop();tts.shutdown();} executor.shutdownNow(); if(web!=null)web.destroy(); super.onDestroy(); }
}
