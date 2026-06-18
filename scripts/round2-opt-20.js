#!/usr/bin/env node
const path = require('path');
const fs = require('fs');
const { BacktestEngine, DEFAULT_PARAMS } = require('./backtest-engine');

const JSON_PATH = path.join(__dirname, '..', 'data', 'ai-analysis-BTCUSDT-300d-20260615T220842.json');
const OUT = path.join(__dirname, '..', 'data', 'round2-opt-20.md');

// ===== 第二轮：基于#8(MAX_BARS=72+SKIP10)深度探索 =====
const configs = [
  // ── 阶段一：出场微调 ──
  { id:1,  name:'基准(#8复现)', desc:'Round1最优：MAX_BARS=72 + SKIP_SCORE_10',
    params:{SKIP_SCORE_10:true, MAX_BARS:72} },
  { id:2,  name:'MAX=60(15h)', desc:'更激进时间止损，15h强制平仓',
    params:{SKIP_SCORE_10:true, MAX_BARS:60} },
  { id:3,  name:'72+紧回撤', desc:'MAX72 + 4H回撤0.10 + 1H回撤0.12（Round1#7+#8合并）',
    params:{SKIP_SCORE_10:true, MAX_BARS:72, H4_PEAK_RETRACE:0.10, H1_PEAK_RETRACE:0.12} },
  { id:4,  name:'72+中回撤', desc:'MAX72 + 4H回撤0.12 + 1H回撤0.15',
    params:{SKIP_SCORE_10:true, MAX_BARS:72, H4_PEAK_RETRACE:0.12, H1_PEAK_RETRACE:0.15} },
  { id:5,  name:'84+中回撤', desc:'MAX84(稍松) + 4H回撤0.12 + 1H回撤0.15',
    params:{SKIP_SCORE_10:true, MAX_BARS:84, H4_PEAK_RETRACE:0.12, H1_PEAK_RETRACE:0.15} },
  { id:6,  name:'72+TP65', desc:'MAX72 + TP从80降到65（Round1#4启发，TP60回撤仅11%）',
    params:{SKIP_SCORE_10:true, MAX_BARS:72, M15_TAKE_PROFIT:65} },

  // ── 阶段二：入场+杠杆精调 ──
  { id:7,  name:'72+H4多放宽', desc:'MAX72 + H4强多50→30（Round1#15启发）',
    params:{SKIP_SCORE_10:true, MAX_BARS:72, H4_STRONG_BULL:30} },
  { id:8,  name:'72+H4多40', desc:'MAX72 + H4强多50→40（中间值）',
    params:{SKIP_SCORE_10:true, MAX_BARS:72, H4_STRONG_BULL:40} },
  { id:9,  name:'72+H4空放宽', desc:'MAX72 + H4强空-60→-50（做空门槛放低）',
    params:{SKIP_SCORE_10:true, MAX_BARS:72, H4_STRONG_BEAR:-50} },
  { id:10, name:'72+日线放宽', desc:'MAX72 + 日线熊-120→-140（仅极端熊禁多）',
    params:{SKIP_SCORE_10:true, MAX_BARS:72, DAILY_BEARISH:-140} },
  { id:11, name:'72+3x多放', desc:'MAX72 + 3x做多条件放宽：H4>40且1H>-20',
    params:{SKIP_SCORE_10:true, MAX_BARS:72, H4_STRONG_BULL:40, H4_STRONG_BEAR:-50} },
  { id:12, name:'72+关1H空过滤', desc:'MAX72 + 关闭1H做空过滤器（允许1H超卖时做空）',
    params:{SKIP_SCORE_10:true, MAX_BARS:72, USE_1H_SHORT_FILTER:false} },

  // ── 阶段三：组合精调 ──
  { id:13, name:'72+温和A', desc:'TP65 + 回撤0.12 + H4多40 + BEARISH-10',
    params:{SKIP_SCORE_10:true, MAX_BARS:72, M15_TAKE_PROFIT:65,
      H4_PEAK_RETRACE:0.12, H1_PEAK_RETRACE:0.15, H4_STRONG_BULL:40, H4_BEARISH_ZONE:-10} },
  { id:14, name:'72+温和B', desc:'TP70 + 回撤0.14 + H4多35 + H4空-55',
    params:{SKIP_SCORE_10:true, MAX_BARS:72, M15_TAKE_PROFIT:70,
      H4_PEAK_RETRACE:0.14, H1_PEAK_RETRACE:0.18, H4_STRONG_BULL:35, H4_STRONG_BEAR:-55} },
  { id:15, name:'72+深回调TP65', desc:'MAX72 + 买点-120 + TP65（深买快卖）',
    params:{SKIP_SCORE_10:true, MAX_BARS:72, M15_DEEP_PULLBACK:-120,
      M15_DEEP_PULLBACK_TOL:20, M15_TAKE_PROFIT:65} },
  { id:16, name:'72+3x激进', desc:'MAX72 + 更多3x：H4多30 空-50 + Align3',
    params:{SKIP_SCORE_10:true, MAX_BARS:72, H4_STRONG_BULL:30, H4_STRONG_BEAR:-50,
      TREND_ALIGN:3, TREND_PARTIAL:2} },

  // ── 阶段四：边界测试 ──
  { id:17, name:'72+做空优先', desc:'MAX72 + 做空放宽空-50 + 做多收紧多70',
    params:{SKIP_SCORE_10:true, MAX_BARS:72, H4_STRONG_BEAR:-50, H4_STRONG_BULL:70} },
  { id:18, name:'72+做多优先', desc:'MAX72 + 做多放宽多30 + 做空收紧空-80',
    params:{SKIP_SCORE_10:true, MAX_BARS:72, H4_STRONG_BULL:30, H4_STRONG_BEAR:-80} },
  { id:19, name:'MAX=48(12h)', desc:'极端时间止损：12h平仓',
    params:{SKIP_SCORE_10:true, MAX_BARS:48} },
  { id:20, name:'72+Align3', desc:'MAX72 + 趋势一致性加强 Align3+Partial2',
    params:{SKIP_SCORE_10:true, MAX_BARS:72, TREND_ALIGN:3, TREND_PARTIAL:2} },
];

const fm = v => Math.abs(v)>=1e6 ? '$'+(v/1e6).toFixed(2)+'M' : Math.abs(v)>=1000 ? '$'+(v/1000).toFixed(1)+'K' : '$'+v.toFixed(0);

console.log('='.repeat(70));
console.log('Round 2 | 深度探索 #8(MAX_BARS=72) | 20组实验');
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
  console.log(`  → ${r.totalTrades}笔 胜率${r.winRate.toFixed(1)}% 收益${fm(r.totalReturn)} 回撤${r.maxDrawdown.toFixed(1)}%`);
}

results.sort((a,b)=>b.totalReturn-a.totalReturn);

let md = `# Round 2 | 基于#8(MAX_BARS=72)深度参数优化

> **引擎**: BacktestEngine V9 | **数据**: 300天 BTCUSDT | **基线**: SKIP_SCORE_10 + MAX_BARS=72
> **生成**: ${new Date().toISOString().replace('T',' ').substring(0,19)}

---

## Round 1 回顾：为什么选 #8

| 指标 | 原版 | #8(MAX72) | 改善 |
|:---|---:|---:|:---|
| 收益 | $935.8K | $1.07M | **+14%** |
| 胜率 | 62.8% | 63.7% | +0.9pp |
| 回撤 | 16.0% | 12.4% | **-22%** |
| 交易 | 269 | 270 | +1 |

---

## 一、Round 2 排名（按总收益降序）

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

// 对比基准
const best=results[0], base=results.find(r=>r.id===1);
if(base && best && best.id!==1) {
  md += `## 三、最优 vs #8基线\n\n| 指标 | #8基线 | 最优(#${best.id}) | 变化 |\n|:---|---:|---:|:---|\n`;
  md += `| 收益 | ${fm(base.totalReturn)} | ${fm(best.totalReturn)} | ${fm(best.totalReturn-base.totalReturn)} |\n`;
  md += `| 胜率 | ${base.winRate.toFixed(1)}% | ${best.winRate.toFixed(1)}% | ${(best.winRate-base.winRate>=0?'+':'')+(best.winRate-base.winRate).toFixed(1)}pp |\n`;
  md += `| 回撤 | ${base.maxDrawdown.toFixed(1)}% | ${best.maxDrawdown.toFixed(1)}% | ${(best.maxDrawdown-base.maxDrawdown>=0?'+':'')+(best.maxDrawdown-base.maxDrawdown).toFixed(1)}pp |\n`;
  md += `| 交易 | ${base.trades} | ${best.trades} | ${best.trades-base.trades>0?'+':''}${best.trades-base.trades} |\n`;
}

// 杠杆深度分析
md += `\n## 四、3x杠杆深度分析\n\n| 配置 | 3x笔数 | 3x收益 | 笔均 | 3x胜率 |\n|:---|---:|---:|---:|---:|\n`;
for (const r of [...results].sort((a,b)=>b.lev3Pnl/Math.max(1,b.lev3Cnt)-a.lev3Pnl/Math.max(1,a.lev3Cnt)).slice(0,5)) {
  md += `| ${r.name} | ${r.lev3Cnt} | ${fm(r.lev3Pnl)} | ${fm(r.lev3Cnt>0?r.lev3Pnl/r.lev3Cnt:0)} | — |\n`;
}

md += `\n## 五、Round 2 结论\n\n`;
md += `- **#8(MAX72+SKIP10) 基线表现**: ${fm(base.totalReturn)} 收益, ${base.maxDrawdown.toFixed(1)}% 回撤, ${base.winRate.toFixed(1)}% 胜率\n`;
md += `- **Round2 最优**: #${best.id} ${best.name} — ${fm(best.totalReturn)} (${best.totalReturn>base.totalReturn?'+':''}${fm(best.totalReturn-base.totalReturn)})\n`;

md += `\n---\n*BacktestEngine V9 | 严格无未来函数 | ${new Date().toISOString().substring(0,10)}*\n`;

fs.writeFileSync(OUT, md, 'utf-8');
console.log(`\n✅ 报告: ${OUT}`);
console.log(`🏆 最优: #${best.id} ${best.name} — ${fm(best.totalReturn)}`);
