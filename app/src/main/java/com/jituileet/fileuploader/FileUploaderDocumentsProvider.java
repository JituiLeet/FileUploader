package com.jituileet.fileuploader;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.net.Uri;
import android.provider.DocumentsProvider;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class FileUploaderDocumentsProvider extends DocumentsProvider {
    private static final String[] DEFAULT_DOCUMENT_PROJECTION = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };
    private static final String ROOT_ID = "internal_storage";
    private static final String DOC_ROOT = "root";
    private File storageRoot;

    @Override
    public boolean onCreate() {
        storageRoot = Environment.getExternalStorageDirectory();
        return storageRoot != null;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        final String[] columns = projection == null ? new String[] {
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.COLUMN_ICON,
                DocumentsContract.Root.COLUMN_SUMMARY,
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        } : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        if (!hasReadPermission() || storageRoot == null || !storageRoot.canRead()) return cursor;
        MatrixCursor.RowBuilder row = cursor.newRow();
        add(row, columns, DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID);
        add(row, columns, DocumentsContract.Root.COLUMN_DOCUMENT_ID, DOC_ROOT);
        add(row, columns, DocumentsContract.Root.COLUMN_TITLE, "FileUploader");
        add(row, columns, DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);
        add(row, columns, DocumentsContract.Root.COLUMN_ICON, R.drawable.ic_launcher);
        add(row, columns, DocumentsContract.Root.COLUMN_SUMMARY, "设备内部存储");
        add(row, columns, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, storageRoot.getFreeSpace());
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        File file = resolve(documentId);
        if (!file.exists()) throw new FileNotFoundException(documentId);
        return documentCursor(file, projection);
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        File parent = resolve(parentDocumentId);
        if (!parent.isDirectory() || !parent.canRead()) throw new FileNotFoundException(parentDocumentId);
        File[] children = parent.listFiles();
        MatrixCursor cursor = new MatrixCursor(projection == null
                ? DEFAULT_DOCUMENT_PROJECTION : projection);
        if (children == null) return cursor;
        List<File> sorted = new ArrayList<>(Arrays.asList(children));
        Collections.sort(sorted, FILE_COMPARATOR);
        for (File child : sorted) {
            if (!child.canRead()) continue;
            includeDocumentRow(cursor, child);
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("只支持读取文件");
        File file = resolve(documentId);
        if (!file.isFile() || !file.canRead()) throw new FileNotFoundException(documentId);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            String parent = resolve(parentDocumentId).getCanonicalPath();
            String child = resolve(documentId).getCanonicalPath();
            return child.startsWith(parent.endsWith(File.separator) ? parent : parent + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    private Cursor documentCursor(File file, String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null
                ? DEFAULT_DOCUMENT_PROJECTION : projection);
        includeDocumentRow(cursor, file);
        return cursor;
    }

    private void includeDocumentRow(MatrixCursor cursor, File file) {
        String id = documentIdFor(file);
        String mime = file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : mimeType(file);
        // Read-only directory enumeration needs no optional document flags.
        int flags = 0;
        MatrixCursor.RowBuilder row = cursor.newRow();
        String[] columns = cursor.getColumnNames();
        add(row, columns, DocumentsContract.Document.COLUMN_DOCUMENT_ID, id);
        add(row, columns, DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.getName().isEmpty() ? "内部存储" : file.getName());
        add(row, columns, DocumentsContract.Document.COLUMN_MIME_TYPE, mime);
        add(row, columns, DocumentsContract.Document.COLUMN_FLAGS, flags);
        add(row, columns, DocumentsContract.Document.COLUMN_SIZE, file.isFile() ? file.length() : null);
        add(row, columns, DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }

    private File resolve(String documentId) throws FileNotFoundException {
        if (storageRoot == null) storageRoot = Environment.getExternalStorageDirectory();
        try {
            File root = storageRoot.getCanonicalFile();
            File candidate;
            if (DOC_ROOT.equals(documentId)) candidate = root;
            else if (documentId.startsWith(DOC_ROOT + "/")) {
                candidate = new File(root, documentId.substring(DOC_ROOT.length() + 1)).getCanonicalFile();
            } else throw new FileNotFoundException(documentId);
            String rootPath = root.getPath();
            String candidatePath = candidate.getPath();
            if (!candidatePath.equals(rootPath) && !candidatePath.startsWith(rootPath + File.separator)) {
                throw new FileNotFoundException("非法路径");
            }
            if (!hasReadPermission()) throw new FileNotFoundException("尚未授予存储读取权限");
            return candidate;
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException(documentId);
        }
    }

    /** Builds a stable content URI for a file or directory selected in our picker. */
    public static Uri buildDocumentUri(Context context, File file) throws FileNotFoundException {
        try {
            File root = Environment.getExternalStorageDirectory().getCanonicalFile();
            File selected = file.getCanonicalFile();
            String rootPath = root.getPath();
            String selectedPath = selected.getPath();
            if (!selectedPath.equals(rootPath)
                    && !selectedPath.startsWith(rootPath + File.separator)) {
                throw new FileNotFoundException("所选路径不在共享存储目录中");
            }
            String id = DOC_ROOT;
            if (!selectedPath.equals(rootPath)) {
                id += "/" + selectedPath.substring(rootPath.length() + 1)
                        .replace(File.separator, "/");
            }
            return DocumentsContract.buildDocumentUri(
                    context.getPackageName() + ".documents", id);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException("无法生成所选文件 URI");
        }
    }

    private String documentIdFor(File file) {
        try {
            String root = storageRoot.getCanonicalPath();
            String path = file.getCanonicalPath();
            if (path.equals(root)) return DOC_ROOT;
            return DOC_ROOT + "/" + path.substring(root.length() + 1).replace(File.separator, "/");
        } catch (Exception e) {
            return DOC_ROOT;
        }
    }

    private boolean hasReadPermission() {
        Context c = getContext();
        return c != null && (android.os.Build.VERSION.SDK_INT < 23
                || c.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED);
    }

    private static String mimeType(File file) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(file.getName());
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
        return mime == null ? "application/octet-stream" : mime;
    }

    private static void add(MatrixCursor.RowBuilder row, String[] columns, String column, Object value) {
        for (String c : columns) if (column.equals(c)) { row.add(value); return; }
    }

    private static final Comparator<File> FILE_COMPARATOR = (a, b) -> {
        if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
        return a.getName().compareToIgnoreCase(b.getName());
    };
}
