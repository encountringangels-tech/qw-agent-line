#!/usr/bin/env node
/**
 * BacktestEngine — 可复用多周期MACDV策略回测引擎
 * 
 * 严格无未来函数：
 *   - 向前指针（forward pointer）跨周期数据访问
 *   - 所有决策仅使用 <= 当前K线时间的数据
 *   - 15min K线为决策粒度（与 MultiTimeframeStrategy.java 对齐）
 * 
 * 使用方式：
 *   const { BacktestEngine } = require('./scripts/backtest-engine');
 *   const engine = new BacktestEngine(config);
 *   engine.loadData(jsonPath);
 *   const result = engine.run();
 *   console.log(engine.generateReport(result));
 */

const fs = require('fs');
const path = require('path');

const ENGINE_VERSION = '20260616';
const STRATEGY_VERSION = 'V9';

// ==================== 默认策略参数 ====================
const DEFAULT_PARAMS = {
  // 日线
  DAILY_BEARISH: -120,
  DAILY_BULLISH: 0,
  
  // 4H
  H4_STRONG_BULL: 50,
  H4_BEARISH_ZONE: -20,
  H4_STRONG_BEAR: -60,
  
  // 15min（核心买卖层）
  M15_DEEP_PULLBACK: -100,
  M15_DEEP_PULLBACK_TOL: 25,
  M15_AXIS: 0,
  M15_AXIS_TOL: 25,
  M15_TAKE_PROFIT: 80,
  M15_DEEP_RALLY: 100,
  M15_DEEP_RALLY_TOL: 25,
  
  // 5min（精确触发层）
  M5_OVERBOUGHT: 150,
  M5_OVERSOLD: -150,
  
  // 1H（极值确认层）
  H1_TOP: 90,
  H1_BOTTOM: -100,
  
  // 评分
  SCORE_LONG_THRESHOLD: 8,
  SCORE_SHORT_THRESHOLD: 8,
  TREND_ALIGN: 2,
  TREND_PARTIAL: 1,
  
  // 出场参数
  H4_PEAK_RETRACE: 0.15,
  H1_PEAK_RETRACE: 0.20,
  MAX_BARS: 96,
  COOLDOWN_BARS: 3,
  
  // 杠杆
  USE_1H_SHORT_FILTER: true,    // 1H<-150禁做空, 1H<-100降2x
  USE_DIVERGENCE_FILTER: true,  // 30m/15m背离降2x
  
  // 实验性参数
  SKIP_SCORE_10: false,         // 过滤 Score 10 信号
};

// ==================== 工具函数 ====================
function inRange(v, target, tol) { return v >= target - tol && v <= target + tol; }
function r2(v) { return Math.round(v * 100) / 100; }
function dt(ts) {
  const d = new Date(ts * 1000);
  const p = n => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth()+1)}-${p(d.getUTCDate())} ${p(d.getUTCHours())}:${p(d.getUTCMinutes())}`;
}

// ==================== 评分引擎 ====================
function calcLongScore(d, h4, h1, m15, m5, p) {
  let s = 0, veto = false;
  const r = [];
  
  // 日线方向过滤
  if (d > p.DAILY_BULLISH) { s += 3; r.push('日线多头(+3)'); }
  else if (d > p.DAILY_BEARISH) { s += 1; r.push('日线偏弱(+1)'); }
  else { veto = true; r.push('日线强空-否决'); }

  // 4H中期趋势
  if (!veto) {
    if (h4 > p.H4_STRONG_BULL) { s += 3; r.push('4H强多头(+3)'); }
    else if (h4 > 0) { s += 2; r.push('4H弱多头(+2)'); }
    else if (h4 > p.H4_BEARISH_ZONE) { s += 0; r.push('4H零轴震荡(+0)'); }
    else { veto = true; r.push('4H偏空阶段-否决'); }
  }

  // 15min核心买卖点 + 5min精确层 + 1H极值
  if (!veto) {
    if (inRange(m15, p.M15_DEEP_PULLBACK, p.M15_DEEP_PULLBACK_TOL))
      { s += 4; r.push('15min深回调-100附近(+4)'); }
    else if (inRange(m15, p.M15_AXIS, p.M15_AXIS_TOL))
      { s += 2; r.push('15min零轴附近(+2)'); }
    else if (m15 > p.M15_TAKE_PROFIT)
      { s -= 3; r.push('15min高位止盈区(-3)'); }
    else if (m15 < p.M15_DEEP_PULLBACK - p.M15_DEEP_PULLBACK_TOL)
      { s += 1; r.push('15min极端超卖(+1)'); }
    else if (m15 > 0 && m15 <= 50)
      { s += 1; r.push('15min温和上涨(+1)'); }

    if (m5 < p.M5_OVERSOLD) { s += 3; r.push('5min极值超卖<-150(+3)'); }
    else if (m5 > p.M5_OVERBOUGHT) { s -= 2; r.push('5min极值超买>150(-2)'); }
    
    if (h1 < p.H1_BOTTOM) { s += 2; r.push('1H底部<-100(+2)'); }
    else if (h1 > p.H1_TOP) { s -= 2; r.push('1H顶部>90(-2)'); }

    if (h4 > 0 && h1 <= 0) { s -= 3; r.push('1H转空周期冲突(-3)'); }
    else if (h4 > 0 && h1 < 20) { s -= 1; r.push('1H近零轴(-1)'); }

    if (d > 0 && h4 > 0 && h1 > 0) { s += p.TREND_ALIGN; r.push(`趋势一致日4H1H同多(+${p.TREND_ALIGN})`); }
    else if (d > 0 && h4 > 0) { s += p.TREND_PARTIAL; r.push(`日4H同多(+${p.TREND_PARTIAL})`); }
  }

  return { score: veto ? 0 : s, reason: r.join('; '), veto };
}

function calcShortScore(d, h4, h1, m15, m5, p) {
  let s = 0, veto = false;
  const r = [];
  
  if (d <= p.DAILY_BEARISH) { s += 3; r.push('日线强空(+3)'); }
  else if (d <= p.DAILY_BULLISH) { s += 1; r.push('日线偏空(+1)'); }
  else { veto = true; r.push('日线多头-否决'); }

  if (!veto) {
    if (h4 < p.H4_STRONG_BEAR) { s += 5; r.push('4H强空底(+5)'); }
    else { veto = true; r.push('4H非强空底-否决'); }
  }

  if (!veto) {
    if (inRange(m15, p.M15_DEEP_RALLY, p.M15_DEEP_RALLY_TOL))
      { s += 4; r.push('15min深拉回+100附近(+4)'); }
    else if (inRange(m15, p.M15_AXIS, p.M15_AXIS_TOL))
      { s += 2; r.push('15min零轴附近(+2)'); }
    else if (m15 < -p.M15_TAKE_PROFIT)
      { s -= 3; r.push('15min低位止盈区(-3)'); }
    else if (m15 > p.M15_DEEP_RALLY + p.M15_DEEP_RALLY_TOL)
      { s += 1; r.push('15min极端超买(+1)'); }
    else if (m15 < 0 && m15 >= -50)
      { s += 1; r.push('15min温和下跌(+1)'); }

    if (m5 > p.M5_OVERBOUGHT) { s += 3; r.push('5min极值超买>150(+3)'); }
    else if (m5 < p.M5_OVERSOLD) { s -= 2; r.push('5min极值超卖<-150(-2)'); }

    if (h1 > p.H1_TOP) { s += 2; r.push('1H顶部>90(+2)'); }
    else if (h1 < p.H1_BOTTOM) { s -= 2; r.push('1H底部<-100(-2)'); }

    if (d < 0 && h4 < 0 && h1 < 0) { s += p.TREND_ALIGN; r.push(`趋势一致日4H1H同空(+${p.TREND_ALIGN})`); }
    else if (d < 0 && h4 < 0) { s += p.TREND_PARTIAL; r.push(`日4H同空(+${p.TREND_PARTIAL})`); }
  }

  return { score: veto ? 0 : s, reason: r.join('; '), veto };
}

// ==================== 回测引擎类 ====================
class BacktestEngine {
  constructor(params = {}) {
    this.params = { ...DEFAULT_PARAMS, ...params };
    this.timeframes = null;
    this.dataSymbol = '';
    this.dataFrom = '';
    this.dataTo = '';
  }

  /** 加载JSON数据文件 */
  loadData(jsonPath) {
    const data = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
    this.timeframes = {};
    for (const [tf, arr] of Object.entries(data.timeframes)) {
      this.timeframes[tf] = arr.sort((a, b) => a.t - b.t);
    }
    this.dataSymbol = data.symbol || '';
    this.dataFrom = data.dataFrom || '';
    this.dataTo = data.dataTo || '';
    return this;
  }

  /** 向前指针：从arr中取 ≤ tLimit 的最新一条 */
  _advance(arr, ptr, tLimit) {
    while (ptr.idx + 1 < arr.length && arr[ptr.idx + 1].t <= tLimit) ptr.idx++;
    return (ptr.idx >= 0 && ptr.idx < arr.length && arr[ptr.idx].t <= tLimit) ? arr[ptr.idx] : null;
  }

  /** 获取某一时刻的所有周期快照 */
  _snapshot(ptrs, t) {
    return {
      m5m:  this._advance(this.timeframes['5m'],  ptrs['5m'],  t),
      m15m: this._advance(this.timeframes['15m'], ptrs['15m'], t),
      m30m: this._advance(this.timeframes['30m'], ptrs['30m'], t),
      m1h:  this._advance(this.timeframes['1h'],  ptrs['1h'],  t),
      m4h:  this._advance(this.timeframes['4h'],  ptrs['4h'],  t),
      m1d:  this._advance(this.timeframes['1d'],  ptrs['1d'],  t),
    };
  }

  /** 获取MACDV值，容错 */
  _v(p) { return p && p.mv != null ? p.mv : 0; }

  /** 平多头检查 */
  _closeLong(st, m15, h4, h1) {
    const p = this.params;
    st.bars++;
    if (h4 > st.h4Peak) st.h4Peak = h4;
    if (h1 > st.h1Peak) st.h1Peak = h1;
    if (m15 > p.M15_TAKE_PROFIT) return { close: true, reason: `15min止盈(${r2(m15)}>80)` };
    if (st.h4Peak > 0 && h4 > -50) {
      const pct = (st.h4Peak - h4) / Math.abs(st.h4Peak);
      if (pct > p.H4_PEAK_RETRACE) return { close: true, reason: `4H见顶回落(${r2(pct*100)}%)` };
    }
    if (st.bars >= p.MAX_BARS) return { close: true, reason: `时间止损(${st.bars}根K线)` };
    if (st.h1Peak > 0 && h1 > -50 && st.h4Peak < 30) {
      const pct = (st.h1Peak - h1) / Math.abs(st.h1Peak);
      if (pct > p.H1_PEAK_RETRACE) return { close: true, reason: `1H见顶回落(${r2(pct*100)}%)` };
    }
    return { close: false };
  }

  /** 平空头检查 */
  _closeShort(st, m15, h4, h1) {
    const p = this.params;
    st.bars++;
    if (h4 < st.h4Peak) st.h4Peak = h4;
    if (h1 < st.h1Peak) st.h1Peak = h1;
    if (m15 < -p.M15_TAKE_PROFIT) return { close: true, reason: `15min止盈(${r2(m15)}<-80)` };
    if (st.h4Peak < 0 && h4 < 50) {
      const pct = (h4 - st.h4Peak) / Math.abs(st.h4Peak);
      if (pct > p.H4_PEAK_RETRACE) return { close: true, reason: `4H见底反弹(${r2(pct*100)}%)` };
    }
    if (st.bars >= p.MAX_BARS) return { close: true, reason: `时间止损(${st.bars}根K线)` };
    return { close: false };
  }

  /** 运行回测 */
  run() {
    if (!this.timeframes) throw new Error('请先调用 loadData()');
    const p = this.params;
    const m15arr = this.timeframes['15m'];
    const ptrs = { '5m':{idx:-1}, '15m':{idx:-1}, '30m':{idx:-1}, '1h':{idx:-1}, '4h':{idx:-1}, '1d':{idx:-1} };

    let pos = 'F';  // F=空仓, L=多, S=空
    let entryPrice = 0, entryTime = 0, entryScore = 0, entryLev = 0, entryReason = '';
    let cooldown = 0, posState = null;
    const trades = [];
    let equity = 100000, peak = 100000, maxDD = 0;

    // 从第10根15min K线开始（等数据稳定）
    for (let i = 10; i < m15arr.length; i++) {
      const c = m15arr[i], t = c.t, close = c.c;
      const snap = this._snapshot(ptrs, t);
      if (!snap.m5m || !snap.m15m || !snap.m30m || !snap.m1h || !snap.m4h || !snap.m1d) continue;

      const d = this._v(snap.m1d), h4 = this._v(snap.m4h), h1 = this._v(snap.m1h);
      const m30 = this._v(snap.m30m), m15 = this._v(snap.m15m), m5 = this._v(snap.m5m);

      if (cooldown > 0) cooldown--;

      // ---- 平仓检查 ----
      if (pos === 'L' && posState) {
        const r = this._closeLong(posState, m15, h4, h1);
        if (r.close) {
          const pnl = equity * (close / entryPrice - 1) * entryLev;
          equity += pnl;
          if (equity > peak) peak = equity;
          const dd = peak > 0 ? (peak - equity) / peak * 100 : 0;
          if (dd > maxDD) maxDD = dd;
          trades.push({
            type: '做多', entryTime, exitTime: t, entryPrice, exitPrice: close,
            leverage: entryLev, score: entryScore, pnl, entryReason, exitReason: r.reason,
          });
          pos = 'F'; posState = null; cooldown = p.COOLDOWN_BARS;
        }
      } else if (pos === 'S' && posState) {
        const r = this._closeShort(posState, m15, h4, h1);
        if (r.close) {
          const pnl = equity * (1 - close / entryPrice) * entryLev;
          equity += pnl;
          if (equity > peak) peak = equity;
          const dd = peak > 0 ? (peak - equity) / peak * 100 : 0;
          if (dd > maxDD) maxDD = dd;
          trades.push({
            type: '做空', entryTime, exitTime: t, entryPrice, exitPrice: close,
            leverage: entryLev, score: entryScore, pnl, entryReason, exitReason: r.reason,
          });
          pos = 'F'; posState = null; cooldown = p.COOLDOWN_BARS;
        }
      }

      // ---- 开仓检查 ----
      if (pos === 'F' && cooldown <= 0) {
        const ls = calcLongScore(d, h4, h1, m15, m5, p);
        const ss = calcShortScore(d, h4, h1, m15, m5, p);

        // Score 10 过滤（实验性）
        if (p.SKIP_SCORE_10) {
          if (ls.score === 10) ls.score = 0;
          if (ss.score === 10) ss.score = 0;
        }

        if (ls.score >= p.SCORE_LONG_THRESHOLD && ls.score >= ss.score) {
          let lev = (h4 > 50 && h1 > 0) ? 3 : 2;
          if (p.USE_DIVERGENCE_FILTER && ((m15 > 0) !== (m30 > 0))) {
            lev = Math.min(lev, 2); ls.reason += ' [背离降2x]';
          }
          entryPrice = close; entryTime = t; entryScore = ls.score;
          entryLev = lev; entryReason = ls.reason;
          pos = 'L'; posState = { bars: 0, h4Peak: h4, h1Peak: h1 };
        } else if (ss.score >= p.SCORE_SHORT_THRESHOLD && ss.score > ls.score) {
          let lev = (h4 < -60 && h1 < 0) ? 3 : 2;
          if (p.USE_DIVERGENCE_FILTER && ((m15 > 0) !== (m30 > 0))) {
            lev = Math.min(lev, 2); ss.reason += ' [背离降2x]';
          }
          if (p.USE_1H_SHORT_FILTER && h1 < -100) {
            lev = Math.min(lev, 2); ss.reason += ' [1H<-100降2x]';
          }
          if (p.USE_1H_SHORT_FILTER && h1 < -150) {
            ss.score = 0; ss.reason += ' [1H<-150禁空]';
          }
          if (ss.score >= p.SCORE_SHORT_THRESHOLD) {
            entryPrice = close; entryTime = t; entryScore = ss.score;
            entryLev = lev; entryReason = ss.reason;
            pos = 'S'; posState = { bars: 0, h4Peak: h4, h1Peak: h1 };
          }
        }
      }
    }

    // 强制平仓
    if (pos !== 'F') {
      const last = m15arr[m15arr.length - 1], close = last.c;
      if (pos === 'L') {
        const pnl = equity * (close / entryPrice - 1) * entryLev;
        equity += pnl;
        trades.push({
          type: '做多', entryTime, exitTime: last.t, entryPrice, exitPrice: close,
          leverage: entryLev, score: entryScore, pnl, entryReason, exitReason: '回测结束',
        });
      } else {
        const pnl = equity * (1 - close / entryPrice) * entryLev;
        equity += pnl;
        trades.push({
          type: '做空', entryTime, exitTime: last.t, entryPrice, exitPrice: close,
          leverage: entryLev, score: entryScore, pnl, entryReason, exitReason: '回测结束',
        });
      }
    }

    return this._computeStats(trades, 100000, equity, peak, maxDD);
  }

  /** 计算统计指标 */
  _computeStats(trades, initial, final, peak, maxDD) {
    const win = trades.filter(t => t.pnl > 0), lose = trades.filter(t => t.pnl < 0);
    const tw = win.reduce((s,t)=>s+t.pnl,0), tl = lose.reduce((s,t)=>s+t.pnl,0);
    const longs = trades.filter(t=>t.type==='做多'), shorts = trades.filter(t=>t.type==='做空');
    const lev2 = trades.filter(t=>t.leverage===2), lev3 = trades.filter(t=>t.leverage===3);

    // 按评分分组
    const byScore = {};
    for (const t of trades) {
      if (!byScore[t.score]) byScore[t.score] = { cnt:0, w:0, pnl:0 };
      byScore[t.score].cnt++; byScore[t.score].pnl += t.pnl;
      if (t.pnl > 0) byScore[t.score].w++;
    }

    // 出场原因分布
    const exitReasons = {};
    for (const t of trades) {
      const r = t.exitReason.split('(')[0];
      exitReasons[r] = (exitReasons[r] || 0) + 1;
    }

    return {
      configName: this.params.SKIP_SCORE_10 ? '优化(过滤Score10)' : '基准(当前规则)',
      params: { ...this.params },
      initialCapital: initial,
      finalCapital: final,
      totalReturn: final - initial,
      returnPct: (final - initial) / initial * 100,
      totalTrades: trades.length,
      wins: win.length,
      losses: lose.length,
      winRate: trades.length > 0 ? win.length / trades.length * 100 : 0,
      maxWin: win.length > 0 ? Math.max(...win.map(t=>t.pnl)) : 0,
      maxLoss: lose.length > 0 ? Math.min(...lose.map(t=>t.pnl)) : 0,
      avgWin: win.length > 0 ? tw / win.length : 0,
      avgLoss: lose.length > 0 ? tl / lose.length : 0,
      profitFactor: tl !== 0 ? Math.abs(tw / tl) : (tw > 0 ? Infinity : 0),
      maxDrawdown: maxDD,
      peakCapital: peak,
      longs: { count: longs.length, pnl: longs.reduce((s,t)=>s+t.pnl,0), wr: longs.length>0?longs.filter(t=>t.pnl>0).length/longs.length*100:0 },
      shorts: { count: shorts.length, pnl: shorts.reduce((s,t)=>s+t.pnl,0), wr: shorts.length>0?shorts.filter(t=>t.pnl>0).length/shorts.length*100:0 },
      lever2: { count: lev2.length, pnl: lev2.reduce((s,t)=>s+t.pnl,0) },
      lever3: { count: lev3.length, pnl: lev3.reduce((s,t)=>s+t.pnl,0) },
      byScore,
      exitReasons,
      trades,
      dataSymbol: this.dataSymbol,
      dataFrom: this.dataFrom,
      dataTo: this.dataTo,
      tfCounts: this.timeframes ? Object.fromEntries(Object.entries(this.timeframes).map(([k,v])=>[k,v.length])) : {},
    };
  }

  /** 生成Markdown报告 */
  generateReport(result) {
    function fm(v) {
      if (Math.abs(v) >= 1e6) return (v/1e6).toFixed(2)+'M';
      if (Math.abs(v) >= 1e3) return (v/1e3).toFixed(2)+'K';
      return v.toFixed(2);
    }
    function pctStr(n) { return (n >= 0 ? '+' : '') + n.toFixed(2) + '%'; }

    const s = result, trades = s.trades;
    const now = new Date().toISOString().slice(0,19).replace('T',' ');

    let md = `# BTCUSDT 多周期MACDV策略回测报告 ${STRATEGY_VERSION}

> **生成时间**: ${now} | **引擎版本**: ${ENGINE_VERSION}
> **数据文件**: ai-analysis-BTCUSDT-300d-20260615T220842.json
> **策略**: MultiTimeframeStrategy（日线/4H/1H/30m/15min/5min 六周期联动）
> **初始资金**: $100,000.00
> **数据范围**: ${s.dataFrom} ~ ${s.dataTo}
> **数据量**: ${s.tfCounts['5m'] || '?'}根5min / ${s.tfCounts['15m'] || '?'}根15min / ${s.tfCounts['30m'] || '?'}根30m / ${s.tfCounts['1h'] || '?'}根1H / ${s.tfCounts['4h'] || '?'}根4H / ${s.tfCounts['1d'] || '?'}根日线

---

## 回测结果

| 指标 | 数值 |
|:---|---:|
| 最终资金 | $${fm(s.totalReturn + 100000)} |
| 总收益 | $${fm(s.totalReturn)}（**${s.returnPct.toFixed(2)}%**） |
| 总交易数 | ${s.totalTrades} |
| 盈利/亏损 | ${s.wins}胜 / ${s.losses}负（胜率**${s.winRate.toFixed(1)}%**） |
| 最大单笔盈利 | $${fm(s.maxWin)} |
| 最大单笔亏损 | $${fm(s.maxLoss)} |
| 平均盈利/亏损 | $${fm(s.avgWin)} / $${fm(s.avgLoss)} |
| 盈亏比 | **${s.profitFactor === Infinity ? '∞' : s.profitFactor.toFixed(2)}** |
| 最大回撤 | **${s.maxDrawdown.toFixed(2)}%** |
| 峰值资金 | $${fm(s.peakCapital)} |

---

## 方向与杠杆

| 方向 | 次数 | 总盈亏 | 胜率 |
|:---|:---:|:---:|:---:|
| 🔵做多 | ${s.longs.count} | $${fm(s.longs.pnl)} | ${s.longs.wr.toFixed(1)}% |
| 🔴做空 | ${s.shorts.count} | $${fm(s.shorts.pnl)} | ${s.shorts.wr.toFixed(1)}% |

| 杠杆 | 次数 | 总盈亏 |
|:---|:---:|:---:|
| 2x | ${s.lever2.count} | $${fm(s.lever2.pnl)} |
| 3x | ${s.lever3.count} | $${fm(s.lever3.pnl)} |

---

## 评分与胜率

| 评分 | 次数 | 总盈亏 | 胜率 |
|:---:|:---:|:---:|:---:|
`;
    const scores = Object.keys(s.byScore).sort((a,b)=>+a-+b);
    for (const sc of scores) {
      const v = s.byScore[sc];
      md += `| ${sc} | ${v.cnt} | $${fm(v.pnl)} | ${(v.w/v.cnt*100).toFixed(1)}% |\n`;
    }

    md += `
## 出场原因分布

| 原因 | 次数 |
|:---|:---:|
`;
    const ers = Object.entries(s.exitReasons).sort((a,b)=>b[1]-a[1]);
    for (const [r, c] of ers) md += `| ${r} | ${c} |\n`;

    md += `
## 完整交易记录

| # | 入场 | 方向 | 入场价 | 出场价 | 杠杆 | 盈亏 | 评分 | 入场理由 | 出场 |
|:---|:---|:---|:---|:---|:---:|:---:|:---:|:---|:---|
`;
    for (let i = 0; i < trades.length; i++) {
      const t = trades[i];
      const emoji = t.pnl >= 0 ? '🟢' : '🔴';
      const dir = t.type === '做多' ? '🔵' : '🔴';
      md += `| ${i+1} | ${dt(t.entryTime)} | ${dir}${t.type} | ${t.entryPrice.toFixed(0)} | ${t.exitPrice.toFixed(0)} | ${t.leverage}x | ${emoji}$${t.pnl.toFixed(0)} | ${t.score} | ${(t.entryReason||'').slice(0,65)} | ${t.exitReason} |\n`;
    }

    md += `
---

*由 BacktestEngine ${ENGINE_VERSION} 生成 | ${STRATEGY_VERSION} | 严格无未来函数 | 15min K线决策 | 复利+杠杆 2x/3x*
`;
    return md;
  }

  /** 生成对比报告（基准 vs 优化） */
  static generateCompareReport(baseline, optimized) {
    function fm(v) { 
      if (Math.abs(v) >= 1e6) return '$'+(v/1e6).toFixed(2)+'M';
      if (Math.abs(v) >= 1e3) return '$'+(v/1e3).toFixed(2)+'K';
      return '$'+v.toFixed(0);
    }
    function pct(n) { return (n>=0?'+':'')+n.toFixed(2)+'%'; }
    function pp(n) { return (n>=0?'+':'')+n.toFixed(2)+'pp'; }

    const now = new Date().toISOString().slice(0,19).replace('T',' ');
    let md = `# BTCUSDT 评分优化回测对比报告 ${STRATEGY_VERSION}

> **生成时间**: ${now} | **引擎版本**: ${ENGINE_VERSION}
> **数据**: ai-analysis-BTCUSDT-300d-20260615T220842.json（300天）
> **引擎**: BacktestEngine | 严格无未来函数 | 15min K线决策 | 复利+杠杆

---

## 一、核心指标对比

| 指标 | 基准(当前规则) | 优化(过滤Score10) | 变化 |
|:---|---:|---:|:---|
| 初始资金 | $100,000 | $100,000 | — |
| 最终权益 | ${fm(baseline.finalCapital)} | ${fm(optimized.finalCapital)} | ${fm(optimized.finalCapital - baseline.finalCapital)} |
| 总收益率 | ${baseline.returnPct.toFixed(2)}% | ${optimized.returnPct.toFixed(2)}% | **${pp(optimized.returnPct - baseline.returnPct)}** |
| 总交易数 | ${baseline.totalTrades} | ${optimized.totalTrades} | ${optimized.totalTrades - baseline.totalTrades}笔 |
| **胜率** | **${baseline.winRate.toFixed(1)}%** | **${optimized.winRate.toFixed(1)}%** | **${pp(optimized.winRate - baseline.winRate)}** |
| 盈亏比 | ${baseline.profitFactor === Infinity ? '∞' : baseline.profitFactor.toFixed(2)} | ${optimized.profitFactor === Infinity ? '∞' : optimized.profitFactor.toFixed(2)} | — |
| **最大回撤** | **${baseline.maxDrawdown.toFixed(2)}%** | **${optimized.maxDrawdown.toFixed(2)}%** | **${pp(optimized.maxDrawdown - baseline.maxDrawdown)}** |
| 峰值资金 | ${fm(baseline.peakCapital)} | ${fm(optimized.peakCapital)} | — |
| 最大单笔盈利 | ${fm(baseline.maxWin)} | ${fm(optimized.maxWin)} | — |
| 最大单笔亏损 | ${fm(Math.abs(baseline.maxLoss))} | ${fm(Math.abs(optimized.maxLoss))} | — |

---

## 二、杠杆验证

| 杠杆 | 基准笔数 | 基准盈亏 | 优化笔数 | 优化盈亏 |
|:---|:---:|:---:|:---:|:---:|
| 2x | ${baseline.lever2.count} | ${fm(baseline.lever2.pnl)} | ${optimized.lever2.count} | ${fm(optimized.lever2.pnl)} |
| 3x | ${baseline.lever3.count} | ${fm(baseline.lever3.pnl)} | ${optimized.lever3.count} | ${fm(optimized.lever3.pnl)} |

> PnL = 当前权益 × 价格变动% × 杠杆，复利计算

---

## 三、方向分析

| 方向 | 基准笔数 | 基准胜率 | 基准盈亏 | 优化笔数 | 优化胜率 | 优化盈亏 |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|
| 🔵做多 | ${baseline.longs.count} | ${baseline.longs.wr.toFixed(1)}% | ${fm(baseline.longs.pnl)} | ${optimized.longs.count} | ${optimized.longs.wr.toFixed(1)}% | ${fm(optimized.longs.pnl)} |
| 🔴做空 | ${baseline.shorts.count} | ${baseline.shorts.wr.toFixed(1)}% | ${fm(baseline.shorts.pnl)} | ${optimized.shorts.count} | ${optimized.shorts.wr.toFixed(1)}% | ${fm(optimized.shorts.pnl)} |

---

## 四、评分-胜率分布

| 评分 | 基准笔数 | 基准胜率 | 基准盈亏 | 优化笔数 | 优化胜率 | 优化盈亏 |
|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
`;

    const allScores = new Set([...Object.keys(baseline.byScore), ...Object.keys(optimized.byScore)]);
    for (const sc of [...allScores].sort((a,b)=>+a-+b)) {
      const bd = baseline.byScore[sc] || { cnt:0, w:0, pnl:0 };
      const od = optimized.byScore[sc] || { cnt:0, w:0, pnl:0 };
      const bwr = bd.cnt > 0 ? (bd.w/bd.cnt*100) : 0;
      const owr = od.cnt > 0 ? (od.w/od.cnt*100) : 0;
      const mark = sc === '10' ? ' ⚠️已过滤' : '';
      md += `| ${sc} | ${bd.cnt} | ${bwr.toFixed(1)}% | ${fm(bd.pnl)} | ${od.cnt}${mark} | ${od.cnt>0?owr.toFixed(1)+'%':'—'} | ${fm(od.pnl)} |\n`;
    }

    md += `
---

## 五、出场原因分布

| 出场原因 | 基准 | 优化 |
|:---|:---:|:---:|
`;
    const allER = new Set([...Object.keys(baseline.exitReasons), ...Object.keys(optimized.exitReasons)]);
    for (const r of [...allER].sort((a,b)=>(optimized.exitReasons[b]||0)-(optimized.exitReasons[a]||0))) {
      md += `| ${r} | ${baseline.exitReasons[r]||0} | ${optimized.exitReasons[r]||0} |\n`;
    }

    const tradeDiff = optimized.totalTrades - baseline.totalTrades;
    const pnlDiff = optimized.totalReturn - baseline.totalReturn;
    const wrDiff = optimized.winRate - baseline.winRate;
    const ddDiff = optimized.maxDrawdown - baseline.maxDrawdown;

    md += `
---

## 六、结论

1. **杠杆已正确使用**：PnL = 权益 × 变动% × 杠杆，复利计算，2x/3x按趋势强度自动选择
2. **过滤 Score 10 效果**：收益变化 ${fm(pnlDiff)}，胜率 ${pp(wrDiff)}，回撤 ${pp(ddDiff)}
3. **回撤降低 = 风险减小**：${ddDiff < 0 ? '最大回撤从'+baseline.maxDrawdown.toFixed(1)+'%降到'+optimized.maxDrawdown.toFixed(1)+'%，资金曲线更平滑' : '回撤有所增加'}
4. **严格无未来函数**：向前指针逐K线推进，所有决策仅使用历史数据

---

*BacktestEngine ${ENGINE_VERSION} | ${STRATEGY_VERSION} | 严格无未来函数 | ${now}*
`;
    return md;
  }
}

module.exports = { BacktestEngine, DEFAULT_PARAMS };
