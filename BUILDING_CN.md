# 编译与发布指南

[English](BUILDING.md) | [简体中文](BUILDING_CN.md) | [← 返回 README](README_CN.md)

---

## 环境要求

- **JDK**：JDK 17
- **Android SDK**：Platform 36（`compileSdk = 36`、`targetSdk = 36`、`minSdk = 30`）
- **Python 3**：可选，用于本地运行 Changelog 提取脚本校验

---

## 常用命令

```bash
# 编译 Debug / Release APK
./gradlew assembleDebug
./gradlew assembleRelease

# 运行单元测试（纯 JVM 逻辑）
./gradlew testDebugUnitTest

# 运行 Release Lint 检查
./gradlew lintVitalRelease
```

若依赖已缓存，可附加 `--offline` 执行离线编译：

```bash
./gradlew testDebugUnitTest assembleRelease --offline
```

- Release 产物路径：`app/build/outputs/apk/release/app-release.apk`。
- Release 构建需开启 R8 压缩（`isMinifyEnabled = true`）；在 `minSdk 30+` 下不开启 R8 时未压缩 DEX 会使包体超过 40 MB（启用后约 4 MB）。混淆规则见 [`app/proguard-rules.pro`](app/proguard-rules.pro)。

---

## 签名配置

本地编译不强制配置正式签名密钥：

1. 将 [`keystore.properties.example`](keystore.properties.example) 复制为 `keystore.properties`（已列入 `.gitignore`），填写 `storeFile`、`storePassword`、`keyAlias` 与 `keyPassword`。
2. 缺少 `keystore.properties` 或对应 `.jks` 文件时，`assembleRelease` 会输出警告并退回 debug 签名，外部开发者可直接完成编译。

> 注意：自建 APK 使用本地签名证书，无法直接覆盖官方发布包；请先卸载官方版本或使用同一套签名密钥。

---

## 辅助工具 `tools/probe`

[`tools/probe`](tools/probe) 是独立的真机测试应用，用于测量按 UID 的实际出口，不属于 MultiRoute 模块本体，包名保留为 `com.multiroute.probe`。

---

## 版本号与发布流程

[`app/build.gradle.kts`](app/build.gradle.kts) 根据 Git 历史自动计算版本信息：

- **`versionName`**：取自最近可达的 Git 标签（`git describe --tags --abbrev=0`）；工作区存在未提交改动时追加 `-local`。
- **`versionCode`**：`10000` 加上 `origin/master` 与 `HEAD` 提交计数中的较大值（无 Git 信息时默认为 `1`）。
- **`HOOK_BUILD_ID` 与 `HOTSPOT_HOOK_BUILD_ID`**：将 Git 短 SHA（存在改动时带 `-dirty`）与热点 Hook 摘要写入 `BuildConfig`，供应用检测 `system_server` 是否仍在执行旧构建。

需要自定义版本号时，可通过参数覆盖：

```bash
./gradlew assembleRelease -PmultiRouteVersionName=1.8.0-fork -PmultiRouteVersionCode=19999
```

### CI 自动发布

正式发版由 [`.github/workflows/release.yml`](.github/workflows/release.yml) 完成：

1. 在 [`CHANGELOG.md`](CHANGELOG.md) 顶部新增唯一的 `## vX.Y.Z - YYYY-MM-DD` 节区，包含 `**中文**` 与 `**English**`（中文在前，内容非空且不能标为 `Unreleased` / `待发布`），并同步更新 [`xposed_update.json`](xposed_update.json)。
2. 本地验证发布说明脚本：
   ```bash
   python3 -m unittest discover -s .github/scripts -p 'test_*.py'
   python3 .github/scripts/release_notes.py X.Y.Z CHANGELOG.md /tmp/NOTES.md
   ```
3. 推送格式为 **`<versionCode>-<versionName>`** 的附注标签（例如 `10173-1.8.0`）。
4. 工作流会编译并使用仓库密钥签名，校验签名指纹、APK 体积（<= 20 MB）以及 APK / 标签 / `xposed_update.json` 的一致性，将 `MultiRoute-<VersionCode>-<VersionName>.apk` 发布至 GitHub Releases，并在配置 `LSPOSED_REPO_TOKEN` 时镜像至 `Xposed-Modules-Repo/io.github.linoleic.multiroute`。
