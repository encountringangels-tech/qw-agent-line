package com.qw.agent.line.openapi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binance API 配置 — 映射自 application.yml 的 binance.* 配置项。
 * <p>
 * 默认使用币安合约测试网: https://testnet.binancefuture.com
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "binance")
public class BinanceConfig {

    private ApiConfig api;
    private FuturesConfig futures;

    @Data
    public static class ApiConfig {
        private String baseUrl;
        private String wsBaseUrl;
        private String apiKey;
        private String secretKey;
        private int recvWindow;
    }

    @Data
    public static class FuturesConfig {
        private boolean testnet;
        private int defaultLeverage;
        private int maxLeverage;
        private int defaultPositionSizeUsdt;
        private int maxPositionSizeUsdt;
    }
}
