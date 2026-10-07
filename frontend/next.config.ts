import type { NextConfig } from "next";

const desenvolvimento = process.env.NODE_ENV !== "production";

// Em produção o portal é exportado como arquivos estáticos e a API serve tudo
// no mesmo host: mesma origem para o cookie httpOnly da sessão. Em
// desenvolvimento, `next dev` repassa /api para a API na porta 8080.
const config: NextConfig = {
  output: desenvolvimento ? undefined : "export",
  trailingSlash: !desenvolvimento,
  images: { unoptimized: true },
  turbopack: { root: import.meta.dirname },
  ...(desenvolvimento && {
    async rewrites() {
      return [{ source: "/api/:caminho*", destination: "http://127.0.0.1:8080/api/:caminho*" }];
    },
  }),
};

export default config;
