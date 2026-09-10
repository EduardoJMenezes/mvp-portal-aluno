import { useEffect, useState } from "react";
import { api, type Usuario } from "../api";

const DEMO = [
  ["professor@escola.demo", "Prof. Helena — ADMIN"],
  ["joao@aluno.demo", "João — Extensivo 2027"],
  ["pedro@aluno.demo", "Pedro — Extensivo 2026"],
];

export default function Login({ aoEntrar }: { aoEntrar: (u: Usuario) => void }) {
  const [email, setEmail] = useState("professor@escola.demo");
  const [senha, setSenha] = useState("demo1234");
  const [erro, setErro] = useState("");
  const [ocupado, setOcupado] = useState(false);

  useEffect(() => {
    if (!erro) return;
    const timeout = window.setTimeout(() => setErro(""), 6000);
    return () => window.clearTimeout(timeout);
  }, [erro]);

  async function enviar(e: React.FormEvent) {
    e.preventDefault();
    setErro("");
    setOcupado(true);
    try {
      const r = await api.login(email, senha);
      localStorage.setItem("token", r.token);
      aoEntrar(r.usuario);
    } catch (ex) {
      setErro((ex instanceof Error ? ex.message.trim() : "") || "Não foi possível entrar. Tente novamente.");
    } finally {
      setOcupado(false);
    }
  }

  return (
    <div className="entrar">
      {erro && (
        <div className="toast-login-erro" role="alert" aria-live="assertive" aria-atomic="true">
          <div className="toast-login-conteudo">
            <strong>Erro ao entrar</strong>
            <p>{erro}</p>
          </div>
          <button type="button" className="toast-login-fechar" aria-label="Fechar erro de login"
                  onClick={() => setErro("")}>
            <span aria-hidden="true">×</span>
          </button>
        </div>
      )}
      <h2>Entrar</h2>
      <p className="legenda">Plataforma Educacional — POC MCP</p>
      <form className="cartao" onSubmit={enviar}>
        <label>
          E-mail
          <input value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" />
        </label>
        <label>
          Senha
          <input type="password" value={senha} onChange={(e) => setSenha(e.target.value)}
                 autoComplete="current-password" />
        </label>
        <button className="primario" disabled={ocupado} style={{ width: "100%" }}>
          {ocupado ? "entrando…" : "Entrar"}
        </button>
      </form>
      <p className="legenda">Contas da demonstração (senha <span className="mono">demo1234</span>):</p>
      {DEMO.map(([e, rotulo]) => (
        <div key={e} className="linha" style={{ marginBottom: 6 }}>
          <button onClick={() => { setEmail(e); setSenha("demo1234"); }}>{rotulo}</button>
        </div>
      ))}
    </div>
  );
}
