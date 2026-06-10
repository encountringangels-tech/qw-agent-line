package com.qw.agent.line.store;

import com.qw.agent.line.model.Kline;
import com.qw.agent.line.model.MACDVPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.io.File;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * SQLite 数据访问层 —— 读写 K 线和 MACD-V 数据。
 * <p>
 * 建表、批量写入、按条件读取均在此完成。
 */
@Repository
public class KlineStore {

    private static final Logger log = LoggerFactory.getLogger(KlineStore.class);

    private final JdbcTemplate jdbc;

    public KlineStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        ensureDbDir();
        initTables();
    }

    /**
     * 确保数据库文件所在目录存在（SQLite 驱动只创建文件，不创建目录）。
     */
    private void ensureDbDir() {
        try {
            String userDir = System.getProperty("user.dir");
            File dir = new File(userDir).getName().equals("qw-agent-line")
                    ? new File(userDir, "data")
                    : new File(userDir, "qw-agent-line/data");
            if (!dir.exists()) {
                dir.mkdirs();
                log.info("已创建数据库目录: {}", dir.getAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("无法创建数据库目录: {}", e.getMessage());
        }
    }

    // ==================== 初始化建表 ====================

    private void initTables() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS kline (
                symbol     TEXT    NOT NULL,
                interval   TEXT    NOT NULL,
                open_time  INTEGER NOT NULL,
                open       REAL    NOT NULL,
                high       REAL    NOT NULL,
                low        REAL    NOT NULL,
                close      REAL    NOT NULL,
                volume     REAL    NOT NULL,
                PRIMARY KEY (symbol, interval, open_time)
            )
        """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS macdv_point (
                symbol   TEXT    NOT NULL,
                interval TEXT    NOT NULL,
                time     INTEGER NOT NULL,
                macdV    REAL,
                signal   REAL,
                hist     REAL,
                PRIMARY KEY (symbol, interval, time)
            )
        """);

        log.info("SQLite 表已就绪 (kline, macdv_point)");
    }

    // ==================== K 线操作 ====================

    /** 批量写入 K 线（幂等，重复 open_time 自动跳过） */
    public void saveKlines(String symbol, String interval, List<Kline> klines) {
        String sql = """
            INSERT OR IGNORE INTO kline (symbol, interval, open_time, open, high, low, close, volume)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;

        jdbc.batchUpdate(sql, klines, klines.size(), (ps, kline) -> {
            ps.setString(1, symbol);
            ps.setString(2, interval);
            ps.setLong(3, kline.getOpenTime());
            ps.setDouble(4, kline.getOpen().doubleValue());
            ps.setDouble(5, kline.getHigh().doubleValue());
            ps.setDouble(6, kline.getLow().doubleValue());
            ps.setDouble(7, kline.getClose().doubleValue());
            ps.setDouble(8, kline.getVolume().doubleValue());
        });

        log.debug("已写入 {} 条 K 线 [{}/{}]", klines.size(), symbol, interval);
    }

    /** 读取某个 (symbol, interval) 的最新 limit 条 K 线（按时间正序） */
    public List<Kline> getKlines(String symbol, String interval, int limit) {
        String sql = """
            SELECT open_time, open, high, low, close, volume
            FROM kline
            WHERE symbol = ? AND interval = ?
            ORDER BY open_time DESC
            LIMIT ?
        """;

        List<Kline> result = jdbc.query(sql, this::mapKline, symbol, interval, limit);
        // 翻转成时间正序（calculator 需要正序）
        Collections.reverse(result);
        return result;
    }

    /** 本地已有的最新一条 K 线开盘时间戳（毫秒），没有则返回 0 */
    public long getLatestOpenTime(String symbol, String interval) {
        String sql = "SELECT COALESCE(MAX(open_time), 0) FROM kline WHERE symbol = ? AND interval = ?";
        return Objects.requireNonNullElse(
                jdbc.queryForObject(sql, Long.class, symbol, interval), 0L);
    }

    /** 本地 K 线条数 */
    public int countKlines(String symbol, String interval) {
        String sql = "SELECT COUNT(*) FROM kline WHERE symbol = ? AND interval = ?";
        return Objects.requireNonNullElse(
                jdbc.queryForObject(sql, Integer.class, symbol, interval), 0);
    }

    // ==================== MACD-V 操作 ====================

    /** 批量写入 MACD-V 点（幂等） */
    public void saveMACDVPoints(String symbol, String interval, List<MACDVPoint> points) {
        String sql = """
            INSERT OR IGNORE INTO macdv_point (symbol, interval, time, macdV, signal, hist)
            VALUES (?, ?, ?, ?, ?, ?)
        """;

        jdbc.batchUpdate(sql, points, points.size(), (ps, p) -> {
            ps.setString(1, symbol);
            ps.setString(2, interval);
            ps.setLong(3, p.getTime());
            ps.setObject(4, p.getMacdV() != null ? p.getMacdV().doubleValue() : null);
            ps.setObject(5, p.getSignal() != null ? p.getSignal().doubleValue() : null);
            ps.setObject(6, p.getHist() != null ? p.getHist().doubleValue() : null);
        });

        log.debug("已写入 {} 条 MACD-V [{}/{}]", points.size(), symbol, interval);
    }

    /** 读取某个 (symbol, interval) 的全部 MACD-V 点（按时间正序） */
    public List<MACDVPoint> getMACDVPoints(String symbol, String interval) {
        String sql = """
            SELECT time, macdV, signal, hist
            FROM macdv_point
            WHERE symbol = ? AND interval = ?
            ORDER BY time ASC
        """;

        return jdbc.query(sql, this::mapMACDVPoint, symbol, interval);
    }

    /** 本地 MACD-V 点数 */
    public int countMACDVPoints(String symbol, String interval) {
        String sql = "SELECT COUNT(*) FROM macdv_point WHERE symbol = ? AND interval = ?";
        return Objects.requireNonNullElse(
                jdbc.queryForObject(sql, Integer.class, symbol, interval), 0);
    }

    // ==================== 行映射 ====================

    private Kline mapKline(ResultSet rs, int rowNum) throws SQLException {
        return new Kline(
                rs.getLong("open_time"),
                BigDecimal.valueOf(rs.getDouble("open")),
                BigDecimal.valueOf(rs.getDouble("high")),
                BigDecimal.valueOf(rs.getDouble("low")),
                BigDecimal.valueOf(rs.getDouble("close")),
                BigDecimal.valueOf(rs.getDouble("volume"))
        );
    }

    private MACDVPoint mapMACDVPoint(ResultSet rs, int rowNum) throws SQLException {
        double macdV = rs.getDouble("macdV");
        double signal = rs.getDouble("signal");
        double hist = rs.getDouble("hist");
        boolean hasMacdV = !rs.wasNull();

        return new MACDVPoint(
                rs.getLong("time"),
                hasMacdV ? BigDecimal.valueOf(macdV) : null,
                hasMacdV ? BigDecimal.valueOf(signal) : null,
                hasMacdV ? BigDecimal.valueOf(hist) : null
        );
    }
}
