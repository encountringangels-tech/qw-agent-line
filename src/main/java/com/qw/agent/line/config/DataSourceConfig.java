package com.qw.agent.line.config;

import com.qw.agent.line.util.DbPathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;

/**
 * 数据源配置 —— 自动解析数据库文件路径。
 * <p>
 * 无论从父目录 {@code C:\git-lotto} 还是直接进入 {@code qw-agent-line} 启动，
 * 由 {@link DbPathUtil} 统一处理路径解析。
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    public DataSource dataSource() {
        String dbPath = DbPathUtil.getDbPath();
        log.info("数据库路径: {}", dbPath);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(DbPathUtil.getJdbcUrl());
        return ds;
    }
}
