package test.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class BackTest {
    public static void main(String[] args) throws Exception {
        // ⚠️ 注意: TradFi 美股合约（如 MUUSDT）必须用 fstream，不能用 stream.binancefuture.com
        String ws = "wss://fstream.binance.com";

        // 开仓价格（用户设置）
        double entryPrice = 1090.0;

        // 保存最新成交价
        AtomicReference<String> latestPrice = new AtomicReference<>("等待数据...");
        ObjectMapper mapper = new ObjectMapper();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("HH:mm:ss");

        // 连接 Binance WebSocket，订阅 MUUSDT 实时成交
        HttpClient client = HttpClient.newHttpClient();
        WebSocket webSocket = client.newWebSocketBuilder()
                .buildAsync(URI.create(ws + "/ws/muusdt@trade"), new WebSocket.Listener() {

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        System.out.println("[WebSocket 已连接] " + LocalDateTime.now().format(dtf));
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        try {
                            JsonNode node = mapper.readTree(data.toString());
                            String price = node.get("p").asText();
                            String qty = node.get("q").asText();
                            latestPrice.set(price);
                        } catch (Exception e) {
                            System.err.println("解析消息失败: " + e.getMessage());
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        System.err.println("[WebSocket 错误] " + error.getMessage());
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        System.out.println("[WebSocket 已关闭] code=" + statusCode + " reason=" + reason);
                        return null;
                    }
                }).join();

        // 每 5 秒打印一次最新价格和盈利点数
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            String now = LocalDateTime.now().format(dtf);
            String priceStr = latestPrice.get();
            String profitStr;
            try {
                double currentPrice = Double.parseDouble(priceStr);
                double points = currentPrice - entryPrice;
                profitStr = String.format("%+.2f", points);
            } catch (Exception e) {
                profitStr = "---";
            }
            System.out.println(now + " MU  现价: " + priceStr + "  盈点数: " + profitStr);
        }, 0, 5, TimeUnit.SECONDS);

        // 保持主线程运行
        Thread.sleep(Long.MAX_VALUE);
    }

}
