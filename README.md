# 文件上传器（FileUploader）

Android 文件选择与上传辅助工具。

- 包名：`com.jituileet.fileuploader`
- 最低系统：Android 5.0（API 21）
- 当前版本：1.0.0
- 开发语言：Java

## 功能

- 单选和多选文件。
- 浏览文件夹。
- 递归选择当前文件夹中的文件。
- 通过 `ACTION_GET_CONTENT`、`ACTION_OPEN_DOCUMENT` 注册为其他应用可调用的文件选择目标。
- 通过 `content://` URI、`ClipData` 和读取授权将选择结果返回调用应用。
- 使用 Android Storage Access Framework 授权访问目录，不需要申请全盘文件访问权限。

## 首次使用

首次启动时点击“选择存储位置”，在系统文件夹选择器中选择一个希望管理的目录，并允许访问。之后应用会记住该目录的授权。

## 构建

需要 JDK 17、Android SDK Platform 35 和 Gradle 8.9：

```bash
gradle assembleDebug
gradle assembleRelease
```

也可以在 GitHub 仓库的 **Actions → Build FileUploader → Run workflow** 手动编译。生成的 Debug/Release APK 会作为 `FileUploader-APKs` artifact 提供下载。

## Android 文件选择集成说明

应用声明了 `ACTION_GET_CONTENT` 和 `ACTION_OPEN_DOCUMENT` 的 `*/*` 过滤器。不同应用可能使用不同的 Intent、MIME 类型或自定义上传流程，因此是否显示 FileUploader 取决于调用应用的实现和 Android 版本。

文件夹选择在 FileUploader 内部通过系统目录授权实现；对普通网页上传控件，选择文件夹会将目录中的文件作为多个文件 URI 返回。并非所有调用应用都支持多文件上传。

## 当前版本说明

这是基础可运行版本。它使用用户明确授权的 SAF 目录作为浏览根目录，不会静默扫描整个设备。首次使用需要先选择一个目录。
