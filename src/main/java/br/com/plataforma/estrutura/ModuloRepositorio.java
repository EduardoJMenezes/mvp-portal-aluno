package br.com.plataforma.estrutura;

import br.com.plataforma.catalogo.Turma;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Sem {@code public}: nenhuma borda alcança o banco sem passar pelo {@link EstruturaServico}. */
interface ModuloRepositorio extends JpaRepository<Modulo, Integer> {

    /** Os módulos que a turma recebe, e os que têm aula só dela (decisão 0011). */
    @Query("""
            select m from Modulo m
             where :turma member of m.turmas
                or exists (select 1 from Item i where i.submodulo.modulo = m and :turma member of i.turmas)
             order by m.ordem, m.id""")
    List<Modulo> daTurma(Turma turma);

    List<Modulo> findAllByOrderByOrdemAscIdAsc();

    @Query("select coalesce(max(m.ordem), 0) from Modulo m")
    int maiorOrdem();

    /** O aluno alcança o módulo que a turma dele recebe, ou que tem aula só da turma dele. */
    @Query("""
            select count(m) > 0 from Modulo m
             where m.id = :modulo
               and (exists (select 1 from Matricula x where x.usuarioId = :usuario and x.turma member of m.turmas)
                    or exists (select 1 from Item i, Matricula x
                                where i.submodulo.modulo = m and x.usuarioId = :usuario
                                  and x.turma member of i.turmas))""")
    boolean alcancadoPor(Integer modulo, Integer usuario);

    // A foto entra e sai por consulta nativa: a entidade não a conhece.
    @Modifying
    @Query(value = "UPDATE modules SET foto = :conteudo WHERE id = :modulo", nativeQuery = true)
    void gravarFoto(Integer modulo, byte[] conteudo);

    @Modifying
    @Query(value = "UPDATE modules SET foto = NULL WHERE id = :modulo", nativeQuery = true)
    void apagarFoto(Integer modulo);

    @Query(value = "SELECT foto FROM modules WHERE id = :modulo AND removido_em IS NULL", nativeQuery = true)
    byte[] lerFoto(Integer modulo);
}
