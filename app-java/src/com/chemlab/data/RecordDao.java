package com.chemlab.data;

import com.chemlab.model.Record;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 实验记录的增删改查。 */
public final class RecordDao {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Database db;

    public RecordDao(Database db) {
        this.db = db;
    }

    private Connection conn() {
        return db.connection();
    }

    public List<Record> listAll() throws SQLException {
        return query("SELECT * FROM records ORDER BY date DESC, id DESC", null);
    }

    /** 关键字检索：标题、目的、数据、结论、标签、类型全覆盖。 */
    public List<Record> search(String keyword) throws SQLException {
        if (keyword == null || keyword.trim().isEmpty()) {
            return listAll();
        }
        String like = "%" + keyword.trim().toLowerCase() + "%";
        String sql = "SELECT * FROM records WHERE " +
                "LOWER(title) LIKE ? OR LOWER(purpose) LIKE ? OR LOWER(data_text) LIKE ? " +
                "OR LOWER(conclusion) LIKE ? OR LOWER(tags) LIKE ? OR LOWER(type) LIKE ? " +
                "ORDER BY date DESC, id DESC";
        return query(sql, ps -> {
            for (int i = 1; i <= 6; i++) {
                ps.setString(i, like);
            }
        });
    }

    public long insert(Record r) throws SQLException {
        String now = LocalDateTime.now().format(TS);
        if (r.createdAt == null || r.createdAt.isEmpty()) {
            r.createdAt = now;
        }
        r.updatedAt = now;
        if (r.date == null || r.date.isEmpty()) {
            r.date = LocalDate.now().toString();
        }
        String sql = "INSERT INTO records(title, type, date, purpose, data_text, conclusion, " +
                "tags, result_value, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, r);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    r.id = keys.getLong(1);
                }
            }
        }
        return r.id;
    }

    public void update(Record r) throws SQLException {
        r.updatedAt = LocalDateTime.now().format(TS);
        String sql = "UPDATE records SET title=?, type=?, date=?, purpose=?, data_text=?, " +
                "conclusion=?, tags=?, result_value=?, updated_at=? WHERE id=?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, r.title);
            ps.setString(2, r.type);
            ps.setString(3, r.date);
            ps.setString(4, r.purpose);
            ps.setString(5, r.dataText);
            ps.setString(6, r.conclusion);
            ps.setString(7, r.tags);
            if (r.resultValue == null) {
                ps.setNull(8, java.sql.Types.REAL);
            } else {
                ps.setDouble(8, r.resultValue);
            }
            ps.setString(9, r.updatedAt);
            ps.setLong(10, r.id);
            ps.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM records WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public int count() throws SQLException {
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM records")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // ------------------------------------------------------------------

    private void bind(PreparedStatement ps, Record r) throws SQLException {
        ps.setString(1, r.title);
        ps.setString(2, r.type);
        ps.setString(3, r.date);
        ps.setString(4, r.purpose);
        ps.setString(5, r.dataText);
        ps.setString(6, r.conclusion);
        ps.setString(7, r.tags);
        if (r.resultValue == null) {
            ps.setNull(8, java.sql.Types.REAL);
        } else {
            ps.setDouble(8, r.resultValue);
        }
        ps.setString(9, r.createdAt);
        ps.setString(10, r.updatedAt);
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<Record> query(String sql, Binder binder) throws SQLException {
        List<Record> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            if (binder != null) {
                binder.bind(ps);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        }
        return out;
    }

    private Record map(ResultSet rs) throws SQLException {
        Record r = new Record();
        r.id = rs.getLong("id");
        r.title = rs.getString("title");
        r.type = rs.getString("type");
        r.date = rs.getString("date");
        r.purpose = rs.getString("purpose");
        r.dataText = rs.getString("data_text");
        r.conclusion = rs.getString("conclusion");
        r.tags = rs.getString("tags");
        double rv = rs.getDouble("result_value");
        r.resultValue = rs.wasNull() ? null : rv;
        r.createdAt = rs.getString("created_at");
        r.updatedAt = rs.getString("updated_at");
        return r;
    }
}