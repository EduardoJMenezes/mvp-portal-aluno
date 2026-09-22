package br.com.plataforma.questoes;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface QuestaoAssuntoRepositorio extends JpaRepository<QuestaoAssunto, Integer> {

    /**
     * Os vínculos cujo assunto ainda existe.
     *
     * <p>O {@code join fetch} no assunto faz duas coisas: aplica o {@code @SQLRestriction} dele
     * (vínculo órfão de assunto removido não vem) e carrega junto, para a navegação preguiçosa
     * não estourar depois. No sub-assunto é {@code left join}, que devolve nulo em vez de sumir
     * com a linha.
     */
    @Query("""
            select qa from QuestaoAssunto qa
              join fetch qa.assunto
              left join fetch qa.subassunto
             where qa.questao = :questao""")
    List<QuestaoAssunto> vivosDa(Questao questao);
}
