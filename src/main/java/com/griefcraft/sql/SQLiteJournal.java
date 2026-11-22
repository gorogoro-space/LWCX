package com.griefcraft.sql;

import com.griefcraft.lwc.LWC;
import com.griefcraft.util.config.Configuration;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * SQLite のジャーナルの設定(WAL + synchronous=NORMAL)と、別スレッドでのチェックポイント。
 * <p>
 * 初期設定(journal_mode=DELETE、synchronous=FULL)のままだと、コミットのたびに fsync が走り、
 * メインスレッドの書き込みがディスクの混み具合に引きずられて止まる。WAL + NORMAL にすると
 * 通常のコミットは WAL への追記だけになり、fsync はチェックポイントのときだけになる。
 * そのチェックポイントを専用の接続と非同期のタスクで行い、メインスレッドで fsync しないようにする。
 */
public final class SQLiteJournal {

    private static final String DEFAULT_JOURNAL_MODE = "WAL";
    private static final String DEFAULT_SYNCHRONOUS = "NORMAL";
    private static final int DEFAULT_CHECKPOINT_INTERVAL = 60;

    /**
     * 同じ警告を出す最短の間隔(5 分)
     */
    private static final long WARNING_INTERVAL_MILLIS = 5 * 60 * 1000L;

    /**
     * 専用の接続を作り直すまでの、連続して失敗した回数
     */
    private static final int REOPEN_AFTER_FAILURES = 3;

    /**
     * WAL を全部書き戻せない(checkpointed < log)状態が、この回数続き、かつ -wal がこの大きさを超えたら警告する。
     * どこかで読み取りのトランザクションが残っていると、チェックポイントが進まず -wal が大きくなり続けるため
     */
    private static final int INCOMPLETE_WARN_COUNT = 10;
    private static final long INCOMPLETE_WARN_WAL_BYTES = 64L * 1024 * 1024;

    /**
     * 上の警告を出す最短の間隔(1 時間)
     */
    private static final long INCOMPLETE_WARNING_INTERVAL_MILLIS = 60 * 60 * 1000L;

    private SQLiteJournal() {
    }

    /**
     * core.yml の database.sqlite の設定
     */
    public static final class Settings {
        public final String journalMode;   // WAL or DELETE
        public final String synchronous;   // NORMAL or FULL
        public final int checkpointInterval; // 秒。0 = SQLite の自動チェックポイント

        private Settings(String journalMode, String synchronous, int checkpointInterval) {
            this.journalMode = journalMode;
            this.synchronous = synchronous;
            this.checkpointInterval = checkpointInterval;
        }

        public boolean isWal() {
            return "WAL".equals(journalMode);
        }

        /**
         * 別スレッドでチェックポイントを行うか
         */
        public boolean useAsyncCheckpoint() {
            return isWal() && checkpointInterval > 0;
        }
    }

    /**
     * 設定を読む。決めた値以外は警告を出して既定値にする
     */
    public static Settings loadSettings() {
        Configuration config = LWC.getInstance().getConfiguration();

        String journalMode = config.getString("database.sqlite.journalMode", DEFAULT_JOURNAL_MODE).trim().toUpperCase(Locale.ROOT);
        if (!journalMode.equals("WAL") && !journalMode.equals("DELETE")) {
            warning("Invalid database.sqlite.journalMode '" + journalMode + "' (expected WAL or DELETE), using " + DEFAULT_JOURNAL_MODE);
            journalMode = DEFAULT_JOURNAL_MODE;
        }

        String synchronous = config.getString("database.sqlite.synchronous", DEFAULT_SYNCHRONOUS).trim().toUpperCase(Locale.ROOT);
        if (!synchronous.equals("NORMAL") && !synchronous.equals("FULL")) {
            warning("Invalid database.sqlite.synchronous '" + synchronous + "' (expected NORMAL or FULL), using " + DEFAULT_SYNCHRONOUS);
            synchronous = DEFAULT_SYNCHRONOUS;
        }

        String intervalValue = config.getString("database.sqlite.checkpointInterval", String.valueOf(DEFAULT_CHECKPOINT_INTERVAL)).trim();
        int checkpointInterval;
        try {
            checkpointInterval = Integer.parseInt(intervalValue);
        } catch (NumberFormatException e) {
            checkpointInterval = -1;
        }
        if (checkpointInterval < 0) {
            warning("Invalid database.sqlite.checkpointInterval '" + intervalValue + "' (expected 0 or more seconds), using " + DEFAULT_CHECKPOINT_INTERVAL);
            checkpointInterval = DEFAULT_CHECKPOINT_INTERVAL;
        }

        return new Settings(journalMode, synchronous, checkpointInterval);
    }

    /**
     * 開いた直後の SQLite の接続に PRAGMA を当て、実際の値を 1 行のログに出す。
     * 失敗しても接続は止めず、警告を出して続ける。
     *
     * @return 実際の journal_mode(小文字。読めなかったときは null)
     */
    public static String applyPragmas(Connection connection, Settings settings) {
        try (Statement statement = connection.createStatement()) {
            // 読み取り専用の接続では journal_mode を変えられないので、今の値を読むだけにする
            if (!connection.isReadOnly()) {
                String mode = queryString(statement, "PRAGMA journal_mode=" + settings.journalMode + ";");
                if (mode == null || !mode.equalsIgnoreCase(settings.journalMode)) {
                    warning("Could not set SQLite journal_mode to " + settings.journalMode + " (current: " + mode
                            + "). Another process may have the database open. Continuing with the current mode.");
                }
            }

            statement.execute("PRAGMA synchronous=" + settings.synchronous + ";");

            if (settings.useAsyncCheckpoint()) {
                // 自動のチェックポイントを止める(止めないと、コミットした接続 = メインスレッドで fsync が起きる)
                statement.execute("PRAGMA wal_autocheckpoint=0;");
            }

            String journalMode = queryString(statement, "PRAGMA journal_mode;");
            String synchronous = queryString(statement, "PRAGMA synchronous;");
            String autoCheckpoint = queryString(statement, "PRAGMA wal_autocheckpoint;");
            String busyTimeout = queryString(statement, "PRAGMA busy_timeout;");

            String checkpoint;
            if ("wal".equalsIgnoreCase(journalMode)) {
                checkpoint = settings.useAsyncCheckpoint()
                        ? "checkpoint every " + settings.checkpointInterval + "s (async)"
                        : "automatic checkpoint";
            } else {
                checkpoint = "no checkpoint";
            }

            LWC.getInstance().log("SQLite: journal_mode=" + journalMode
                    + ", synchronous=" + synchronous + " (" + synchronousName(synchronous) + ")"
                    + ", wal_autocheckpoint=" + autoCheckpoint
                    + ", busy_timeout=" + busyTimeout + "ms"
                    + ", " + checkpoint);

            return journalMode == null ? null : journalMode.toLowerCase(Locale.ROOT);
        } catch (SQLException e) {
            warning("Failed to apply SQLite PRAGMA settings: " + e.getMessage() + ". Continuing with the defaults.");
            return null;
        }
    }

    /**
     * 終了時に、メインの接続で WAL の内容をすべて本体に書き戻し、-wal を空にする
     */
    public static void checkpointTruncate(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            if (!"wal".equalsIgnoreCase(queryString(statement, "PRAGMA journal_mode;"))) {
                return;
            }

            try (ResultSet set = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE);")) {
                if (set.next() && set.getInt(1) != 0) {
                    warning("SQLite checkpoint on shutdown could not complete (busy). The -wal file will be applied on the next start.");
                }
            }
        } catch (SQLException e) {
            warning("SQLite checkpoint on shutdown failed: " + e.getMessage());
        }
    }

    /**
     * 専用の接続で、定期的に wal_checkpoint(PASSIVE) を行う非同期のタスク
     */
    public static final class Checkpointer implements Runnable {

        private final String url;
        private final File walFile;
        private final Settings settings;
        private final Map<String, Long> lastWarnings = new HashMap<>();

        private Connection connection;
        private BukkitTask task;
        private int failures = 0;
        private int incomplete = 0;
        private boolean stopped = false;

        private Checkpointer(String databasePath, Settings settings) {
            this.url = "jdbc:sqlite:" + databasePath;
            this.walFile = new File(databasePath + "-wal");
            this.settings = settings;
        }

        /**
         * タスクを開始する
         *
         * @param plugin
         * @param databasePath SQLite のファイルのパス(database.path)
         * @param settings
         * @return 開始したタスク(WAL でないときなど、開始しなかったときは null)
         */
        public static Checkpointer start(Plugin plugin, String databasePath, Settings settings) {
            if (!settings.useAsyncCheckpoint()) {
                return null;
            }

            Checkpointer checkpointer = new Checkpointer(databasePath, settings);
            long period = settings.checkpointInterval * 20L;
            checkpointer.task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, checkpointer, period, period);
            return checkpointer;
        }

        @Override
        public synchronized void run() {
            if (stopped) {
                return;
            }

            try {
                if (connection == null) {
                    connection = DriverManager.getConnection(url);
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("PRAGMA busy_timeout=5000;");
                        statement.execute("PRAGMA synchronous=" + settings.synchronous + ";");
                    }
                }

                try (Statement statement = connection.createStatement();
                     ResultSet set = statement.executeQuery("PRAGMA wal_checkpoint(PASSIVE);")) {
                    // 結果は busy, log(WAL のフレーム数), checkpointed(書き戻したフレーム数)
                    if (set.next()) {
                        checkIncomplete(set.getInt(2), set.getInt(3));
                    }
                }

                failures = 0;
            } catch (SQLException e) {
                failures++;
                warnThrottled("checkpoint", "SQLite checkpoint failed: " + e.getMessage());

                // エラーが続くときだけ、専用の接続を作り直す
                if (failures >= REOPEN_AFTER_FAILURES) {
                    closeConnection();
                    failures = 0;
                }
            }
        }

        /**
         * タスクを止め、専用の接続を閉じる。実行中のチェックポイントがあれば、終わるまで待つ
         */
        public synchronized void stop() {
            stopped = true;

            if (task != null) {
                task.cancel();
                task = null;
            }

            closeConnection();
        }

        private void closeConnection() {
            if (connection == null) {
                return;
            }

            try {
                connection.close();
            } catch (SQLException e) {
                warnThrottled("close", "Failed to close the SQLite checkpoint connection: " + e.getMessage());
            }

            connection = null;
        }

        /**
         * WAL を全部書き戻せない状態が続き、-wal が大きくなっていたら警告する
         */
        private void checkIncomplete(int log, int checkpointed) {
            if (log < 0 || checkpointed >= log) {
                incomplete = 0;
                return;
            }

            incomplete++;
            long walBytes = walFile.length();

            if (incomplete >= INCOMPLETE_WARN_COUNT && walBytes > INCOMPLETE_WARN_WAL_BYTES) {
                warnThrottled("incomplete", "SQLite checkpoint could not write back the whole WAL " + incomplete
                        + " times in a row (log=" + log + ", checkpointed=" + checkpointed + "), and " + walFile.getName()
                        + " is " + (walBytes / (1024 * 1024)) + " MB. A read transaction may be left open somewhere;"
                        + " restarting the server resets it.", INCOMPLETE_WARNING_INTERVAL_MILLIS);
            }
        }

        private void warnThrottled(String key, String message) {
            warnThrottled(key, message, WARNING_INTERVAL_MILLIS);
        }

        private void warnThrottled(String key, String message, long interval) {
            long now = System.currentTimeMillis();
            Long last = lastWarnings.get(key);

            if (last == null || now - last >= interval) {
                lastWarnings.put(key, now);
                warning(message);
            }
        }
    }

    private static String queryString(Statement statement, String sql) throws SQLException {
        try (ResultSet set = statement.executeQuery(sql)) {
            return set.next() ? set.getString(1) : null;
        }
    }

    private static String synchronousName(String value) {
        if (value == null) {
            return "unknown";
        }

        switch (value) {
            case "0":
                return "OFF";
            case "1":
                return "NORMAL";
            case "2":
                return "FULL";
            case "3":
                return "EXTRA";
            default:
                return value;
        }
    }

    private static void warning(String message) {
        Logger logger = LWC.getInstance().getPlugin().getLogger();
        logger.warning(message);
    }

}
