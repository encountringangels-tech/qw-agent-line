package com.qw.agent.line.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.File;

/**
 * 数据源配置 —— 自动解析数据库文件路径。
 * <p>
 * 无论从父目录 {@code C:\git-lotto} 还是直接进入 {@code qw-agent-line} 启动，
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    public DataSource dataSource() {
        String userDir = System.getProperty("user.dir");
        File dataDir = resolveDataDir(userDir);

        // 创建目录
        if (!dataDir.exists()) {
            dataDir.mkdirs();
            log.info("已创建数据库目录: {}", dataDir.getAbsolutePath());
        }

        String dbPath = new File(dataDir, "agent-line.db").getAbsolutePath();
        log.info("数据库路径: {}", dbPath);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath);
        return ds;
    }

    /**
     * 解析数据库目录：检测 user.dir 是否已经是项目目录。
     * <ul>
     *   <li>从父目录启动 {@code C:\git-lotto} → {@code C:\git-lotto\qw-agent-line\data}</li>
     *   <li>从项目目录启动 {@code C:\git-lotto\qw-agent-line} → {@code C:\git-lotto\qw-agent-line\data}</li>
     * </ul>
     */
    static File resolveDataDir(String userDir) {
        File dir = new File(userDir);
        if (dir.getName().equals("qw-agent-line")) {
            return new File(dir, "data");
        }
        return new File(dir, "qw-agent-line/data");
    }
}
