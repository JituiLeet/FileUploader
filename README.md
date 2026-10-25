# 文件上传器 / FileUploader

- Package: `com.jituileet.fileuploader`
- Language: Java
- Minimum Android: 7.0 (API 24)
- UI language: follows the system by default; can be changed to Simplified Chinese or English in the app.

## Features

- Select one or multiple files with Android's Storage Access Framework.
- Select a folder and recursively add its files, retaining relative paths.
- Receive files from Android share sheets (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`) and file-open intents.
- Upload each selected file as a `multipart/form-data` POST request.
- Show task status and retain the latest task list while the app process is alive.
- GitHub Actions workflow builds a debug APK on push, pull request, or manual dispatch.

## Upload endpoint contract

Enter an HTTP/HTTPS endpoint in the app. For each file the app sends one POST request with multipart fields:

- `file`: file bytes
- `filename`: basename
- `relative_path`: path relative to the selected folder (folder uploads)

The server must implement this contract to receive the file and reconstruct folder structures. This is a generic client convention, not a universal web upload protocol. If a target website expects a different field name, authentication, CSRF token, or custom API, its endpoint/API needs to be adapted accordingly. Use HTTPS for sensitive files. Cleartext HTTP is enabled for local development and can be disabled for production.

## Web integration

A website may launch the app through Android share/open flows or a URL/deep-link integration that it explicitly implements. A normal HTML upload form cannot be intercepted universally by a third-party app. This project currently accepts shared files and ordinary file/content URIs; for a custom website, adapt the intent/deep-link parameters and endpoint contract to that site's documented API.

## Build

Open this folder in Android Studio, or run with Gradle 8.9 and JDK 17:

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.
