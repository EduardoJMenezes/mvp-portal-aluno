package br.com.plataforma.comandos;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.BaseDeComando;
import org.junit.jupiter.api.Test;

class CriarTurmaTest extends BaseDeComando {

    @Test
    void criaATurmaPeloChatENaoRepeteONome() throws Exception {
        comando("criar_turma", "{\"nome\": \"Extensivo 2027 Online\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Extensivo 2027 Online"))
                .andExpect(jsonPath("$.id").isNumber());
        comando("criar_turma", "{\"nome\": \"extensivo 2027 online\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Já existe uma turma chamada 'extensivo 2027 online'."));
        comando("criar_turma", "{}").andExpect(status().isBadRequest());
        comando("criar_turma", "{\"nome\": \"Revisão 2027\"}", ALUNO).andExpect(status().isForbidden());
    }
}
