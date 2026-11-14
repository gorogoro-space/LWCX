# LWCX

チェストやドアなどのブロックを保護する Paper 用プラグイン(LWC Extended、Paper 1.21.11・Java 21)。
**フォーク元**: [pop4959/LWCX](https://github.com/pop4959/LWCX)。その元は [Hidendra/LWC](https://github.com/Hidendra/LWC)(作者 Hidendra = Tyler Blair)
リポジトリ: https://github.com/gorogoro-space/LWCX (BSD 2 条項、公開リポジトリ)

## 作業の進め方(必ず守ること)

- **設計が確定するまで実装しない。** 機能追加や仕様変更は、まず設計案(何を・なぜ・どう変えるか、影響範囲)を提示し、承認を得てからコードを書く。
- 判断が必要な点は、選択肢を示して質問する。勝手に決めない。
- やり取りは日本語で行う。新しく書くコードのコメントは日本語でよい。
- 変更は必要最小限にする。頼まれていないリファクタリングや機能追加はしない(フォークなので、元のコードとの差分を小さく保つ意味もある)。
- 作業後は、変更・追加・削除したファイルの一覧と変更内容を報告する。
- 仕様を変えたら README.md と CLAUDE.md も合わせて更新する。
- `git commit` は「コミットして」と言われるまでしない。`git push` の前には必ず確認を取る。コミットは意味のある単位で分ける。
- リポジトリへの初回の `git push` の前には、「GitHub で Watch 設定(Custom → Issues と Pull requests)はしましたか?」と日本語で確認する。自動 Watch は廃止されており、設定しないと issue や PR の通知が届かない。
- コミットメッセージや PR・リリースノートに `Co-Authored-By: Claude` などの署名を付けない。`.claude/` は Git に入れない(`.git/info/exclude` で除外済み)。
- 実装中に設計の抜けや穴に気づいたら、黙って対処せず報告して相談する。
- プルリクエストをチェックするときは、次の観点で確認して報告する。
  - 変更概要(何を・なぜ変えているか)
  - 脆弱性(権限チェックの漏れ、入力値の検証、パスの扱い、SQL の組み立てなど)
  - 安全面(保護データの破損・消失、他プラグインの妨げなど)
  - 性能(TPS など)の低下(高頻度イベントでの重い処理、メインスレッドでの同期 I/O・SQL、定期タスクの追加など。「最重要の設計方針」に沿っているか)

## フォークとしての注意

- 既存コードのコメント・ログ・識別子は英語。既存の英語コメントは、頼まれない限り書き換えない。追加するコメントは日本語でよい。
- パッケージ名 `com.griefcraft` などフォーク元の表記は変えない。ほかのプラグインが LWC の API(`com.griefcraft.lwc.LWC`、`com.griefcraft.model.Protection`、`com.griefcraft.scripting` のモジュールとイベントなど)を使うので、公開されているクラス・メソッドの名前や引数は、頼まれない限り変えない。
- ライセンスは BSD 2 条項(`LICENSE`、Copyright 2011 Tyler Blair)。著作権表示と条文を消さない。バイナリ(jar)で配布するときも表示と条文が必要なので、jar の `META-INF/LICENSE` に入れている。
- `plugin.yml` は `authors: [pop4959, Me_Goes_RAWR, kubotan]`(`author: Hidendra`)、`website` はこのリポジトリの URL にしてある。
- プレイヤー向けのメッセージは `src/main/resources/lang/lwc_<言語>.properties`。文言を追加・変更するときは、同梱の全言語ファイルにキーを足す(無い言語は `lwc_en.properties` から補われる)。

## 環境

- Paper 1.21.11(`io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`、`compileOnly`)。`plugin.yml` の `api-version` は `"1.20.6"`(上流のまま)。勝手に変えない
- **Java 21。** `build.gradle.kts` の toolchain で JDK 21 を使い、Java 21 の class ファイルを出力する。勝手に変えない
- ビルド: Gradle(Kotlin DSL、wrapper 9.7.1)。`gradlew.bat clean build`(Windows)。**JDK 21 で実行する**(JDK の場所は `CLAUDE.local.md`)。「ビルドして」と言われたら常にクリーンビルドする。成果物は `build/libs/LWC-<version>.jar` の 1 つ。テストは無い
  - IntelliJ の「アーティファクトのビルド」はクラスファイルが入らないことがある。必ず Gradle でビルドする
- バージョンは `gradle.properties` の `version` だけで管理する。`plugin.yml` の `${version}` は `processResources` でビルド時に置き換わる
- 連携するプラグイン(どれも softdepend・`compileOnly`): Vault、WorldEdit、WorldGuard、Towny、Factions。jar に同梱するライブラリはない(commons-lang3・json-simple・guava は Paper に入っているものを使う)
- メッセージは `sendMessage(String)`(§ の色コード)。アクションバーは Adventure(`sendActionBar` と `LegacyComponentSerializer`)。BungeeCord の chat API(`TextComponent`・`sender.spigot()`)は使わない
- 非推奨の API は使わない(`build.gradle.kts` で `-Xlint:all` を付け、警告 0 件にしてある)。`@SuppressWarnings` で抑えているのは次の場所だけで、増やすときは理由をコメントに書く
  - `bukkit/EntityBlock`・`bukkit/EntityBlockState`(Bukkit の `Block`・`BlockState` を実装しているので、削除予定・非推奨のメソッドも実装が必要)
  - `model/LWCPlayer` の `sendMessage(UUID, …)`(`CommandSender` の実装に必要)
  - `util/MaterialUtil.getMaterialById`(古い ID の変換表。`LEGACY_SIGN` などを残す)
  - `util/UUIDRegistry.getUUID`(オフラインモードの `Bukkit.getOfflinePlayer(String)`。通信しない)
  - `this-escape`: `LWC`・`DatabaseThread`・`LWCBlockListener`・`LWCMCPCSupport`・`ConfirmAction` のコンストラクタ(元の設計のまま)
  - 元からあるもの(`EntityBlockState` のクラス全体の `deprecation` など)
- jar を差し替えるときは先に古い jar を消す(同じ名前のプラグインが 2 つあると、どちらが読まれるかわからない)
- サーバーと開発 PC の環境情報は `CLAUDE.local.md`(Git に入れない)を参照する

## ブランチとリリース

- 既定のブランチは `main`(Paper 1.21.11 用。2.4.2 をもとにした 2.4.x)。`paper` は上流の Paper 26.1 以降向け(2.5.0)、`paper-magnet-cache-fix` は `paper` に保護キャッシュとマグネットの修正を入れたもの
- GitHub Actions などの自動ビルドは無い(手元でビルドした jar を添付する)
- 手順: 「バージョンを X.Y.Z にする」コミットで `gradle.properties` の `version` を上げる → push → クリーンビルド → タグ `vX.Y.Z` で GitHub のリリースを作り、`LWC-X.Y.Z.jar` を添付する(`--target` にはブランチ名か 40 桁の SHA を渡す)
- リリースの本文には、もとにした版・主な変更点・ライセンス(BSD 2 条項。jar の `META-INF/LICENSE` に条文がある)を書く。公開前に本文をユーザーに確認してもらう
- リリース後は、ユーザーがリリースの jar をダウンロードして動作確認する

## 最重要の設計方針

### TPS に影響させない
- 高頻度イベント(PlayerMoveEvent、InventoryMoveItemEvent など)を使うときは、安い判定を先に行い、対象外なら即座に抜ける
- BlockPhysicsEvent など発生頻度が極端に高いイベントは使わない
- 保護の検索は `ProtectionCache` を使う。キャッシュに全保護が載っている(`ProtectionCache.isComplete()`)ときは、メインスレッドで SQL を実行しない。キャッシュを全消去する処理・保護を DB から消す処理を足すときは、`complete` の扱いも合わせて直す
- メインスレッドでファイルや DB の同期 I/O を増やさない(起動時・リロード時の読み込みは除く)
- 定期タスクは最小限にし、追加するときは頻度と理由を設計案に書く(マグネットは毎 tick 動き、50 tick で全マグネットを一巡する)

### 外部と通信しない
- 統計情報の送信(bStats など)、更新の確認、Mojang への問い合わせなど、外部への通信は入れない(フォーク元にあった bStats・更新の確認(`Updater`)・`UUIDFetcher`・`NameFetcher` は削除済み)
- プレイヤーを名前で探すときは、オンラインの人とサーバーのキャッシュだけを見る(`UUIDRegistry.getUUID` は `Bukkit.getPlayerExact` と `Bukkit.getOfflinePlayerIfCached` を使う)
- 例外は、管理者が設定で選ぶ MySQL への接続だけ

### 他プラグインとの関係
- 既存の機能を妨げないこと。イベントをキャンセルする範囲は必要最小限にする

## 過去にハマった点

- 保護の総数(`protectionCount`)とキャッシュの件数を厳密に比べていたため、1 件ずれるだけでキャッシュを使わなくなり、マグネットがメインスレッドで SQL を実行し続けていた(2.4.3 で修正。`ProtectionCache` の `complete` フラグで判定する)
- マグネットの収納枠を周回の累計で計算すると、使わなかった枠が貯まって一度に大量に吸い込む(2.4.3 の開発中に発生。毎 tick 分を足し、1 tick 分を上限にした)
- 動作確認は目で追える設定(マグネットの `perSweep` を小さくするなど)で行う

## 使わない機能

### `/lwc admin backup`(機能していない。使わない)
- Paper では動かない(上流のまま、2020 年から変わっていない)。2026-11-14 に空の DB で試すと、`create` は別スレッドでチェストの中身(`BlockState`)を読もうとして `Block entity is null, asynchronous access?` になり、ブロックも保護も書き出されず空のバックアップになった。あわせて、2 本目の DB 接続で `PhysDB.load()` を実行するため `SQLITE_BUSY` の警告も出る
- 仮に直して中身をメインスレッドで読むようにしても、今の保護の件数(約 52 万件)では使えない
  - `create` は 250 件ずつメインスレッドでブロックを読む(約 2,100 回)。読み込まれていないチャンクはその場で同期的に読み込むので、保護のあるチャンクをほぼすべて読み込むことになり、そのたびにサーバーが止まる(合計で数分〜数十分の見込み)。読み込んだチャンクの分だけメモリも増える
  - 中身はアイテムの種類・数・耐久値だけを保存する。エンチャント・名前・本の中身などは失われる
  - `restore` は保護を今の DB に足す形で全件登録し直す(約 104 万件に増える)。ブロックは種類だけを置き直すので、向きなども戻らない
- バックアップは、サーバーを止めて `plugins/LWC/lwc.db` をコピーする方法で取る

## ファイル構成(src/main/java/com/griefcraft/)

- `lwc/LWCPlugin.java` — メインクラス(言語ファイル・DB の読み込み、リスナーの登録)
- `lwc/LWC.java` — 本体(設定、保護の検索・権限の判定、メッセージの送信、モジュールの管理)
- `cache/` — 保護のキャッシュ(`ProtectionCache`)、ブロック ID のキャッシュ(`BlockCache`)など
- `sql/` — DB(`PhysDB` が保護の読み書き。SQLite / MySQL)
- `model/` — 保護(`Protection`)、フラグ、権限、プレイヤーなど
- `listeners/` — ブロック・プレイヤー・エンティティのイベントのリスナー
- `modules/` — 機能ごとのモジュール(`admin` の `/lwc admin` コマンド、`flag` のマグネットなど、`doors`、`limits`、`pluginsupport` の WorldGuard・Towny・Factions 連携など)
- `scripting/` — モジュールの仕組みとイベント(他プラグインが使う API)
- `util/` — 色(`Colors`)、UUID と名前の変換(`UUIDRegistry`)、設定の読み込み、言語ファイルなど
- `src/main/resources/` — `plugin.yml`、`config/*.yml`(core・magnet など)、`lang/lwc_*.properties`
- `build.gradle.kts` / `settings.gradle.kts` / `gradle.properties` — Gradle の設定(バージョンは `gradle.properties`)
