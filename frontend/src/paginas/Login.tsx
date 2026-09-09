import { useState } from "react";
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

  async function enviar(e: React.FormEvent) {
    e.preventDefault();
    setErro("");
    setOcupado(true);
    try {
      const r = await api.login(email, senha);
      localStorage.setItem("token", r.token);
      aoEntrar(r.usuario);
    } catch (ex) {
      setErro((ex as Error).message);
    } finally {
      setOcupado(false);
    }
  }

  return (
    <div className="entrar">
      <h2>Entrar</h2>
      <p className="legenda">Plataforma Educacional — POC MCP</p>
      <form className="cartao" onSubmit={enviar}>
        {erro && <div className="erro">{erro}</div>}
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
