package com.qw.agent.line.order;

import com.qw.agent.line.model.TradeSignalRecord;
import com.qw.agent.line.store.KlineStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 订单管理服务 —— 执行买卖操作并将记录持久化到 trade_signal 表。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final KlineStore klineStore;

    public OrderService(KlineStore klineStore) {
        this.klineStore = klineStore;
    }

    /**
     * 执行一次买卖操作并记录。
     *
     * @param symbol    交易对（如 BTCUSDT）
     * @param direction 方向：LONG / SHORT / CLOSE
     * @param price     执行价格
     * @param amount    金额（USDT）
     * @param score     策略评分
     * @param leverage  杠杆倍数
     * @param balance   执行时的账号余额（USDT）
     * @param reason    操作原因
     * @return 持久化后的记录
     */
    public TradeSignalRecord executeOrder(String symbol, String direction,
                                          double price, double amount,
                                          int score, int leverage,
                                          double balance, String reason) {
        TradeSignalRecord record = new TradeSignalRecord();
        record.setId(TradeSignalRecord.generateId());
        record.setSymbol(symbol);
        record.setTime(System.currentTimeMillis() / 1000);
        record.setDirection(direction);
        record.setPrice(BigDecimal.valueOf(price));
        record.setAmount(BigDecimal.valueOf(amount));
        record.setScore(score);
        record.setLeverage(leverage);
        record.setBalance(balance);
        record.setReason(reason);
        record.setCreatedAt(LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        klineStore.saveTradeSignal(record);
        log.info("订单执行并记录: {} {} price={} amount={} score={} leverage={}x balance={} reason={}",
                direction, symbol, price, amount, score, leverage, balance, reason);
        return record;
    }
}
