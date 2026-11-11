package com.jituileet.fileuploader;

import android.Manifest;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_STORAGE = 1001;
    private static final String FILE_PROVIDER_SUFFIX = ".fileprovider";

    private TextView pathView;
    private TextView hintView;
    private ListView listView;
    private Button upButton;
    private Button confirmButton;
    private final ArrayList<File> visibleItems = new ArrayList<>();
    private final ArrayList<File> folderStack = new ArrayList<>();
    private final LinkedHashMap<String, File> selectedFiles = new LinkedHashMap<>();
    private final LinkedHashMap<String, File> manuallySelectedFiles = new LinkedHashMap<>();
    private final LinkedHashMap<String, LinkedHashSet<String>> folderSelections = new LinkedHashMap<>();
    private final Map<String, File> fileCache = new LinkedHashMap<>();
    private final Set<String> pendingFolders = new HashSet<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private File storageRoot;
    private File currentFolder;
    private ItemAdapter adapter;
    private ProgressDialog progressDialog;
    private String requestedAction;
    private String requestedMime = "*/*";
    private boolean launchedAsPicker;
    // A directory is returned as one DocumentsProvider content URI so Chromium can
    // enumerate it recursively and calculate each file's webkitRelativePath.
    private File selectedFolder;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent incoming = getIntent();
        requestedAction = incoming == null ? null : incoming.getAction();
        requestedMime = incoming != null && incoming.getType() != null ? incoming.getType() : "*/*";
        launchedAsPicker = Intent.ACTION_GET_CONTENT.equals(requestedAction)
                || Intent.ACTION_OPEN_DOCUMENT.equals(requestedAction);
        buildInterface();
        if (hasStoragePermission()) openStorageRoot();
        else requestStoragePermission();
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.setBackgroundColor(0xFFF7F8FA);

        TextView title = new TextView(this);
        title.setText("文件上传器");
        title.setTextColor(0xFF17202A);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(46)));

        pathView = new TextView(this);
        pathView.setTextColor(0xFF52616B);
        pathView.setTextSize(13);
        root.addView(pathView, new LinearLayout.LayoutParams(-1, dp(32)));

        hintView = new TextView(this);
        hintView.setTextColor(0xFF64748B);
        hintView.setTextSize(12);
        root.addView(hintView, new LinearLayout.LayoutParams(-1, dp(30)));

        listView = new ListView(this);
        listView.setDividerHeight(dp(1));
        adapter = new ItemAdapter();
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        upButton = makeButton("返回上一级");
        confirmButton = makeButton("确认选择 (0)");
        actions.addView(upButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(confirmButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(actions);

        upButton.setOnClickListener(v -> goUp());
        confirmButton.setOnClickListener(v -> confirmSelection());
        listView.setOnItemClickListener((parent, view, position, id) -> {
            File item = visibleItems.get(position);
            if (item.isDirectory()) enterFolder(item);
            else toggleFile(item);
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            File item = visibleItems.get(position);
            if (item.isDirectory()) toggleFolder(item);
            else toggleFile(item);
            return true;
        });
        setContentView(root);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        return b;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_STORAGE);
        } else {
            openStorageRoot();
        }
    }

    private boolean hasStoragePermission() {
        return Build.VERSION.SDK_INT < 23
                || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE) {
            if (hasStoragePermission()) openStorageRoot();
            else {
                hintView.setText("需要允许访问设备文件，才能浏览和上传文件。");
                Toast.makeText(this, "未获得存储读取权限", Toast.LENGTH_LONG).show();
                if (launchedAsPicker) {
                    setResult(RESULT_CANCELED);
                    finish();
                }
            }
        }
    }

    private void openStorageRoot() {
        storageRoot = Environment.getExternalStorageDirectory();
        if (storageRoot == null || !storageRoot.exists() || !storageRoot.isDirectory()) {
            hintView.setText("无法访问设备共享存储空间");
            return;
        }
        folderStack.clear();
        folderStack.add(storageRoot);
        currentFolder = storageRoot;
        refreshList();
    }

    private void enterFolder(File folder) {
        if (folder == null || !folder.isDirectory() || !folder.canRead()) {
            Toast.makeText(this, "无法读取此文件夹", Toast.LENGTH_SHORT).show();
            return;
        }
        currentFolder = folder;
        folderStack.add(folder);
        refreshList();
    }

    private void goUp() {
        if (folderStack.size() <= 1) return;
        folderStack.remove(folderStack.size() - 1);
        currentFolder = folderStack.get(folderStack.size() - 1);
        refreshList();
    }

    private void refreshList() {
        visibleItems.clear();
        if (currentFolder != null) {
            File[] children = currentFolder.listFiles();
            if (children != null) {
                ArrayList<File> sorted = new ArrayList<>();
                Collections.addAll(sorted, children);
                Collections.sort(sorted, FILE_COMPARATOR);
                visibleItems.addAll(sorted);
            }
        }
        pathView.setText(currentFolder == null ? "设备存储" : displayPath(currentFolder));
        int selectedCount = selectedFolder != null ? 1 : selectedFiles.size();
        hintView.setText(selectedFolder != null
                ? "已选择文件夹：" + selectedFolder.getName() + "（将保留内部目录结构）"
                : "直接浏览设备文件；勾选文件或长按文件夹加入上传列表。已选择 "
                + selectedCount + " 项");
        upButton.setEnabled(folderStack.size() > 1);
        confirmButton.setText("确认选择 (" + selectedCount + ")");
        confirmButton.setEnabled(selectedFolder != null || !selectedFiles.isEmpty());
        adapter.notifyDataSetChanged();
    }

    private String displayPath(File folder) {
        if (storageRoot == null) return folder.getName();
        String rootPath = storageRoot.getAbsolutePath();
        String path = folder.getAbsolutePath();
        if (path.equals(rootPath)) return "内部存储";
        return "内部存储 / " + path.substring(rootPath.length()).replace(File.separator, " / ");
    }

    private static final Comparator<File> FILE_COMPARATOR = (a, b) -> {
        if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
        return a.getName().compareToIgnoreCase(b.getName());
    };

    private void toggleFile(File file) {
        // Mixing individual files with a directory would flatten the directory upload.
        // Selecting a file therefore switches back to ordinary multi-file selection.
        selectedFolder = null;
        String key = canonicalPath(file);
        if (manuallySelectedFiles.containsKey(key)) manuallySelectedFiles.remove(key);
        else if (matchesRequestedMime(file)) {
            manuallySelectedFiles.put(key, file);
            fileCache.put(key, file);
        } else {
            Toast.makeText(this, "该文件类型不符合当前网页的选择要求", Toast.LENGTH_SHORT).show();
            return;
        }
        rebuildSelection();
        refreshList();
    }

    private void toggleFolder(File folder) {
        if (folder == null || !folder.isDirectory() || !folder.canRead()) {
            Toast.makeText(this, "无法读取此文件夹", Toast.LENGTH_SHORT).show();
            return;
        }
        String key = canonicalPath(folder);
        if (selectedFolder != null && canonicalPath(selectedFolder).equals(key)) {
            selectedFolder = null;
        } else {
            // Chromium's directory-upload path expects one directory content URI.
            // Returning the directory itself lets Chromium recursively enumerate
            // children instead of receiving a flattened list of unrelated file URIs.
            selectedFolder = folder;
            manuallySelectedFiles.clear();
            folderSelections.clear();
            selectedFiles.clear();
        }
        refreshList();
    }

    private void collectFilesInStableOrder(File root, List<File> output) {
        Deque<Iterator<File>> stack = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        File[] first = safeList(root);
        if (first == null) return;
        ArrayList<File> firstSorted = new ArrayList<>();
        Collections.addAll(firstSorted, first);
        Collections.sort(firstSorted, FILE_COMPARATOR);
        stack.push(firstSorted.iterator());
        while (!stack.isEmpty()) {
            Iterator<File> it = stack.peek();
            if (!it.hasNext()) {
                stack.pop();
                continue;
            }
            File child = it.next();
            if (child.isDirectory()) {
                String key = canonicalPath(child);
                if (!visited.add(key)) continue;
                File[] nested = safeList(child);
                if (nested != null) {
                    ArrayList<File> sorted = new ArrayList<>();
                    Collections.addAll(sorted, nested);
                    Collections.sort(sorted, FILE_COMPARATOR);
                    stack.push(sorted.iterator());
                }
            } else if (child.isFile() && matchesRequestedMime(child)) {
                output.add(child);
            }
        }
    }

    private File[] safeList(File folder) {
        try { return folder.listFiles(); }
        catch (SecurityException e) { return null; }
    }

    private void rebuildSelection() {
        selectedFiles.clear();
        if (selectedFolder != null) return;
        selectedFiles.putAll(manuallySelectedFiles);
        for (Set<String> paths : folderSelections.values()) {
            for (String path : paths) {
                File f = fileCache.get(path);
                if (f != null) selectedFiles.putIfAbsent(path, f);
            }
        }
    }

    private boolean matchesRequestedMime(File file) {
        if (requestedMime == null || "*/*".equals(requestedMime)) return true;
        String ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(file.getName());
        String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
        if (mime == null) return false;
        if (requestedMime.endsWith("/*")) {
            return mime.startsWith(requestedMime.substring(0, requestedMime.length() - 1));
        }
        return requestedMime.equalsIgnoreCase(mime);
    }

    private void setBusy(boolean busy) {
        listView.setEnabled(!busy);
        upButton.setEnabled(!busy && folderStack.size() > 1);
        confirmButton.setEnabled(!busy && (selectedFolder != null || !selectedFiles.isEmpty()));
    }

    private void showProgress() {
        progressDialog = new ProgressDialog(this);
        progressDialog.setTitle("正在读取文件夹");
        progressDialog.setMessage("正在整理文件列表，请稍候…");
        progressDialog.setIndeterminate(true);
        progressDialog.setCancelable(false);
        progressDialog.show();
    }

    private void hideProgress() {
        if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
        progressDialog = null;
    }

    private void confirmSelection() {
        if (selectedFolder == null && selectedFiles.isEmpty()) return;
        if (!launchedAsPicker) {
            Toast.makeText(this, "已选择 " + selectedFiles.size() + " 个文件", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (selectedFolder != null) {
                Uri directoryUri = FileUploaderDocumentsProvider.buildDocumentUri(this, selectedFolder);
                Intent result = new Intent();
                result.setData(directoryUri);
                result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                setResult(RESULT_OK, result);
                finish();
                return;
            }
            ArrayList<Uri> uris = new ArrayList<>();
            for (File file : selectedFiles.values()) {
                uris.add(FileProvider.getUriForFile(this, getPackageName() + FILE_PROVIDER_SUFFIX, file));
            }
            Intent result = new Intent();
            result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (uris.size() == 1) {
                result.setDataAndType(uris.get(0), requestedMime);
            } else {
                result.setType(requestedMime);
                ClipData clip = ClipData.newUri(getContentResolver(), "上传文件", uris.get(0));
                for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
                result.setClipData(clip);
                result.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            setResult(RESULT_OK, result);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "无法准备上传文件：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String canonicalPath(File file) {
        try { return file.getCanonicalPath(); }
        catch (Exception e) { return file.getAbsolutePath(); }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        hideProgress();
        executor.shutdownNow();
        super.onDestroy();
    }

    private class ItemAdapter extends ArrayAdapter<File> {
        ItemAdapter() { super(MainActivity.this, android.R.layout.simple_list_item_1, visibleItems); }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            File item = visibleItems.get(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(5), dp(8), dp(5));

            TextView icon = new TextView(MainActivity.this);
            icon.setText(item.isDirectory() ? "📁" : "📄");
            icon.setTextSize(22);
            row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(48)));

            LinearLayout labels = new LinearLayout(MainActivity.this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(MainActivity.this);
            name.setText(item.getName());
            name.setTextColor(0xFF1F2937);
            name.setTextSize(15);
            TextView detail = new TextView(MainActivity.this);
            detail.setTextColor(0xFF718096);
            detail.setTextSize(11);
            detail.setText(item.isDirectory() ? "文件夹" : formatSize(item.length()));
            labels.addView(name);
            labels.addView(detail);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

            CheckBox check = new CheckBox(MainActivity.this);
            check.setFocusable(false);
            check.setChecked(item.isDirectory()
                    ? selectedFolder != null && canonicalPath(selectedFolder).equals(canonicalPath(item))
                    : selectedFiles.containsKey(canonicalPath(item)));
            check.setOnClickListener(v -> {
                if (item.isDirectory()) toggleFolder(item);
                else toggleFile(item);
            });
            row.addView(check);
            return row;
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
