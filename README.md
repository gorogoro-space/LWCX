# LWC Extended

> **This is a fork of [pop4959/LWCX](https://github.com/pop4959/LWCX) for Paper 1.21.11, maintained by gorogoro-space.**
> Releases of this fork are on the [GitHub releases page](https://github.com/gorogoro-space/LWCX/releases). Please report problems with this fork in this repository, not to the upstream project.
> The text below is from the upstream project.

The unofficial continuation of LWC, supporting newer versions of Minecraft. You can download stable releases of the plugin from the [Spigot resource page](https://www.spigotmc.org/resources/lwc-extended.69551/) or development versions from our [build server](https://ci.codemc.io/view/Author/job/pop4959/job/LWC/).

### Contributors
  * Hidendra - Original LWC author
  * pop4959 - Current LWCX maintainer
  * Me_Goes_Rawr - Previous maintainer
  * [Contributors to LWC](https://github.com/Hidendra/LWC/graphs/contributors)
  * [Contributors to LWCX](https://github.com/pop4959/LWCX/graphs/contributors)

### Support
For reporting problems with the upstream plugin, please make an issue on [GitHub](https://github.com/pop4959/LWCX/issues). For anything else, feel free to join us on the [M.O.S.S. Discord server](https://discord.gg/PHpuzZS). Before asking for support, please make sure you have an updated version of the plugin first.

### Information
LWC is the longest-lived single block protection plugin, that protects both the blocks themselves and their contents. Originally designed in 2010 for locking chests (hence the name "Lightweight Chests"), it can be configured to lock any block.

Please see our [wiki](https://github.com/pop4959/LWCX/wiki) for important information about setting up and using the plugin.

### Contributing
Contributing to LWCX is encouraged, and very easy. Fork the repository, and build using Gradle (`./gradlew clean build`, JDK 21). Simply make a pull request explaining your changes, and how they are a beneficial addition to the plugin.

---

# 日本語

[pop4959/LWCX](https://github.com/pop4959/LWCX)(LWC Extended)を、Paper 1.21.11 用にフォークしたものです。チェストやドアなどのブロックを保護するプラグインです。
元の LWC の作者は Hidendra(Tyler Blair)さんです。

## このフォークでの主な変更

- 保護の総数とキャッシュの件数が 1 件でもずれると、保護の検索がすべてメインスレッドでの SQL に切り替わっていた問題を直した
- マグネット(`magnet` フラグ)が、すべてのアイテムではなくマグネットのチェストを起点に探すようにし、処理を複数の tick に分けた
- 外部との通信をやめた(bStats への統計の送信、更新の確認、Mojang へのプレイヤー名・UUID の問い合わせ)。プレイヤー名と UUID は、オンラインの人とサーバーのキャッシュ(`usercache.json`)だけから引く。そのため、オンラインモードのサーバーで一度も来たことのない人を名前で指定することはできない

## 動作環境

- Paper 1.21.11
- Java 21 以上

## 導入方法

1. [リリースのページ](https://github.com/gorogoro-space/LWCX/releases)から `LWC-<バージョン>.jar` をダウンロードします。
2. サーバーを止め、`plugins` フォルダの古い LWC の jar を消してから、新しい jar を置きます(同じ名前のプラグインが 2 つあると、どちらが読み込まれるかわかりません)。
3. サーバーを起動します。

設定や使い方は、上流の [wiki](https://github.com/pop4959/LWCX/wiki) を参照してください。

## ビルド

JDK 21 が必要です。Gradle はラッパー(`gradlew`)が自動でダウンロードするので、別途インストールする必要はありません。

```
gradlew.bat clean build
```

(macOS / Linux では `./gradlew clean build`)

## IntelliJ IDEA でのビルド手順

本プロジェクトはビルドツールに Gradle を使用しています。
IntelliJ IDEA 上で正しくプラグイン（JARファイル）を生成するには、以下の手順を実行してください。
「ビルド → アーティファクトのビルド」はクラスファイルが入らないことがあるので使わないでください。

### 🛠️ ビルド手順

1. IntelliJ IDEA の画面右端にある **「Gradle」タブ** をクリックして開きます。
2. プロジェクト名（lwc）を展開し、 **`Tasks`** ツリーを開きます。
3. リスト内にある **`clean`** をダブルクリックして実行します（古いビルドキャッシュを削除します）。
4. 続けてリスト内にある **`build`** をダブルクリックして実行します。

### 📦 生成されたファイルの場所
ビルドが成功すると、プロジェクトのルート直下に `build/libs` フォルダが作成（または更新）され、その中に中身の詰まった正しい JAR ファイルが生成されます。

* **生成先:** `build/libs/LWC-<version>.jar`(例: `LWC-2.4.3.jar`。バージョンは `gradle.properties` の `version`)

この JAR ファイルを Minecraft サーバーの `plugins` フォルダに配置してください。

## 開発(IntelliJ IDEA で Claude Code を使う)

このリポジトリには、AI コーディングツール [Claude Code](https://docs.claude.com/ja/docs/claude-code/overview) 向けの作業ルールを書いた `CLAUDE.md` があります。IntelliJ IDEA で使う手順は次のとおりです。

1. Claude の有料プラン(Pro / Max)か、Anthropic API のアカウントを用意します。
2. Windows では、先に [Git for Windows](https://git-scm.com/downloads/win) をインストールします。
3. Claude Code 本体をインストールします。Windows では PowerShell で次を実行します。
   ```
   irm https://claude.ai/install.ps1 | iex
   ```
4. IntelliJ IDEA の「設定 → プラグイン → Marketplace」で「Claude Code」を検索してインストールし、IDE を再起動します。検索結果には Anthropic 以外が作った似た名前のプラグインも表示されます。プラグイン名の下に書かれた提供元が「Anthropic」になっているものを選んでください。
5. プロジェクトを開いて、ターミナルで `claude` を実行します(`Ctrl+Esc` でも起動できます)。初回はブラウザでログインします。

起動すると `CLAUDE.md` が自動で読み込まれ、このプロジェクトの設計方針に沿って作業します。

## ライセンス

本プラグインのライセンスは、フォーク元と同じく **BSD 2 条項ライセンス(2-Clause BSD License)** です。条文は [LICENSE](LICENSE) を参照してください。
元の著作権は Tyler Blair(Hidendra)さんと、LWC・LWCX の貢献者の皆さんにあります。jar にも `META-INF/LICENSE` として条文を入れています。

## kubotan へのメモ

リポジトリを新規作成したら、**必ず Watch を設定すること**(忘れない!)。
GitHub の自動 Watch 機能は 2025 年 5 月に廃止されたため、設定しないと他の人が立てた issue や PR の通知が届かない。

1. リポジトリのページ右上の **「Watch」** を押す
2. **「Custom」** を選び、**Issues** と **Pull requests** にチェックを入れる
