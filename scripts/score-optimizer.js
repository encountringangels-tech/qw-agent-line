#!/usr/bin/env node
/**
 * 评分优化回测 — 基准(当前规则) vs 优化(过滤Score10)
 * 
 * 使用 BacktestEngine，严格无未来函数
 * 
 * 输出:
 *   data/backtest-report-BTCUSDT-300d.md       — 基准回测报告
 *   data/score-optimization-report.md           — 优化对比报告
 */

const path = require('path');
const fs = require('fs');
const { BacktestEngine } = require('./backtest-engine');

const JSON_PATH = path.join(__dirname, '..', 'data', 'ai-analysis-BTCUSDT-300d-20260615T220842.json');
const REPORT_OUT = path.join(__dirname, '..', 'data', 'backtest-report-BTCUSDT-300d.md');
const COMPARE_OUT = path.join(__dirname, '..', 'data', 'score-optimization-report.md');

console.log('=== 评分优化回测 (BacktestEngine 20260616 V9) ===\n');

// 基准：当前规则（含 Score 10）
console.log('运行基准（当前规则，含Score10）...');
const engineBase = new BacktestEngine({ SKIP_SCORE_10: false });
engineBase.loadData(JSON_PATH);
const baseline = engineBase.run();
console.log(`  基准: ${baseline.totalTrades}笔 | 胜率${baseline.winRate.toFixed(1)}% | 盈亏$${(baseline.totalReturn/1000).toFixed(1)}K | 回撤${baseline.maxDrawdown.toFixed(1)}%`);
console.log(`  杠杆: 2x=${baseline.lever2.count}笔($${(baseline.lever2.pnl/1000).toFixed(1)}K) 3x=${baseline.lever3.count}笔($${(baseline.lever3.pnl/1000).toFixed(1)}K)`);

const b10 = baseline.byScore[10];
if (b10) {
  console.log(`  Score10: ${b10.cnt}笔 | 胜率${(b10.w/b10.cnt*100).toFixed(1)}% | 盈亏$${(b10.pnl/1000).toFixed(1)}K`);
}

// 优化：过滤 Score 10
console.log('\n运行优化（过滤Score10）...');
const engineOpt = new BacktestEngine({ SKIP_SCORE_10: true });
engineOpt.loadData(JSON_PATH);
const optimized = engineOpt.run();
console.log(`  优化: ${optimized.totalTrades}笔 | 胜率${optimized.winRate.toFixed(1)}% | 盈亏$${(optimized.totalReturn/1000).toFixed(1)}K | 回撤${optimized.maxDrawdown.toFixed(1)}%`);

// 生成基准报告
console.log('\n生成基准报告...');
const baseReport = engineBase.generateReport(baseline);
fs.writeFileSync(REPORT_OUT, baseReport, 'utf-8');
console.log(`  基准报告: ${REPORT_OUT}`);

// 生成对比报告
console.log('生成对比报告...');
const compareReport = BacktestEngine.generateCompareReport(baseline, optimized);
fs.writeFileSync(COMPARE_OUT, compareReport, 'utf-8');
console.log(`  对比报告: ${COMPARE_OUT}`);

// 总结
const pnlDelta = optimized.totalReturn - baseline.totalReturn;
const wrDelta = optimized.winRate - baseline.winRate;
const ddDelta = optimized.maxDrawdown - baseline.maxDrawdown;

console.log('\n' + '='.repeat(60));
console.log('结果总览');
console.log('='.repeat(60));
console.log(`基准: ${baseline.totalTrades}笔 | 胜率${baseline.winRate.toFixed(1)}% | 回撤${baseline.maxDrawdown.toFixed(1)}% | 盈亏$${(baseline.totalReturn/1000).toFixed(1)}K`);
console.log(`优化: ${optimized.totalTrades}笔 | 胜率${optimized.winRate.toFixed(1)}% | 回撤${optimized.maxDrawdown.toFixed(1)}% | 盈亏$${(optimized.totalReturn/1000).toFixed(1)}K`);
console.log(`变化: 盈亏${(pnlDelta>=0?'+':'')}$${(pnlDelta/1000).toFixed(1)}K | 胜率${(wrDelta>=0?'+':'')}${wrDelta.toFixed(2)}pp | 回撤${(ddDelta>=0?'+':'')}${ddDelta.toFixed(2)}pp`);
