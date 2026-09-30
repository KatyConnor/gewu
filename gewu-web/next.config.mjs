/** @type {import('next').NextConfig} */
const nextConfig = {
  // 历史页面存在批量 unused-vars 债务，不阻塞构建（类型检查由 tsc 强制，lint 可独立运行）
  eslint: { ignoreDuringBuilds: true },
  // 共享包 @gewu/agent-ui 以源码分发（workspace 依赖），需 Next 转译（docs/design/42 ADR-003）
  transpilePackages: ['@gewu/agent-ui'],
  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: 'http://localhost:8081/api/:path*',
      },
    ];
  },
};

export default nextConfig;
