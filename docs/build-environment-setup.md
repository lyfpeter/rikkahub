# RikkaHub 编译环境配置与构建指南

> 本文档基于实际搭建过程整理，记录了从零开始在 Ubuntu 20.04 LTS 上配置 RikkaHub 编译环境并完成构建的全过程。

## 1. 环境前提

| 项目 | 要求 |
|------|------|
| 操作系统 | Ubuntu 20.04.6 LTS (Focal Fossa) x86_64 |
| 权限 | root 或 sudo 用户 |
| 网络 | 需访问 Google Maven 仓库、Gradle 分发服务器、Android SDK 仓库 |

## 2. 安装 JDK 17

### 2.1 安装

```bash
apt-get update -qq
apt-get install -y -qq openjdk-17-jdk-headless
```

apt 会自动拉取依赖包（java-common、libnss3、libcups2 等）。

### 2.2 验证

```bash
java -version
# 期望输出: openjdk version "17.0.15"

# 确认安装路径（后续编译需要）
dirname $(dirname $(readlink -f $(which java)))
# 期望输出: /usr/lib/jvm/java-17-openjdk-amd64
```

### 2.3 环境变量

建议写入 `~/.bashrc` 持久化：

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
```

> 如果不设置环境变量，Gradle 也能自动检测到系统默认 JDK，但 `local.properties` 中的 `sdk.dir` 必须配置。

## 3. 安装 Android SDK

### 3.1 下载 Command-line Tools

```bash
mkdir -p /opt/android-sdk
cd /tmp
curl -fsSL -o cmdline-tools.zip \
  "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
unzip -qo cmdline-tools.zip -d /opt/android-sdk/
# cmdline-tools 目录结构必须调整，否则 sdkmanager 无法正常工作
mv /opt/android-sdk/cmdline-tools /opt/android-sdk/cmdline-tools-tmp
mkdir -p /opt/android-sdk/cmdline-tools/latest
mv /opt/android-sdk/cmdline-tools-tmp/* /opt/android-sdk/cmdline-tools/latest/
rm -rf /opt/android-sdk/cmdline-tools-tmp cmdline-tools.zip
```

### 3.2 配置环境变量

```bash
export ANDROID_HOME=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"
```

### 3.3 接受 License

```bash
yes | sdkmanager --licenses
```

这会将 license hash 写入 `/opt/android-sdk/licenses/` 目录。如果不接受 license，后续安装组件会报错。

### 3.4 安装 SDK 组件

项目 `app/build.gradle.kts` 中 `compileSdk = 37`，需要安装对应的 platform 和 build-tools：

```bash
sdkmanager "platform-tools" "platforms;android-37.2" "build-tools;37.0.0"
```

> **注意：** Gradle 编译时 AGP 会自动检测缺失的 SDK 组件并通过 sdkmanager 自动补装（如 `build-tools;36.0.0` 和 `platforms;android-37.0`）。但建议提前手动安装，避免编译过程中的不确定性。

### 3.5 验证安装

```bash
ls /opt/android-sdk/
# 期望目录: build-tools  cmdline-tools  licenses  platform-tools  platforms

ls /opt/android-sdk/platforms/
# 期望: android-37.0  android-37.2

ls /opt/android-sdk/build-tools/
# 期望: 36.0.0  37.0.0
```

### 3.6 配置项目 local.properties

在项目根目录创建 `local.properties`，告知 Gradle SDK 位置：

```bash
echo "sdk.dir=/opt/android-sdk" > /work/rikkahub/local.properties
```

> 如果不创建此文件，Gradle 会报错 `SDK location not found`。也可以设置 `ANDROID_HOME` 环境变量替代，但 `local.properties` 方式更可靠。

## 4. 配置 google-services.json

项目依赖 Firebase（`google-services` 和 `firebase-crashlytics` 插件），必须有此文件才能编译。

### 4.1 占位文件（用于本地开发）

如果不需要 Firebase 功能，可以使用占位文件：

```json
{
  "project_info": {
    "project_number": "000000000000",
    "project_id": "rikkahub-placeholder",
    "storage_bucket": "rikkahub-placeholder.appspot.com"
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "1:000000000000:android:0000000000000000",
        "android_client_info": {
          "package_name": "me.rerere.rikkahub"
        }
      },
      "oauth_client": [],
      "api_key": [
        {
          "current_key": "AIzaSyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        }
      ],
      "services": {}
    }
  ],
  "configuration_version": "1"
}
```

放置路径：`app/google-services.json`

### 4.2 正式文件

从 [Firebase Console](https://console.firebase.google.com/) 下载真实的 `google-services.json`，替换占位文件。

## 5. Gradle 配置

### 5.1 Gradle Wrapper（无需手动安装）

项目自带 Gradle Wrapper（`gradlew` 脚本 + `gradle/wrapper/gradle-wrapper.properties`），指定了 Gradle 9.6.0。首次执行 `./gradlew` 时，Wrapper 会自动：

1. 从 `https://services.gradle.org/distributions/gradle-9.6.0-bin.zip` 下载（约 134MB）
2. 解压缓存到 `~/.gradle/wrapper/dists/gradle-9.6.0-bin/`
3. 用该 Gradle 执行构建任务

### 5.2 关键版本

| 组件 | 版本 |
|------|------|
| Gradle | 9.6.0 (via Wrapper) |
| Android Gradle Plugin (AGP) | 9.4.0 |
| Kotlin | 2.4.21 |
| Compose BOM | 2026.09.00 |
| Material 3 | 1.5.0-beta02 |

### 5.3 gradle.properties

项目根目录 `gradle.properties` 已预配置以下参数：

```properties
# Gradle JVM 内存分配
org.gradle.jvmargs=-Xmx8g -Dfile.encoding=UTF-8
# 并行构建
org.gradle.parallel=true
# 配置缓存（加速二次构建）
org.gradle.configuration-cache=true
# 并行同步（Gradle 9.4+）
org.gradle.tooling.parallel=true
```

建议根据实际机器内存调整 `-Xmx` 值，最低 4GB。

## 6. 编译

### 6.1 Debug 构建（开发调试）

```bash
cd /work/rikkahub
./gradlew assembleGithubDebug
```

Debug 构建使用默认 debug 签名，不需要配置 keystore。

**产物路径：** `app/build/outputs/apk/github/debug/`

按 ABI 分包输出：

```
app-github-arm64-v8a-debug.apk
app-github-universal-debug.apk
app-github-x86_64-debug.apk
```

### 6.2 Release 构建（正式发布）

Release 构建需要签名配置。在 `local.properties` 中添加：

```properties
sdk.dir=/opt/android-sdk
storeFile=/path/to/your.jks
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

然后执行：

```bash
./gradlew assembleGithubRelease
```

**产物路径：** `app/build/outputs/apk/github/release/`

### 6.3 其他常用命令

```bash
# 构建 Google Play AAB
./gradlew bundlePlayRelease

# 运行 JVM 单元测试
./gradlew test

# 运行 Android Lint
./gradlew lint

# 格式化代码（仅改动过的文件）
./gradlew spotlessApply

# 检查代码格式
./gradlew spotlessCheck
```

### 6.4 构建渠道说明

项目有 `distribution` 维度的 product flavor：

| Flavor | 用途 | 判断方式 |
|--------|------|----------|
| `github` | GitHub Release / 每日构建 | `BuildConfig.IS_PLAY_STORE = false` |
| `play` | Google Play 上架 | `BuildConfig.IS_PLAY_STORE = true` |

两个渠道共用同一个 `applicationId`。Play 版不检查更新、不显示更新提醒。

## 7. 首次编译预期

### 7.1 耗时

首次编译需要下载大量依赖，耗时较长：

| 阶段 | 预估时间 | 说明 |
|------|----------|------|
| 下载 Gradle 9.6.0 | ~1 min | 134MB |
| 下载 Maven 依赖 | ~10-15 min | 约 1.4GB 缓存 |
| 编译 Kotlin 源码 | ~5-10 min | 取决于 CPU |
| 打包 APK | ~1 min | — |
| **总计** | **~15-25 min** | 二次编译利用缓存可降至 2-3 min |

### 7.2 Gradle 缓存

首次编译后，`~/.gradle/` 目录约 1.4GB，`/opt/android-sdk/` 约 800MB。

二次编译会命中配置缓存（`org.gradle.configuration-cache=true`），大幅减少 task graph 计算时间。

### 7.3 常见警告

编译过程可能出现以下警告，不影响构建：

- `Experimental Haze API` — Haze 库实验性 API 使用警告
- `Slider is deprecated` — 建议使用接受 `SliderState` 的新重载

## 8. CI 构建（GitHub Actions）

项目配置了每日自动构建（`.github/workflows/daily-build.yml`），每天 UTC 09:00 和 18:00 触发。

CI 环境与本地环境的差异：

| 项目 | CI | 本地 |
|------|----|----|
| JDK | JetBrains Runtime 21 (via `actions/setup-java`) | OpenJDK 17 |
| Node.js | 22 + pnpm 11（用于 web-ui 前端构建） | 不需要 |
| 签名 | 从 GitHub Secrets 注入 keystore | local.properties 配置 |
| google-services.json | 从 GitHub Secrets 注入 | 占位文件或真实文件 |

CI 构建命令：

```bash
./gradlew assembleGithubRelease
```

产物发布为 GitHub Prerelease，包含各 ABI 的 APK 和 `mapping.txt`（混淆映射）。

## 9. APK 下载服务

项目自带一个简易 HTTP 服务器脚本，编译完成后可用于下载 APK：

```bash
./serve-apk.sh [端口]
# 默认端口 10000
# 启动后可在浏览器访问 http://<机器IP>:10000/ 下载 APK
```

## 10. 故障排查

### 10.1 JAVA_HOME is not set

```
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
```

**解决：** 安装 JDK 或设置 `JAVA_HOME` 环境变量。

### 10.2 SDK location not found

```
SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by configuring the local.properties file.
```

**解决：** 创建 `local.properties` 并写入 `sdk.dir=/opt/android-sdk`。

### 10.3 google-services.json 缺失

```
File google-services.json is missing.
```

**解决：** 在 `app/` 目录下放置 `google-services.json`（可用占位文件）。

### 10.4 License 未接受

```
You have not accepted the license agreements of the following SDK components
```

**解决：** 执行 `yes | sdkmanager --licenses`。

### 10.5 Gradle 下载缓慢或失败

如果网络访问 `services.gradle.org` 缓慢，可配置镜像或代理：

```bash
# 方式 1：使用国内镜像（在 gradle/wrapper/gradle-wrapper.properties 中修改）
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-9.6.0-bin.zip

# 方式 2：配置 HTTP 代理（在 gradle.properties 中添加）
systemProp.http.proxyHost=your.proxy.host
systemProp.http.proxyPort=8080
systemProp.https.proxyHost=your.proxy.host
systemProp.https.proxyPort=8080
```

### 10.6 Maven 依赖下载缓慢

在 `settings.gradle.kts` 的 `repositoriesMode` 块中添加国内镜像：

```kotlin
maven("https://maven.aliyun.com/repository/public")
maven("https://maven.aliyun.com/repository/google")
```
