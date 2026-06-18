#!/usr/bin/env node
const path = require('path');
const fs = require('fs');
const { BacktestEngine, DEFAULT_PARAMS } = require('./backtest-engine');

const JSON_PATH = path.join(__dirname, '..', 'data', 'ai-analysis-BTCUSDT-300d-20260615T220842.json');
const OUT = path.join(__dirname, '..', 'data', 'param-opt-20.md');

// ===== 20组实验：数据驱动参数优化 =====
const configs = [
  // ── 阶段一：入场优化 ──
  { id:1,  name:'基准(原版)',  desc:'当前MultiTimeframeStrategy默认参数',
    params:{} },
  { id:2,  name:'仅过滤Score10', desc:'最简单有效的改动：跳过50%胜率的Score10信号',
    params:{SKIP_SCORE_10:true} },
  { id:3,  name:'深回调-120', desc:'数据：15m<-150反弹60.2% vs <-100仅54.9%，将买点从-100移至-120(P5级)',
    params:{SKIP_SCORE_10:true, M15_DEEP_PULLBACK:-120, M15_DEEP_PULLBACK_TOL:20} },
  { id:4,  name:'止盈TP60', desc:'数据：15m>80后53.8%反转下跌，TP从80降至60更早锁定利润',
    params:{SKIP_SCORE_10:true, M15_TAKE_PROFIT:60} },
  { id:5,  name:'15minTol收紧15', desc:'容差从±25缩到±15，仅进入MACDV最极端15%区域',
    params:{SKIP_SCORE_10:true, M15_DEEP_PULLBACK_TOL:15, M15_DEEP_RALLY_TOL:15, M15_AXIS_TOL:15} },
  { id:6,  name:'4H区间收紧', desc:'4H强多50→70,强空-60→-70,bearish-20→-10，严格趋势过滤',
    params:{SKIP_SCORE_10:true, H4_STRONG_BULL:70, H4_STRONG_BEAR:-70, H4_BEARISH_ZONE:-10} },

  // ── 阶段二：出场优化 ──
  { id:7,  name:'快速止损', desc:'数据：4H回落>15%仅47.8%反弹，回撤收紧：H4 0.10, H1 0.12',
    params:{SKIP_SCORE_10:true, H4_PEAK_RETRACE:0.10, H1_PEAK_RETRACE:0.12} },
  { id:8,  name:'时间止损72', desc:'MAX_BARS 96→72(18h)，减少长时间持仓被反转吞噬',
    params:{SKIP_SCORE_10:true, MAX_BARS:72} },
  { id:9,  name:'冷却延长5根', desc:'开仓冷却3→5根K线(75min)，避免频繁交易被反复打脸',
    params:{SKIP_SCORE_10:true, COOLDOWN_BARS:5} },
  { id:10, name:'出场组合A', desc:'TP60+紧回撤+短持仓+长冷却',
    params:{SKIP_SCORE_10:true, M15_TAKE_PROFIT:60, H4_PEAK_RETRACE:0.10, H1_PEAK_RETRACE:0.12, MAX_BARS:72, COOLDOWN_BARS:5} },
  { id:11, name:'出场组合B(宽)', desc:'TP90+松回撤+长持仓 — 测试"让利润奔跑"是否有用',
    params:{SKIP_SCORE_10:true, M15_TAKE_PROFIT:90, H4_PEAK_RETRACE:0.20, H1_PEAK_RETRACE:0.25, MAX_BARS:120} },
  { id:12, name:'做空收紧', desc:'空头入场H4强空-60→-80(仅P10级做空)，减少假空头信号',
    params:{SKIP_SCORE_10:true, H4_STRONG_BEAR:-80} },

  // ── 阶段三：评分/趋势优化 ──
  { id:13, name:'趋势一致性加强', desc:'TREND_ALIGN 2→3, PARTIAL 1→2，同向多周期信号权重更大',
    params:{SKIP_SCORE_10:true, TREND_ALIGN:3, TREND_PARTIAL:2} },
  { id:14, name:'日线加强', desc:'日线强空-120→-140(P5级)，仅极端熊市日才禁多。牛市日禁空不变',
    params:{SKIP_SCORE_10:true, DAILY_BEARISH:-140} },
  { id:15, name:'4H放宽做多', desc:'H4强多50→30(P65附近)，更多4H多头窗口做多',
    params:{SKIP_SCORE_10:true, H4_STRONG_BULL:30} },
  { id:16, name:'5min极值收紧', desc:'超买150→180(P98),超卖-150→-180(P98)，仅2%极端5min触发',
    params:{SKIP_SCORE_10:true, M5_OVERBOUGHT:180, M5_OVERSOLD:-180} },

  // ── 阶段四：综合策略 ──
  { id:17, name:'激进综合', desc:'深回调-120+TP60+紧回撤+Align3+Tol15+COOLDOWN5',
    params:{SKIP_SCORE_10:true, M15_DEEP_PULLBACK:-120, M15_DEEP_PULLBACK_TOL:15,
      M15_TAKE_PROFIT:60, H4_PEAK_RETRACE:0.10, H1_PEAK_RETRACE:0.12,
      MAX_BARS:72, COOLDOWN_BARS:5, TREND_ALIGN:3, TREND_PARTIAL:2,
      H4_STRONG_BULL:70, H4_STRONG_BEAR:-70, H4_BEARISH_ZONE:-10} },
  { id:18, name:'温和综合', desc:'均衡收紧：TP70+回撤0.12+Tol20+Align3+BARS84+冷却4',
    params:{SKIP_SCORE_10:true, M15_TAKE_PROFIT:70, M15_DEEP_PULLBACK_TOL:20,
      H4_PEAK_RETRACE:0.12, H1_PEAK_RETRACE:0.17, MAX_BARS:84,
      COOLDOWN_BARS:4, TREND_ALIGN:3, TREND_PARTIAL:2,
      H4_BEARISH_ZONE:-15, H4_STRONG_BEAR:-65} },
  { id:19, name:'做多优化', desc:'做多放宽(H4强多30,bearish-5)+做空收紧(H4强空-80)',
    params:{SKIP_SCORE_10:true, H4_STRONG_BULL:30, H4_BEARISH_ZONE:-5,
      H4_STRONG_BEAR:-80, TREND_ALIGN:3} },
  { id:20, name:'做空优化', desc:'做空放宽(H4强空-50)+做多收紧(H4强多70,bearish-20)',
    params:{SKIP_SCORE_10:true, H4_STRONG_BEAR:-50, H4_STRONG_BULL:70,
      H4_BEARISH_ZONE:-20, TREND_ALIGN:3} },
];

const fm = v => Math.abs(v)>=1e6 ? '$'+(v/1e6).toFixed(2)+'M' : Math.abs(v)>=1000 ? '$'+(v/1000).toFixed(1)+'K' : '$'+v.toFixed(0);
const pp = v => (v>=0?'+':'')+v.toFixed(2);

console.log('='.repeat(70));
console.log('数据驱动参数优化 | BacktestEngine V9 | 20组实验');
console.log('='.repeat(70));

const results = [];
for (const cfg of configs) {
  const merged = {...DEFAULT_PARAMS, ...cfg.params};
  console.log(`\n[${cfg.id}/20] ${cfg.name}...`);
  const engine = new BacktestEngine(merged);
  engine.loadData(JSON_PATH);
  const r = engine.run();
  
  const lev2 = r.lever2 || {}, lev3 = r.lever3 || {};
  const longs = r.longs || {}, shorts = r.shorts || {};
  
  results.push({
    ...cfg,
    trades: r.totalTrades, wins: r.wins, losses: r.losses,
    winRate: r.winRate, totalReturn: r.totalReturn, maxDrawdown: r.maxDrawdown,
    avgWin: r.avgWin, avgLoss: r.avgLoss,
    pf: r.avgLoss!==0 ? Math.abs(r.avgWin/r.avgLoss) : 0,
    longCnt: longs.count||0, longPnl: longs.pnl||0, longWR: longs.wr||0,
    shortCnt: shorts.count||0, shortPnl: shorts.pnl||0, shortWR: shorts.wr||0,
    lev2Cnt: lev2.count||0, lev2Pnl: lev2.pnl||0,
    lev3Cnt: lev3.count||0, lev3Pnl: lev3.pnl||0,
  });
  console.log(`  → ${r.totalTrades}笔 胜率${r.winRate.toFixed(1)}% 收益${fm(r.totalReturn)} 回撤${r.maxDrawdown.toFixed(1)}% 做多${fm(longs.pnl||0)} 做空${fm(shorts.pnl||0)}`);
}

// 按收益排序
results.sort((a,b)=>b.totalReturn-a.totalReturn);

// 生成排名报告
let md = `# BTCUSDT 多周期MACDV策略 — 数据驱动参数优化报告

> **引擎**: BacktestEngine V9 | **数据**: 300天 BTCUSDT | **严格无未来函数**
> **生成**: ${new Date().toISOString().replace('T',' ').substring(0,19)}

---

## 数据特征摘要

| 发现 | 数据 | 策略含义 |
|:---|:---|:---|
| 15m<-150反弹率 | **60.2%** 3h后上涨 | 深回调买点应更极端(>=-120) |
| 15m>150下跌率 | **61.3%** 3h后下跌 | 极端超买是可靠做空信号 |
| 15m>80反转率 | **53.8%** 反转下跌 | TP从80降到60-70更早锁定利润 |
| 4H-15m同向概率 | **仅54.4%** | 近半数时间大小周期背离 |
| 4H回落>15%反弹率 | **47.8%** | 回落信号偏弱，应更早止损 |
| Score10胜率 | **50.0%** | 高置信度=假信号，需过滤 |

---

## 一、性能排名（按总收益降序）

| 排名 | ID | 配置 | 交易 | 胜率 | 收益 | 回撤 | PF | 做多 | 做空 | 2x/3x |
|:---:|:---:|:---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
`;

for (let i=0;i<results.length;i++) {
  const r=results[i];
  const rank = i<3 ? ['🥇','🥈','🥉'][i] : i+1;
  md += `| ${rank} | ${r.id} | ${r.name} | ${r.trades} | ${r.winRate.toFixed(1)}% | ${fm(r.totalReturn)} | ${r.maxDrawdown.toFixed(1)}% | ${r.pf.toFixed(2)} | ${fm(r.longPnl)} | ${fm(r.shortPnl)} | ${r.lev2Cnt}/${r.lev3Cnt} |\n`;
}

md += `\n---\n\n## 二、详细结果\n\n`;
for (const r of results) {
  md += `### ${r.id}. ${r.name}\n> ${r.desc}\n\n`;
  md += `| 指标 | 数值 |\n|:---|---:|\n`;
  md += `| 交易 | ${r.trades}笔 (${r.wins}胜/${r.losses}负) |\n`;
  md += `| 胜率 | **${r.winRate.toFixed(1)}%** |\n`;
  md += `| 总收益 | **${fm(r.totalReturn)}** |\n`;
  md += `| 最大回撤 | ${r.maxDrawdown.toFixed(1)}% |\n`;
  md += `| 均盈/均亏 | ${fm(r.avgWin)} / ${fm(r.avgLoss)} |\n`;
  md += `| 盈亏比 | ${r.pf.toFixed(2)} |\n`;
  md += `| 🔵做多 | ${r.longCnt}笔 ${fm(r.longPnl)} (胜率${r.longWR.toFixed(1)}%) |\n`;
  md += `| 🔴做空 | ${r.shortCnt}笔 ${fm(r.shortPnl)} (胜率${r.shortWR.toFixed(1)}%) |\n`;
  md += `| 2x杠杆 | ${r.lev2Cnt}笔 ${fm(r.lev2Pnl)} |\n`;
  md += `| 3x杠杆 | ${r.lev3Cnt}笔 ${fm(r.lev3Pnl)} |\n`;
  md += `\n**参数:** \`${JSON.stringify(r.params)}\`\n\n---\n\n`;
}

// 基准对比
const best=results[0], base=results.find(r=>r.id===1);
if(base && best && best.id!==1) {
  md += `## 三、最优 vs 基准\n\n| 指标 | 基准 | 最优(#${best.id}) | 变化 |\n|:---|---:|---:|:---|\n`;
  md += `| 收益 | ${fm(base.totalReturn)} | ${fm(best.totalReturn)} | ${fm(best.totalReturn-base.totalReturn)} |\n`;
  md += `| 胜率 | ${base.winRate.toFixed(1)}% | ${best.winRate.toFixed(1)}% | ${pp(best.winRate-base.winRate)}pp |\n`;
  md += `| 回撤 | ${base.maxDrawdown.toFixed(1)}% | ${best.maxDrawdown.toFixed(1)}% | ${pp(best.maxDrawdown-base.maxDrawdown)}pp |\n`;
  md += `| 交易 | ${base.trades} | ${best.trades} | ${best.trades-base.trades>0?'+':''}${best.trades-base.trades} |\n`;
  md += `| 做多 | ${fm(base.longPnl)} | ${fm(best.longPnl)} | — |\n`;
  md += `| 做空 | ${fm(base.shortPnl)} | ${fm(best.shortPnl)} | — |\n`;
}

// 杠杆分析
md += `\n## 四、杠杆3x使用条件分析\n\n`;
md += `当前3x条件：\n`;
md += `- 做多3x: 4H MACDV > ${DEFAULT_PARAMS.H4_STRONG_BULL} **且** 1H MACDV > 0\n`;
md += `- 做空3x: 4H MACDV < ${DEFAULT_PARAMS.H4_STRONG_BEAR} **且** 1H MACDV < 0\n\n`;

// Find best 3x performer
const best3x = [...results].sort((a,b)=>{
  const a3xR = a.lev3Cnt>0 ? a.lev3Pnl/a.lev3Cnt : -Infinity;
  const b3xR = b.lev3Cnt>0 ? b.lev3Pnl/b.lev3Cnt : -Infinity;
  return b3xR - a3xR;
})[0];

// Find config with most 3x trades
const most3x = [...results].sort((a,b)=>b.lev3Cnt-a.lev3Cnt)[0];

md += `| 指标 | 最佳 | 数值 |
|:---|:---|:---|
| 最优配置 | #${best.id} ${best.name} | 3x=${best.lev3Cnt}笔 ${fm(best.lev3Pnl)} |
| 3x最多 | #${most3x.id} ${most3x.name} | 3x=${most3x.lev3Cnt}笔 ${fm(most3x.lev3Pnl)} |
| 3x笔均最高 | #${best3x.id} ${best3x.name} | 3x=${best3x.lev3Cnt}笔 笔均${fm(best3x.lev3Cnt>0?best3x.lev3Pnl/best3x.lev3Cnt:0)} |
\n`;

// 推荐
md += `## 五、推荐方案\n\n`;
if (best.id === 2) {
  md += `**最优方案极其简单：仅需 \`SKIP_SCORE_10: true\`，一行改动即可。**\n\n`;
  md += `不需要动任何阈值、容差、止损参数。原策略本身已经很好，Score 10（36笔50%胜率）是唯一的"毒药"信号。\n\n`;
} else {
  md += `**最优方案: #${best.id} ${best.name}**\n\n\`\`\`json\n${JSON.stringify(best.params,null,2)}\n\`\`\`\n`;
}

md += `### 杠杆建议\n\n`;
md += `基于回测数据，3x杠杆在以下条件更安全：\n`;
md += `1. **做多3x**: 4H>50 + 1H>0 + 15m在深回调区(-120附近) → 反弹率60%+\n`;
md += `2. **做空3x**: 4H<-60 + 1H<0 + 15m在超买区(>100) → 继续跌概率54%+\n`;
md += `3. **避免3x**: 4H与15m方向背离时 → 亏损概率显著升高\n`;

md += `\n---\n*BacktestEngine V9 | 严格无未来函数 | ${new Date().toISOString().substring(0,10)}*\n`;

fs.writeFileSync(OUT, md, 'utf-8');
console.log(`\n✅ 报告: ${OUT}`);
console.log(`🏆 最优: #${best.id} ${best.name} — ${fm(best.totalReturn)} 胜率${best.winRate.toFixed(1)}%`);
