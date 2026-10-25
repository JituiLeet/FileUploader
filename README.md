# 文件上传器 / FileUploader

- Package: `com.jituileet.fileuploader`
- Language: Java
- Minimum Android: 7.0 (API 24)
- UI language: follows system by default; can be changed to Simplified Chinese or English.

## Purpose

FileUploader is designed to appear as an option in compatible apps' Android file-upload/content-picker chooser. When selected, it lets the user choose one or multiple files, or select a folder and return its readable files to the calling app. It is not a universal uploader for arbitrary websites: the calling app or website remains responsible for sending the returned files to its server.

## Supported integration

- `ACTION_GET_CONTENT` with `*/*` and `CATEGORY_OPENABLE`
- `ACTION_OPEN_DOCUMENT` with `*/*` and `CATEGORY_OPENABLE`
- Android share intents (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`)
- File and content URI open intents
- Select multiple files and recursively enumerate a selected SAF folder
- Return selected content URIs with read grants and `ClipData`
- Chinese and English UI, follows system language by default

## Folder-upload limitation

Android's standard content-picker result can return multiple file URIs, but it does not define a universal way to transmit each file's relative folder path to every browser or website. FileUploader enumerates a chosen folder and returns its files; preserving directory structure depends on the calling app/site supporting directory-upload metadata. The app cannot force arbitrary websites to accept folder uploads.

The folder traversal uses `DocumentsContract.getTreeDocumentId()` and child document IDs with `buildChildDocumentsUriUsingTree()`. It does not treat a `content://.../tree/...` URI as a document URI, avoiding the previous `Invalid URI` error.

## Build

Open this folder in Android Studio, or use Gradle 8.9 and JDK 17:

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.
