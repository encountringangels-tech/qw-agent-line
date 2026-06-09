package com.qw.agent.line.controller;

import com.qw.agent.line.service.MACDVService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * MACD-V 图表 REST 控制器 —— 最薄的一层，仅处理 HTTP 请求与响应。
 * <p>
 * 所有业务逻辑委托给 {@link MACDVService}。
 */
@RestController
@RequestMapping("/macdv")
public class MACDVController {

    private final MACDVService macdvService;

    public MACDVController(MACDVService macdvService) {
        this.macdvService = macdvService;
    }

    /**
     * 获取 MACD-V 图表所需的全部数据。
     *
     * @param symbol     交易对（默认 BTCUSDT）
     * @param interval   K 线周期（默认 1h）
     * @param limit      返回 K 线条数（默认 1000）
     * @param fastLen    快线 EMA 周期（默认 12）
     * @param slowLen    慢线 EMA 周期（默认 26）
     * @param signalLen  信号线 EMA 周期（默认 9）
     * @param atrLen     ATR 周期（默认 26）
     * @param overbought 超买阈值（默认 150）
     * @param oversold   超卖阈值（默认 -150）
     * @return 包含 klines / macdv / signals / latestSignal 的 JSON
     */
    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> getData(
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam(defaultValue = "1h") String interval,
            @RequestParam(defaultValue = "1000") int limit,
            @RequestParam(defaultValue = "12") int fastLen,
            @RequestParam(defaultValue = "26") int slowLen,
            @RequestParam(defaultValue = "9") int signalLen,
            @RequestParam(defaultValue = "26") int atrLen,
            @RequestParam(defaultValue = "150") int overbought,
            @RequestParam(defaultValue = "-150") int oversold) {

        Map<String, Object> data = macdvService.getChartData(
                symbol, interval, limit,
                fastLen, slowLen, signalLen, atrLen,
                overbought, oversold);

        return ResponseEntity.ok(data);
    }
}
