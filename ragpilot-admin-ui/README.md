# ragpilot-admin-ui

RagPilot **可运维管理端**（Vue 3 + TypeScript + Vite + Element Plus）。

规范见仓库：

- [`docs/admin-console-spec.md`](../docs/admin-console-spec.md)
- [`docs/admin-console-plan.md`](../docs/admin-console-plan.md)

## 环境

- Node.js 18+（推荐 20 LTS）
- 包管理：**npm**（提交 `package-lock.json`）

## 开发

```bash
cd ragpilot-admin-ui
npm install
npm run dev
```

浏览器打开终端提示的地址（默认 `http://127.0.0.1:5173/admin/`）。

## 脚本

| 命令 | 说明 |
|---|---|
| `npm run dev` | 本地开发 |
| `npm run build` | 类型检查 + 构建到 `dist/` |
| `npm run preview` | 预览构建产物 |
| `npm run lint` | ESLint |

## 进度

- [x] ADM-0.1 工程骨架 + 中文布局壳子  
- [ ] ADM-0.2 完整侧栏 IA  
- [ ] ADM-0.3 API client / 环境变量  

生产部署：`vite` 的 `base` 为 `/admin/`，由 bootstrap 托管 `dist`（ADM-0.4）。
