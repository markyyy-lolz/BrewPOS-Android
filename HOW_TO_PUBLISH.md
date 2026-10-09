# Publish BrewPOS on GitHub

**Suggested destination:** `markyyy-lolz/BrewPOS-Android` (PRIVATE)

1. Open https://github.com/new and choose owner `markyyy-lolz`.
2. Set **Repository name:** `BrewPOS-Android`; choose **Private**.
3. Do **not** initialize the remote with a README, .gitignore or license (those are already included).
4. Extract `BrewPOS_GitHub_Ready_v1.1.zip`, open a terminal in the extracted folder where `settings.gradle.kts` is located.
5. Run the commands below. Use GitHub's normal login/credential manager when prompted; never paste tokens in a public chat or commit them.

```bash
git init -b main
git add .
git commit -m "Initial BrewPOS v1.1: offline coffee shop POS and one-printer receipts"
git remote add origin https://github.com/markyyy-lolz/BrewPOS-Android.git
git push -u origin main
```

If `git init -b main` reports the repository already exists, use `git branch -M main` instead. The remote URL above will work **only after** you create that repository. To verify your source is pushed, open `https://github.com/markyyy-lolz/BrewPOS-Android`.

## Optional: Android APK from GitHub Actions

The project includes `.github/workflows/android-debug.yml`. On a push to `main`, GitHub Actions will **attempt** to run Gradle tests and build a debug APK using JDK 17, Gradle 8.13 and Android SDK 36. After a **successful** run, go to **Actions → Android Debug APK → latest successful workflow → Artifacts → BrewPOS-v1.1-debug-APK**.

**Caution:** The Android build has not been tested in this environment. Build failures require fixing before an APK artifact will exist. A GitHub Actions debug APK is not a signed production release and one-printer Bluetooth printing needs testing with your exact printer.