package com.jituileet.fileuploader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_TREE = 1001;
    private static final String PREFS = "file_uploader";
    private static final String KEY_TREE = "tree_uri";

    private LinearLayout rootLayout;
    private TextView pathView;
    private TextView hintView;
    private ListView listView;
    private Button chooseRootButton;
    private Button chooseFolderButton;
    private Button selectButton;

    private DocumentFile treeRoot;
    private DocumentFile currentFolder;
    private final ArrayList<DocumentFile> folderStack = new ArrayList<>();
    private final ArrayList<DocumentFile> visibleItems = new ArrayList<>();
    private final Set<String> selectedUris = new HashSet<>();
    private final ArrayList<DocumentFile> selectedFiles = new ArrayList<>();
    private ItemAdapter adapter;
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ProgressDialog scanDialog;

    private String requestedAction;
    private String requestedMime = "*/*";
    private boolean launchedAsPicker;
    private boolean folderTreeRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestedAction = getIntent() != null ? getIntent().getAction() : null;
        requestedMime = getIntent() != null && getIntent().getType() != null
                ? getIntent().getType() : "*/*";
        launchedAsPicker = Intent.ACTION_GET_CONTENT.equals(requestedAction)
                || Intent.ACTION_OPEN_DOCUMENT.equals(requestedAction)
                || Intent.ACTION_OPEN_DOCUMENT_TREE.equals(requestedAction);
        folderTreeRequest = Intent.ACTION_OPEN_DOCUMENT_TREE.equals(requestedAction);

        buildInterface();
        restoreTree();
    }

    private void buildInterface() {
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setPadding(dp(16), dp(12), dp(16), dp(12));
        rootLayout.setBackgroundColor(0xFFF7F8FA);

        TextView title = new TextView(this);
        title.setText("文件上传器");
        title.setTextSize(23);
        title.setTextColor(0xFF17202A);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, dp(4), 0, dp(12));
        rootLayout.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));

        pathView = new TextView(this);
        pathView.setTextColor(0xFF52616B);
        pathView.setTextSize(13);
        pathView.setPadding(0, dp(4), 0, dp(8));
        rootLayout.addView(pathView);

        hintView = new TextView(this);
        hintView.setTextColor(0xFF64748B);
        hintView.setTextSize(13);
        hintView.setText("请选择存储位置，然后浏览文件");
        rootLayout.addView(hintView);

        listView = new ListView(this);
        listView.setDividerHeight(dp(1));
        adapter = new ItemAdapter();
        listView.setAdapter(adapter);
        rootLayout.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        chooseRootButton = makeButton("选择存储位置");
        chooseFolderButton = makeButton("选择当前文件夹");
        selectButton = makeButton("返回所选文件");
        actions.addView(chooseRootButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(chooseFolderButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(selectButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        rootLayout.addView(actions);

        chooseRootButton.setOnClickListener(v -> openTreePicker());
        chooseFolderButton.setOnClickListener(v -> selectCurrentFolderContents());
        selectButton.setOnClickListener(v -> finishWithSelectedFiles());

        listView.setOnItemClickListener((parent, view, position, id) -> {
            DocumentFile item = visibleItems.get(position);
            if (item.isDirectory()) {
                folderStack.add(item);
                currentFolder = item;
                refreshList();
            } else {
                toggleSelection(item);
            }
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            DocumentFile item = visibleItems.get(position);
            if (item.isFile()) {
                toggleSelection(item);
                return true;
            }
            return false;
        });

        setContentView(rootLayout);
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11);
        button.setAllCaps(false);
        return button;
    }

    private void restoreTree() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(KEY_TREE, null);
        if (saved != null) {
            try {
                Uri uri = Uri.parse(saved);
                treeRoot = DocumentFile.fromTreeUri(this, uri);
                if (treeRoot != null && treeRoot.exists()) {
                    currentFolder = treeRoot;
                    folderStack.clear();
                    folderStack.add(treeRoot);
                    refreshList();
                    return;
                }
            } catch (Exception ignored) { }
        }
        updateEmptyState();
        if (launchedAsPicker) {
            hintView.setText("首次使用需要授权访问一个存储文件夹");
            openTreePicker();
        }
    }

    private void openTreePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_TREE);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开系统文件夹选择器", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_TREE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, flags);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_TREE, uri.toString()).apply();
                treeRoot = DocumentFile.fromTreeUri(this, uri);
                currentFolder = treeRoot;
                folderStack.clear();
                folderStack.add(treeRoot);
                selectedUris.clear();
                selectedFiles.clear();
                refreshList();
                if (folderTreeRequest) {
                    finishWithFolderUri(uri);
                }
            } catch (Exception e) {
                Toast.makeText(this, "无法访问所选文件夹：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void refreshList() {
        visibleItems.clear();
        if (currentFolder != null && currentFolder.isDirectory()) {
            DocumentFile[] children = currentFolder.listFiles();
            ArrayList<DocumentFile> sorted = new ArrayList<>();
            Collections.addAll(sorted, children);
            Collections.sort(sorted, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                String an = a.getName() == null ? "" : a.getName();
                String bn = b.getName() == null ? "" : b.getName();
                return an.compareToIgnoreCase(bn);
            });
            visibleItems.addAll(sorted);
        }
        StringBuilder path = new StringBuilder("位置：");
        if (folderStack.size() > 1) path.append("… / ");
        path.append(currentFolder != null && currentFolder.getName() != null
                ? currentFolder.getName() : "存储空间");
        pathView.setText(path.toString());
        hintView.setText("点击文件夹进入；点击文件选择，长按文件也可选择。已选择 "
                + selectedFiles.size() + " 个文件");
        adapter.notifyDataSetChanged();
        updateButtons();
    }

    private void updateEmptyState() {
        pathView.setText("尚未选择存储位置");
        hintView.setText("点击“选择存储位置”，授权访问设备中的文件夹");
        updateButtons();
    }

    private void updateButtons() {
        chooseFolderButton.setEnabled(currentFolder != null);
        selectButton.setEnabled(!selectedFiles.isEmpty());
    }

    private void toggleSelection(DocumentFile file) {
        String key = file.getUri().toString();
        if (selectedUris.contains(key)) {
            selectedUris.remove(key);
            for (int i = selectedFiles.size() - 1; i >= 0; i--) {
                if (selectedFiles.get(i).getUri().toString().equals(key)) {
                    selectedFiles.remove(i);
                }
            }
        } else {
            if (matchesRequestedMime(file)) {
                selectedUris.add(key);
                selectedFiles.add(file);
            } else {
                Toast.makeText(this, "该文件类型与调用应用请求的类型不匹配", Toast.LENGTH_SHORT).show();
            }
        }
        refreshList();
    }

    private boolean matchesRequestedMime(DocumentFile file) {
        if (requestedMime == null || "*/*".equals(requestedMime)) return true;
        String name = file.getName();
        if (name == null) return true;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) ext = name.substring(dot + 1).toLowerCase();
        String guessed = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext);
        if (guessed == null) return false;
        if (requestedMime.endsWith("/*")) {
            return guessed.startsWith(requestedMime.substring(0, requestedMime.length() - 1));
        }
        return requestedMime.equalsIgnoreCase(guessed);
    }

    private void selectCurrentFolderContents() {
        if (currentFolder == null) return;

        final DocumentFile folderToScan = currentFolder;
        chooseRootButton.setEnabled(false);
        chooseFolderButton.setEnabled(false);
        selectButton.setEnabled(false);

        scanDialog = new ProgressDialog(this);
        scanDialog.setTitle("正在扫描文件夹");
        scanDialog.setMessage("正在查找文件，请稍候…");
        scanDialog.setIndeterminate(true);
        scanDialog.setCancelable(false);
        scanDialog.show();

        scanExecutor.execute(() -> {
            ArrayList<DocumentFile> files = new ArrayList<>();
            collectFiles(folderToScan, files);

            ArrayList<DocumentFile> matchedFiles = new ArrayList<>();
            for (DocumentFile file : files) {
                if (matchesRequestedMime(file)) matchedFiles.add(file);
            }

            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (scanDialog != null && scanDialog.isShowing()) scanDialog.dismiss();
                scanDialog = null;

                chooseRootButton.setEnabled(true);
                updateButtons();

                if (matchedFiles.isEmpty()) {
                    Toast.makeText(this, "当前文件夹中没有可上传的文件", Toast.LENGTH_LONG).show();
                    return;
                }

                selectedFiles.clear();
                selectedUris.clear();
                for (DocumentFile file : matchedFiles) {
                    selectedFiles.add(file);
                    selectedUris.add(file.getUri().toString());
                }

                refreshList();
                Toast.makeText(this, "已找到 " + selectedFiles.size() + " 个文件", Toast.LENGTH_SHORT).show();

                if (launchedAsPicker && !folderTreeRequest) finishWithSelectedFiles();
            });
        });
    }

    // Scan outside the UI thread and use an explicit stack to avoid deep-recursion crashes.
    private void collectFiles(DocumentFile directory, List<DocumentFile> output) {
        ArrayDeque<DocumentFile> pending = new ArrayDeque<>();
        pending.push(directory);
        while (!pending.isEmpty()) {
            DocumentFile current = pending.pop();
            DocumentFile[] children;
            try {
                children = current.listFiles();
            } catch (Exception e) {
                continue;
            }
            for (DocumentFile child : children) {
                try {
                    if (child.isDirectory()) pending.push(child);
                    else if (child.isFile()) output.add(child);
                } catch (Exception ignored) { }
            }
        }
    }

    private void finishWithSelectedFiles() {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "请先选择文件", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        for (DocumentFile file : selectedFiles) uris.add(file.getUri());
        Intent result = new Intent();
        result.setAction(requestedAction);
        result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (uris.size() == 1) {
            result.setDataAndType(uris.get(0), requestedMime);
        } else {
            // Multi-file result: use ClipData + EXTRA_ALLOW_MULTIPLE only.
            // Setting data to the first URI can cause clients to treat the folder
            // selection as a single-file result and ignore the remaining items.
            result.setType(requestedMime);
            ClipData clip = ClipData.newUri(getContentResolver(), "上传文件", uris.get(0));
            for (int i = 1; i < uris.size(); i++) {
                clip.addItem(new ClipData.Item(uris.get(i)));
            }
            result.setClipData(clip);
            result.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        result.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        setResult(RESULT_OK, result);
        finish();
    }

    private void finishWithFolderUri(Uri uri) {
        Intent result = new Intent();
        result.setData(uri);
        result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (scanDialog != null && scanDialog.isShowing()) scanDialog.dismiss();
        scanExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (folderStack.size() > 1) {
            folderStack.remove(folderStack.size() - 1);
            currentFolder = folderStack.get(folderStack.size() - 1);
            refreshList();
        } else if (launchedAsPicker) {
            setResult(RESULT_CANCELED);
            finish();
        } else {
            super.onBackPressed();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private class ItemAdapter extends ArrayAdapter<DocumentFile> {
        ItemAdapter() {
            super(MainActivity.this, android.R.layout.simple_list_item_1, visibleItems);
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(7), dp(8), dp(7));

            DocumentFile item = visibleItems.get(position);
            TextView icon = new TextView(MainActivity.this);
            icon.setText(item.isDirectory() ? "📁" : "📄");
            icon.setTextSize(22);
            row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(48)));

            LinearLayout labels = new LinearLayout(MainActivity.this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(MainActivity.this);
            name.setText(item.getName() == null ? "未命名" : item.getName());
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
            check.setClickable(false);
            check.setChecked(selectedUris.contains(item.getUri().toString()));
            check.setVisibility(item.isFile() ? View.VISIBLE : View.INVISIBLE);
            row.addView(check);
            return row;
        }
    }

    private String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(java.util.Locale.getDefault(), "%.1f KB", size / 1024.0);
        return String.format(java.util.Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024.0));
    }
}
