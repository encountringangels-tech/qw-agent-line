package com.qw.agent.line.order;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

/**
 * 订单 REST 控制器 — 提供币安合约的买卖交易接口。
 * <p>
 * 基础路径: /api/order
 */
@RestController
@RequestMapping("/api/order")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

}
