# ==============================================================================
# 第一阶段：构建环境 (Builder Stage)
# 作用：使用 Node.js 环境来安装依赖并编译前端代码
# ==============================================================================
FROM node:20-alpine AS builder
WORKDIR /app

# 1. 优先复制依赖定义文件
# 技巧：利用 Docker 缓存层机制。只要 package.json 不变，这一层就会被缓存，
# 避免每次构建都重新下载 npm 包，显著加快构建速度。
COPY package.json pnpm-lock.yaml ./

# 2. 安装依赖
# npm install -g pnpm: 全局安装 pnpm 包管理器
# pnpm install: 安装所有依赖（包括 devDependencies，构建需要 TypeScript、Vite 等）
# 注意：不使用 --prod 标志，因为构建阶段需要完整的开发环境
RUN npm install -g pnpm && pnpm install

# 3. 复制源代码并构建
COPY . .
# 执行构建命令 (vite build)，生成的静态文件会存放在 /app/dist 目录
RUN pnpm build

# ==============================================================================
# 第二阶段：生产环境 (Production Stage)
# 作用：使用 Nginx 来托管构建好的静态文件 (HTML/CSS/JS)
# 区别：后端运行需要 JRE，前端运行只需要一个 Web 服务器（如 Nginx）
# ==============================================================================
FROM nginx:alpine

# 从第一阶段 (builder) 复制构建产物 (dist 目录) 到 Nginx 的默认服务目录
COPY --from=builder /app/dist /usr/share/nginx/html

# 复制自定义的 Nginx 配置文件
# 用于处理路由转发（解决 React/Vue 单页应用刷新 404 问题）和 API 反向代理
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 暴露 80 端口
EXPOSE 80

# 启动 Nginx，并保持在前台运行 (daemon off)
# 容器的主进程必须前台运行，否则容器一启动就会退出
CMD ["nginx", "-g", "daemon off;"]
