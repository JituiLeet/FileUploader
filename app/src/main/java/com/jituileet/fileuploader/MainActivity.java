package com.jituileet.fileuploader;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_FILES = 1001;
    private static final int REQ_FOLDER = 1002;
    private static final String PREFS = "file_uploader_settings";
    private final ArrayList<UploadItem> selected = new ArrayList<>();
    private final ArrayList<String> taskLines = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText urlInput;
    private LinearLayout selectedList, taskList;
    private Spinner languageSpinner;
    private SharedPreferences prefs;
    private boolean handlingExternal = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        applySavedLocale();
        buildUi();
        handleIncomingIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void applySavedLocale() {
        String lang = getSharedPreferences(PREFS, MODE_PRIVATE).getString("language", "system");
        if (!"system".equals(lang)) {
            Locale locale = "zh".equals(lang) ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
            Locale.setDefault(locale);
            Configuration config = new Configuration(getResources().getConfiguration());
            config.setLocale(locale);
            getResources().updateConfiguration(config, getResources().getDisplayMetrics());
        }
    }

    private void buildUi() {
        int pad = dp(18);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(14), pad, dp(24));
        root.setBackgroundColor(0xFFF5F6FA);
        scroll.addView(root);

        TextView title = text(getString(R.string.app_name), 27, 0xFF17233C, true);
        root.addView(title);
        TextView subtitle = text(getString(R.string.app_subtitle), 14, 0xFF64708A, false);
        root.addView(subtitle, margin(0, 2, 0, 16));

        TextView langLabel = text(getString(R.string.language), 14, 0xFF25324A, true);
        root.addView(langLabel);
        languageSpinner = new Spinner(this);
        String[] langs = {getString(R.string.system_language), getString(R.string.chinese), getString(R.string.english)};
        languageSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, langs));
        String current = prefs.getString("language", "system");
        languageSpinner.setSelection("zh".equals(current) ? 1 : "en".equals(current) ? 2 : 0);
        root.addView(languageSpinner, margin(0, 4, 0, 14));
        languageSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            boolean first = true;
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (first) { first = false; return; }
                String val = position == 1 ? "zh" : position == 2 ? "en" : "system";
                if (!val.equals(prefs.getString("language", "system"))) {
                    prefs.edit().putString("language", val).apply();
                    recreate();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setText(prefs.getString("upload_url", ""));
        urlInput.setHint(getString(R.string.upload_url));
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        urlInput.setBackgroundColor(0xFFFFFFFF);
        root.addView(urlInput, margin(0, 0, 0, 12));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button files = button(getString(R.string.pick_files));
        Button folder = button(getString(R.string.pick_folder));
        actions.addView(files, new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(folder, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(actions, margin(0, 0, 0, 12));
        files.setOnClickListener(v -> openFilePicker());
        folder.setOnClickListener(v -> openFolderPicker());

        root.addView(text(getString(R.string.selected_files), 18, 0xFF17233C, true), margin(0, 8, 0, 6));
        selectedList = new LinearLayout(this);
        selectedList.setOrientation(LinearLayout.VERTICAL);
        root.addView(selectedList);
        Button upload = button(getString(R.string.upload));
        upload.setTextColor(0xFFFFFFFF);
        upload.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF315DA8));
        root.addView(upload, margin(0, 12, 0, 8));
        upload.setOnClickListener(v -> startUploads());

        TextView note = text(getString(R.string.folder_note), 12, 0xFF64708A, false);
        root.addView(note, margin(0, 0, 0, 16));
        root.addView(text(getString(R.string.tasks), 18, 0xFF17233C, true), margin(0, 0, 0, 6));
        taskList = new LinearLayout(this);
        taskList.setOrientation(LinearLayout.VERTICAL);
        root.addView(taskList);
        setContentView(scroll);
        renderSelected();
        renderTasks();
    }

    private void openFilePicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_FILES);
    }

    private void openFolderPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(i, REQ_FOLDER);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_FILES) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) addUri(clip.getItemAt(i).getUri(), displayName(clip.getItemAt(i).getUri()));
            } else if (data.getData() != null) addUri(data.getData(), displayName(data.getData()));
            persistUriPermission(data, false);
            renderSelected();
        } else if (requestCode == REQ_FOLDER) {
            Uri tree = data.getData();
            persistUriPermission(data, true);
            if (tree != null) {
                String rootName = displayName(tree);
                collectTree(tree, tree, rootName);
                renderSelected();
            }
        }
    }

    private void persistUriPermission(Intent data, boolean tree) {
        Uri uri = data.getData();
        if (uri == null) return;
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) { }
    }

    private void collectTree(Uri tree, Uri parent, String relative) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(tree);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent));
            Cursor c = getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                String path = relative + "/" + name;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) collectTree(tree, child, path);
                else addUri(child, path);
            }
            c.close();
        } catch (Exception e) {
            toast(e.getMessage() == null ? getString(R.string.failed) : e.getMessage());
        }
    }

    private void addUri(Uri uri, String path) {
        if (uri == null) return;
        for (UploadItem item : selected) if (item.uri.equals(uri)) return;
        selected.add(new UploadItem(uri, path == null ? "file" : path));
    }

    private String displayName(Uri uri) {
        if (uri == null) return "file";
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { } finally { if (c != null) c.close(); }
        String last = uri.getLastPathSegment();
        return last == null ? "file" : last;
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) addUri(uri, displayName(uri));
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null) parseSharedText(text.toString());
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris != null) for (Uri uri : uris) addUri(uri, displayName(uri));
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            Uri uri = intent.getData();
            if ("fileuploader".equals(uri.getScheme()) || "http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
                String target = uri.getQueryParameter("upload_url");
                if (target != null) urlInput.setText(target);
                if ("file".equals(uri.getScheme()) || "content".equals(uri.getScheme())) addUri(uri, displayName(uri));
            } else addUri(uri, displayName(uri));
        }
        renderSelected();
    }

    private void parseSharedText(String text) {
        try {
            Uri maybe = Uri.parse(text.trim());
            if ("http".equals(maybe.getScheme()) || "https".equals(maybe.getScheme())) {
                String target = maybe.getQueryParameter("upload_url");
                if (target != null && urlInput != null) urlInput.setText(target);
            }
        } catch (Exception ignored) { }
    }

    private void startUploads() {
        final String endpoint = urlInput.getText().toString().trim();
        if (!(endpoint.startsWith("https://") || endpoint.startsWith("http://"))) { toast(getString(R.string.url_required)); return; }
        if (selected.isEmpty()) { toast(getString(R.string.files_required)); return; }
        prefs.edit().putString("upload_url", endpoint).apply();
        ArrayList<UploadItem> batch = new ArrayList<>(selected);
        for (UploadItem item : batch) {
            final int index = taskLines.size();
            taskLines.add(item.path + " — " + getString(R.string.ready));
            renderTasks();
            executor.execute(() -> {
                updateTask(index, item.path + " — " + getString(R.string.uploading));
                try {
                    uploadOne(endpoint, item);
                    updateTask(index, "✓ " + item.path + " — " + getString(R.string.success));
                } catch (Exception e) {
                    String message = e.getMessage() == null ? "HTTP error" : e.getMessage();
                    updateTask(index, "✕ " + item.path + " — " + getString(R.string.failed) + ": " + message);
                }
            });
        }
        selected.clear();
        renderSelected();
        toast(getString(R.string.upload_started));
    }

    private void uploadOne(String endpoint, UploadItem item) throws Exception {
        String boundary = "----FileUploader" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        try {
            OutputStream out = new BufferedOutputStream(conn.getOutputStream());
            writeField(out, boundary, "relative_path", item.path);
            writeField(out, boundary, "filename", item.path.substring(item.path.lastIndexOf('/') + 1));
            String name = item.path.substring(item.path.lastIndexOf('/') + 1).replace("\"", "_");
            String mime = getContentResolver().getType(item.uri);
            if (mime == null) mime = "application/octet-stream";
            writeAscii(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\nContent-Type: " + mime + "\r\n\r\n");
            InputStream in = new BufferedInputStream(getContentResolver().openInputStream(item.uri));
            byte[] buffer = new byte[64 * 1024]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            in.close();
            writeAscii(out, "\r\n--" + boundary + "--\r\n");
            out.flush(); out.close();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                InputStream err = conn.getErrorStream();
                String body = err == null ? "" : readSmall(err);
                throw new IOException("HTTP " + code + (body.isEmpty() ? "" : ": " + body));
            }
        } finally { conn.disconnect(); }
    }

    private String readSmall(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[1024]; int n;
        while ((n = in.read(buf)) != -1 && b.size() < 4096) b.write(buf, 0, n);
        in.close(); return b.toString("UTF-8");
    }
    private void writeField(OutputStream out, String boundary, String name, String value) throws IOException {
        writeAscii(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }
    private void writeAscii(OutputStream out, String value) throws IOException { out.write(value.getBytes("UTF-8")); }

    private void updateTask(int index, String line) {
        runOnUiThread(() -> { if (index >= 0 && index < taskLines.size()) taskLines.set(index, line); renderTasks(); });
    }
    private void renderSelected() {
        if (selectedList == null) return;
        selectedList.removeAllViews();
        if (selected.isEmpty()) { selectedList.addView(text(getString(R.string.no_files), 14, 0xFF7A8499, false)); return; }
        for (int i = 0; i < selected.size(); i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(10), dp(8), dp(8), dp(8)); row.setBackgroundColor(0xFFFFFFFF);
            TextView name = text("📄  " + selected.get(i).path, 14, 0xFF25324A, false);
            name.setMaxLines(3); row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button remove = new Button(this); remove.setText("×"); remove.setMinWidth(dp(36)); remove.setPadding(0, 0, 0, 0);
            remove.setOnClickListener(v -> { selected.remove(idx); renderSelected(); }); row.addView(remove);
            LinearLayout.LayoutParams lp = margin(0, 0, 0, 4); selectedList.addView(row, lp);
        }
        Button clear = button(getString(R.string.clear)); clear.setOnClickListener(v -> { selected.clear(); renderSelected(); }); selectedList.addView(clear);
    }
    private void renderTasks() {
        if (taskList == null) return;
        taskList.removeAllViews();
        if (taskLines.isEmpty()) { taskList.addView(text("—", 14, 0xFF7A8499, false)); return; }
        for (String line : taskLines) {
            TextView tv = text(line, 13, 0xFF25324A, false); tv.setPadding(dp(10), dp(10), dp(10), dp(10)); tv.setBackgroundColor(0xFFFFFFFF);
            taskList.addView(tv, margin(0, 0, 0, 4));
        }
    }
    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }
    private Button button(String value) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(14); b.setMinHeight(dp(44)); return b;
    }
    private LinearLayout.LayoutParams margin(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return p;
    }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    @Override protected void onDestroy() { super.onDestroy(); }

    private static class UploadItem {
        final Uri uri; final String path;
        UploadItem(Uri uri, String path) { this.uri = uri; this.path = path; }
    }
}
