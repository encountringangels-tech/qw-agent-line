package com.qw.agent.line.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * 数据库目录路径工具类 —— 统一处理 SQLite 数据文件路径解析逻辑。
 * <p>
 * 合并自:
 * <ul>
 *   <li>{@code DataSourceConfig.resolveDataDir()}</li>
 *   <li>{@code KlineStore.ensureDbDir()}</li>
 *   <li>{@code MACDVController.main()} 中的路径拼接</li>
 * </ul>
 *
 * <p><b>路径约定：</b>
 * <ul>
 *   <li>从父目录 {@code C:\\git-lotto} 启动 → {@code C:\\git-lotto\\qw-agent-line\\data}</li>
 *   <li>从项目目录 {@code C:\\git-lotto\\qw-agent-line} 启动 → {@code C:\\git-lotto\\qw-agent-line\\data}</li>
 * </ul>
 */
public final class DbPathUtil {

    private static final Logger log = LoggerFactory.getLogger(DbPathUtil.class);

    /** 本项目根目录名 */
    public static final String PROJECT_DIR = "qw-agent-line";

    /** 数据子目录名 */
    public static final String DATA_DIR = "data";

    /** SQLite 数据库文件名 */
    public static final String DB_FILE = "agent-line.db";

    private DbPathUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 解析数据目录：检测 {@code userDir} 是否已经是项目目录。
     *
     * @param userDir 当前工作目录（通常为 {@code System.getProperty("user.dir")}）
     * @return 数据目录的 File 对象
     */
    public static File resolveDataDir(String userDir) {
        File dir = new File(userDir);
        if (dir.getName().equals(PROJECT_DIR)) {
            return new File(dir, DATA_DIR);
        }
        return new File(dir, PROJECT_DIR + "/" + DATA_DIR);
    }

    /**
     * 解析数据目录并使用当前工作目录。
     */
    public static File resolveDataDir() {
        return resolveDataDir(System.getProperty("user.dir"));
    }

    /**
     * 确保数据目录存在，不存在则创建。
     *
     * @return 数据目录的 File 对象
     */
    public static File ensureDataDir() {
        File dataDir = resolveDataDir();
        if (!dataDir.exists()) {
            if (dataDir.mkdirs()) {
                log.info("已创建数据库目录: {}", dataDir.getAbsolutePath());
            } else {
                log.warn("无法创建数据库目录: {}", dataDir.getAbsolutePath());
            }
        }
        return dataDir;
    }

    /**
     * 获取 SQLite 数据库文件的完整路径。
     */
    public static String getDbPath() {
        return new File(ensureDataDir(), DB_FILE).getAbsolutePath();
    }

    /**
     * 获取 SQLite JDBC URL。
     */
    public static String getJdbcUrl() {
        return "jdbc:sqlite:" + getDbPath();
    }
}
