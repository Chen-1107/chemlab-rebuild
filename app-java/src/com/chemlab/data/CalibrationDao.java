package com.chemlab.data;

import com.chemlab.model.Calibration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 标定曲线的保存与读取。 */
public final class CalibrationDao {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Database db;

    public CalibrationDao(Database db) {
        this.db = db;
    }

    private Connection conn() {
        return db.connection();
    }

    public List<Calibration> listAll() throws SQLException {
        List<Calibration> out = new ArrayList<>();
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM calibrations ORDER BY id DESC")) {
            while (rs.next()) {
                out.add(map(rs));
            }
        }
        return out;
    }

    public long insert(Calibration c) throws SQLException {
        if (c.createdAt == null || c.createdAt.isEmpty()) {
            c.createdAt = LocalDateTime.now().format(TS);
        }
        String sql = "INSERT INTO calibrations(name, x_json, y_json, slope, intercept, r2, syx, " +
                "lod, loq, created_at) VALUES(?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, c.name);
            ps.setString(2, c.xJson);
            ps.setString(3, c.yJson);
            ps.setDouble(4, c.slope);
            ps.setDouble(5, c.intercept);
            ps.setDouble(6, c.r2);
            ps.setDouble(7, c.syx);
            ps.setDouble(8, c.lod);
            ps.setDouble(9, c.loq);
            ps.setString(10, c.createdAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    c.id = keys.getLong(1);
                }
            }
        }
        return c.id;
    }

    public void delete(long id) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM calibrations WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private Calibration map(ResultSet rs) throws SQLException {
        Calibration c = new Calibration();
        c.id = rs.getLong("id");
        c.name = rs.getString("name");
        c.xJson = rs.getString("x_json");
        c.yJson = rs.getString("y_json");
        c.slope = rs.getDouble("slope");
        c.intercept = rs.getDouble("intercept");
        c.r2 = rs.getDouble("r2");
        c.syx = rs.getDouble("syx");
        c.lod = rs.getDouble("lod");
        c.loq = rs.getDouble("loq");
        c.createdAt = rs.getString("created_at");
        return c;
    }
}