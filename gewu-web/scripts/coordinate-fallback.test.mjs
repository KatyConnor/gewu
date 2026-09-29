// 编排画布坐标兜底回归脚本（docs/design/55 P0-A/P0-B 验证层）。
// gewu-web 未引入测试运行器：此脚本用项目自带 typescript 的编译 API 转译真实模块后断言，
// 零新增依赖。运行：cd gewu-web && node scripts/coordinate-fallback.test.mjs
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const here = dirname(fileURLToPath(import.meta.url));
const webRoot = dirname(here);
const require = createRequire(import.meta.url);
const ts = require('typescript');

// 转译真实源码为 CJS 并在隔离模块中加载（transpileModule 不做类型检查，类型由 tsc 门禁覆盖）
const source = readFileSync(join(webRoot, 'src', 'lib', 'orchestrationDesigner.ts'), 'utf8');
const js = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;
const mod = { exports: {} };
new Function('exports', 'require', 'module', '__filename', '__dirname', js)(
  mod.exports, require, mod, join(webRoot, 'orchestrationDesigner.js'), webRoot);
const { definitionToFlow, parseDefinition, gridPosition } = mod.exports;

let failed = 0;
const check = (name, cond) => {
  if (cond) console.log(`  ok  ${name}`);
  else { failed += 1; console.error(`FAIL  ${name}`); }
};
const flow = definition => definitionToFlow(parseDefinition(JSON.stringify(definition)));

// 1 正常坐标回归：唯一合法坐标原样保留
const normal = flow({ nodes: [{ nodeId: 'a', x: 26, y: 102 }, { nodeId: 'b', x: 300, y: 102 }], edges: [] });
check('正常坐标保留', normal.nodes[0].position.x === 26 && normal.nodes[0].position.y === 102
  && normal.nodes[1].position.x === 300 && normal.nodes[1].position.y === 102);

// 2 全 (0,0)：按索引铺网格，互不重叠
const zeros = flow({ nodes: [
  { nodeId: 'n1', x: 0, y: 0 }, { nodeId: 'n2', x: 0, y: 0 }, { nodeId: 'n3', x: 0, y: 0 }], edges: [] });
check('全0,0铺网格', zeros.nodes.every((n, i) =>
  n.position.x === gridPosition(i).x && n.position.y === gridPosition(i).y));
check('网格坐标互不重叠', new Set(zeros.nodes.map(n => `${n.position.x},${n.position.y}`)).size === zeros.nodes.length);

// 3 混合缺失：x 或 y 缺失均视为无坐标入网格，有坐标者保留
const mixed = flow({ nodes: [{ nodeId: 'a', x: 500, y: 90 }, { nodeId: 'b' }, { nodeId: 'c', y: 90 }], edges: [] });
check('有坐标者保留', mixed.nodes[0].position.x === 500 && mixed.nodes[0].position.y === 90);
check('全缺失入网格', mixed.nodes[1].position.x === gridPosition(1).x && mixed.nodes[1].position.y === gridPosition(1).y);
check('仅y缺失视为缺失', mixed.nodes[2].position.x === gridPosition(2).x && mixed.nodes[2].position.y === gridPosition(2).y);

// 4 碰撞检测：共用 (0,0) 的节点组重铺；孤立的 (0,0) 是真实坐标，保留
const collide = flow({ nodes: [
  { nodeId: 'a', x: 0, y: 0 }, { nodeId: 'b', x: 0, y: 0 }, { nodeId: 'c', x: 700, y: 300 }], edges: [] });
check('共用0,0重铺', collide.nodes[0].position.x === gridPosition(0).x && collide.nodes[0].position.y === gridPosition(0).y
  && collide.nodes[1].position.x === gridPosition(1).x && collide.nodes[1].position.y === gridPosition(1).y);
check('唯一节点保留原值', collide.nodes[2].position.x === 700 && collide.nodes[2].position.y === 300);
const single = flow({ nodes: [{ nodeId: 'a', x: 0, y: 0 }, { nodeId: 'b', x: 260, y: 0 }], edges: [] });
check('孤立真实0,0保留', single.nodes[0].position.x === 0 && single.nodes[0].position.y === 0);

// 5 parseDefinition 坐标类型收敛：字符串/null 收敛为缺失，合法数字不动，其余字段透传
const sanitized = parseDefinition(JSON.stringify({
  nodes: [{ nodeId: 'a', x: '0', y: null, roleCode: 'DEV' }, { nodeId: 'b', x: 12, y: 34 }], edges: [] }));
check('字符串坐标收敛为缺失', sanitized.nodes[0].x === undefined && sanitized.nodes[0].y === undefined);
check('其余字段透传', sanitized.nodes[0].roleCode === 'DEV');
check('合法坐标不受收敛影响', sanitized.nodes[1].x === 12 && sanitized.nodes[1].y === 34);
check('收敛后序列化不含非法坐标', !JSON.stringify(sanitized).includes('"x":"0"'));
const passthrough = parseDefinition(JSON.stringify({ nodes: [{ nodeId: 'a', config: { k: 'v' } }], edges: [] }));
check('config 等字段原样透传', passthrough.nodes[0].config.k === 'v' && passthrough.nodes[0].x === undefined);

if (failed > 0) {
  console.error(`\n${failed} 项断言失败`);
  process.exit(1);
}
console.log('\n全部断言通过');
