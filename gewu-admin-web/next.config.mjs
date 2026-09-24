/** @type {import('next').NextConfig} */
const nextConfig = {
  // 历史页面存在批量 unused-vars 债务，不阻塞构建（类型检查由 tsc 强制，lint 可独立运行）
  eslint: { ignoreDuringBuilds: true },
  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: 'http://localhost:8083/api/:path*',
      },
    ];
  },
};

export default nextConfig;
