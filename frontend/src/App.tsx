import { useEffect, useState } from "react";
import { NavLink, Navigate, Route, Routes, useNavigate } from "react-router-dom";
import { api, type Usuario } from "./api";
import Login from "./paginas/Login";
import Enviar from "./paginas/Enviar";
import AdminTurmas from "./paginas/AdminTurmas";
import AdminRascunhos from "./paginas/AdminRascunhos";
import AdminSimulados from "./paginas/AdminSimulados";
import AlunoConteudo from "./paginas/AlunoConteudo";
import AlunoSimulados from "./paginas/AlunoSimulados";
import AlunoProva from "./paginas/AlunoProva";

export default function App() {
  const [usuario, setUsuario] = useState<Usuario | null>(null);
  const [carregando, setCarregando] = useState(true);
  const navegar = useNavigate();
  // O link de envio (.docx ou prints) vem do chat e vale sem login: nem confere sessão,
  // senão um login vencido no navegador tiraria a pessoa da página de envio.
  const envio = /^\/enviar\/([^/]+)/.exec(window.location.pathname);

  useEffect(() => {
    if (envio || !localStorage.getItem("token")) return setCarregando(false);
    api.eu().then(setUsuario).catch(() => localStorage.clear()).finally(() => setCarregando(false));
  }, []);

  if (envio) return <Enviar token={envio[1]} />;
  if (carregando) return <main><p className="legenda">carregando…</p></main>;
  if (!usuario) return <Login aoEntrar={setUsuario} />;

  const operador = usuario.papel === "ADMIN" || usuario.papel === "GERENCIADOR";

  function sair() {
    localStorage.clear();
    setUsuario(null);
    navegar("/");
  }

  return (
    <>
      <header className="topo">
        <h1>Plataforma Educacional</h1>
        <nav>
          {operador ? (
            <>
              <NavLink to="/turmas" className={({ isActive }) => (isActive ? "ativo" : "")}>Turmas</NavLink>
              <NavLink to="/rascunhos" className={({ isActive }) => (isActive ? "ativo" : "")}>Rascunhos</NavLink>
              <NavLink to="/simulados" className={({ isActive }) => (isActive ? "ativo" : "")}>Simulados</NavLink>
            </>
          ) : (
            <>
              <NavLink to="/conteudo" className={({ isActive }) => (isActive ? "ativo" : "")}>Conteúdo</NavLink>
              <NavLink to="/simulados" className={({ isActive }) => (isActive ? "ativo" : "")}>Simulados</NavLink>
            </>
          )}
          <span className="quem">
            {usuario.nome} · {usuario.papel}
            {usuario.turmas.length > 0 && ` · ${usuario.turmas.join(", ")}`}
          </span>
          <button onClick={sair}>Sair</button>
        </nav>
      </header>
      <main>
        <Routes>
          {operador ? (
            <>
              <Route path="/turmas" element={<AdminTurmas />} />
              <Route path="/rascunhos" element={<AdminRascunhos />} />
              <Route path="/simulados" element={<AdminSimulados />} />
              <Route path="*" element={<Navigate to="/turmas" replace />} />
            </>
          ) : (
            <>
              <Route path="/conteudo" element={<AlunoConteudo />} />
              <Route path="/simulados" element={<AlunoSimulados />} />
              <Route path="/simulados/:id" element={<AlunoProva />} />
              <Route path="*" element={<Navigate to="/conteudo" replace />} />
            </>
          )}
        </Routes>
      </main>
    </>
  );
}
