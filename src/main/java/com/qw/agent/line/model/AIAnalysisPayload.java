package com.qw.agent.line.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 AI 分析数据包 —— 短字段名 + 自解释 legend，最大化数据密度。
 *
 * <pre>
 * {
 *   "generatedAt": "2026-06-13T18:30:00",
 *   "symbol": "BTCUSDT",
 *   "legend": {"t":"time(sec)","o":"open","h":"high",...},
 *   "timeframes": {
 *     "5m":  [{"t":...,"o":...,...}, ...],
 *     "15m": [...],
 *     ...
 *   }
 * }
 * </pre>
 */
@JsonPropertyOrder({"generatedAt", "symbol", "legend", "dataFrom", "dataTo", "timeframes"})
public class AIAnalysisPayload {

    private String generatedAt;
    private String symbol;

    /** 数据起始时间 */
    private String dataFrom;
    /** 数据结束时间 */
    private String dataTo;

    /** 字段映射说明：短名 → 含义 */
    private Map<String, String> legend;

    /** 各周期原始数据，key = interval */
    private Map<String, List<AIAnalysisRow>> timeframes;

    public AIAnalysisPayload() {
        this.timeframes = new LinkedHashMap<>();
    }

    // ---- getter / setter ----

    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getDataFrom() { return dataFrom; }
    public void setDataFrom(String dataFrom) { this.dataFrom = dataFrom; }

    public String getDataTo() { return dataTo; }
    public void setDataTo(String dataTo) { this.dataTo = dataTo; }

    public Map<String, String> getLegend() { return legend; }
    public void setLegend(Map<String, String> legend) { this.legend = legend; }

    public Map<String, List<AIAnalysisRow>> getTimeframes() { return timeframes; }
    public void setTimeframes(Map<String, List<AIAnalysisRow>> timeframes) { this.timeframes = timeframes; }

    public void putRows(String interval, List<AIAnalysisRow> rows) {
        this.timeframes.put(interval, rows);
    }

    /** 初始化 legend（字段短名 → 含义），AI 依此理解数据 */
    public void initLegend() {
        this.legend = new LinkedHashMap<>();
        this.legend.put("t", "time(unix seconds)");
        this.legend.put("o", "open");
        this.legend.put("h", "high");
        this.legend.put("l", "low");
        this.legend.put("c", "close");
        this.legend.put("v", "volume(base asset)");
        this.legend.put("mv", "MACD-V line");
        this.legend.put("sg", "Signal line(EMA of MACD-V)");
        this.legend.put("hs", "Histogram(MACD-V - Signal)");
        this.legend.put("gc", "goldenCross(MACD-V cross ABOVE Signal)");
        this.legend.put("dc", "deathCross(MACD-V cross BELOW Signal)");
    }

    // ========== 内嵌类：单行合并数据 ==========

    /**
     * 一行合并数据：K 线 OHLCV + MACD-V 三线 + 金叉/死叉标记。
     * 字段名极短，含义见父级 legend。
     */
    @JsonPropertyOrder({"t","o","h","l","c","v","mv","sg","hs","gc","dc"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AIAnalysisRow {

        @JsonProperty("t")  private long time;
        @JsonProperty("o")  private double open;
        @JsonProperty("h")  private double high;
        @JsonProperty("l")  private double low;
        @JsonProperty("c")  private double close;
        @JsonProperty("v")  private double volume;

        @JsonProperty("mv") private Double macdv;
        @JsonProperty("sg") private Double signal;
        @JsonProperty("hs") private Double hist;

        @JsonProperty("gc") private boolean goldenCross;
        @JsonProperty("dc") private boolean deathCross;

        // ---- getter / setter ----

        public long getTime() { return time; }
        public void setTime(long time) { this.time = time; }

        public double getOpen() { return open; }
        public void setOpen(double open) { this.open = open; }

        public double getHigh() { return high; }
        public void setHigh(double high) { this.high = high; }

        public double getLow() { return low; }
        public void setLow(double low) { this.low = low; }

        public double getClose() { return close; }
        public void setClose(double close) { this.close = close; }

        public double getVolume() { return volume; }
        public void setVolume(double volume) { this.volume = volume; }

        public Double getMacdv() { return macdv; }
        public void setMacdv(Double macdv) { this.macdv = macdv; }

        public Double getSignal() { return signal; }
        public void setSignal(Double signal) { this.signal = signal; }

        public Double getHist() { return hist; }
        public void setHist(Double hist) { this.hist = hist; }

        public boolean isGoldenCross() { return goldenCross; }
        public void setGoldenCross(boolean goldenCross) { this.goldenCross = goldenCross; }

        public boolean isDeathCross() { return deathCross; }
        public void setDeathCross(boolean deathCross) { this.deathCross = deathCross; }
    }
}
