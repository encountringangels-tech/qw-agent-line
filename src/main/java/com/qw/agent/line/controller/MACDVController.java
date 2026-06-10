package com.qw.agent.line.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qw.agent.line.indicator.MACDVCalculator;
import com.qw.agent.line.service.MACDVService;
import com.qw.agent.line.strategy.MACDVSignalGenerator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * MACD-V 图表 REST 控制器 —— 最薄的一层，仅处理 HTTP 请求与响应。
 * <p>
 * 所有业务逻辑委托给 {@link MACDVService}。
 * <p>
 * 也可通过 main 方法直接调用（不启动 Spring）：
 * <pre>
 *   MACDVController.main(new String[]{"ETHUSDT", "15m", "200"});
 * </pre>
 */
@RestController
@RequestMapping("/macdv")
public class MACDVController {

    private final MACDVService macdvService;

    public MACDVController(MACDVService macdvService) {
        this.macdvService = macdvService;
    }

    /**
     * 直接运行 main 方法获取数据（不依赖 Spring 容器）。
     * @param args [symbol, interval, limit, fastLen, slowLen, signalLen, atrLen, overbought, oversold]
     */
    public static void main(String[] args) throws Exception {
        // 默认参数
        String symbol     = "BTCUSDT";
        String interval   = "1h";
        int limit         = 1000;
        int fastLen       = 12;
        int slowLen       = 26;
        int signalLen     = 9;
        int atrLen        = 26;
        int overbought    = 150;
        int oversold      = -150;

        if (args.length >= 1) symbol     = args[0];
        if (args.length >= 2) interval   = args[1];
        if (args.length >= 3) limit      = Integer.parseInt(args[2]);
        if (args.length >= 4) fastLen    = Integer.parseInt(args[3]);
        if (args.length >= 5) slowLen    = Integer.parseInt(args[4]);
        if (args.length >= 6) signalLen  = Integer.parseInt(args[5]);
        if (args.length >= 7) atrLen     = Integer.parseInt(args[6]);
        if (args.length >= 8) overbought = Integer.parseInt(args[7]);
        if (args.length >= 9) oversold   = Integer.parseInt(args[8]);

        // 手动组装依赖（不启动 Spring）
        MACDVCalculator calc = new MACDVCalculator();
        MACDVSignalGenerator sig = new MACDVSignalGenerator();
        MACDVService service = new MACDVService(calc, sig);

        long start = System.currentTimeMillis();
        Map<String, Object> data = service.getChartData(
                symbol, interval, limit,
                fastLen, slowLen, signalLen, atrLen,
                overbought, oversold);
        long elapsed = System.currentTimeMillis() - start;

        // 打印统计
        int k = ((java.util.List<?>) data.get("klines")).size();
        int m = ((java.util.List<?>) data.get("macdv")).size();
        long v = ((java.util.List<?>) data.get("macdv")).stream()
                .filter(r -> ((Map<String, Object>) r).get("macdV") != null).count();
        int s = ((java.util.List<?>) data.get("signals")).size();
        System.out.println("===== MACD-V 数据 =====");
        System.out.println("K 线: " + k + " 条");
        System.out.println("MACD-V: " + m + " 条（有效 " + v + " 点）");
        System.out.println("买卖信号: " + s + " 个");
        System.out.println("耗时: " + elapsed + "ms");
        System.out.println();

        // 输出完整 JSON
        ObjectMapper mapper = new ObjectMapper();
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data));
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
