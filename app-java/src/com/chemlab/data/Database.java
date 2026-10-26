package com.chemlab.data;

import com.chemlab.util.AppPaths;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 连接与建表。
 *
 * <p>整个应用共用一个连接：桌面单用户场景下并发极低，SQLite 单文件即可满足，
 * 无需连接池。数据库文件默认位于 {@code <home>/data/chemlab.db}。
 */
public final class Database implements AutoCloseable {

    private final Connection conn;
    private final Path file;

    private Database(Connection conn, Path file) {
        this.conn = conn;
        this.file = file;
    }

    public static Database open() throws SQLException {
        return open(AppPaths.database());
    }

    public static Database open(Path file) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException(
                    "未找到 SQLite JDBC 驱动，请确认 lib/sqlite-jdbc-*.jar 已加入类路径", e);
        }
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toString());
        Database db = new Database(c, file);
        db.initSchema();
        return db;
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("PRAGMA foreign_keys = ON");
            st.executeUpdate("PRAGMA journal_mode = WAL");

            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS records (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  title TEXT NOT NULL DEFAULT ''," +
                    "  type TEXT NOT NULL DEFAULT 'other'," +
                    "  date TEXT NOT NULL DEFAULT ''," +
                    "  purpose TEXT NOT NULL DEFAULT ''," +
                    "  data_text TEXT NOT NULL DEFAULT ''," +
                    "  conclusion TEXT NOT NULL DEFAULT ''," +
                    "  tags TEXT NOT NULL DEFAULT ''," +
                    "  result_value REAL," +
                    "  created_at TEXT NOT NULL DEFAULT ''," +
                    "  updated_at TEXT NOT NULL DEFAULT ''" +
                    ")");

            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS calibrations (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  name TEXT NOT NULL DEFAULT ''," +
                    "  x_json TEXT NOT NULL DEFAULT '[]'," +
                    "  y_json TEXT NOT NULL DEFAULT '[]'," +
                    "  slope REAL NOT NULL DEFAULT 0," +
                    "  intercept REAL NOT NULL DEFAULT 0," +
                    "  r2 REAL NOT NULL DEFAULT 0," +
                    "  syx REAL NOT NULL DEFAULT 0," +
                    "  lod REAL NOT NULL DEFAULT 0," +
                    "  loq REAL NOT NULL DEFAULT 0," +
                    "  created_at TEXT NOT NULL DEFAULT ''" +
                    ")");

            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_records_type ON records(type)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_records_date ON records(date)");
        }
    }

    public Connection connection() {
        return conn;
    }

    public Path file() {
        return file;
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            System.err.println("[chemlab] 关闭数据库失败：" + e.getMessage());
        }
    }
}