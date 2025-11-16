import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class JiraCrawler {
    //Query usando affected hardware: "project = MONA AND \"Affected hardware\" ~ \"Nome do componente\""
    public static void main(String[] args) throws JsonProcessingException {
        System.out.println(buscarDadosKpisIncidentes());
        System.out.println(buscarDadosRankingComponentes());
    }

    public static int fazerConsultaContador(String query) {
        JsonNodeFactory jnf = JsonNodeFactory.instance;
        ObjectNode payload = jnf.objectNode();
        {
            payload.put("jql", query);
        }

        Unirest.config().setObjectMapper(new kong.unirest.ObjectMapper() {
            private com.fasterxml.jackson.databind.ObjectMapper jacksonObjectMapper
                    = new com.fasterxml.jackson.databind.ObjectMapper();

            @Override
            public <T> T readValue(String value, Class<T> valueType) {
                try {
                    return jacksonObjectMapper.readValue(value, valueType);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public String writeValue(Object value) {
                try {
                    return jacksonObjectMapper.writeValueAsString(value);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        HttpResponse<JsonNode> response = Unirest.post("https://sptech-team-lpyjf1yr.atlassian.net/rest/api/3/search/approximate-count")
                .basicAuth("monitora373@gmail.com", "")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .body(payload)
                .asJson();

        //System.out.println(response.getBody());
        return response.getBody().getObject().getInt("count");
    }

    public static ObjectNode buscarDadosKpisIncidentes() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode dadosKpisIncidentes = mapper.createObjectNode();
        dadosKpisIncidentes.put("totais", fazerConsultaContador("project = MONA"));
        dadosKpisIncidentes.put("abertos", fazerConsultaContador("project = MONA AND status in (open, 'in progress')"));
        dadosKpisIncidentes.put("fechados", fazerConsultaContador("project = MONA AND status in (completed, canceled, closed)"));
        dadosKpisIncidentes.put("atencao", fazerConsultaContador("project = MONA AND summary ~ 'ATENÇÃO'"));
        dadosKpisIncidentes.put("criticos", fazerConsultaContador("project = MONA AND summary ~ 'ALERTA CRÍTICO'"));
        return dadosKpisIncidentes;
    }

    public static List buscarDadosRankingComponentes() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        List<ObjectNode> dadosRankingComponentes = new ArrayList<>();
        String[] componentes = {"CPU", "Memória RAM", "Disco", "Latência", "Uso de Rede"};
        for (String componente : componentes) {
            ObjectNode dadosComponente = mapper.createObjectNode();
            dadosComponente.put("nomeComponente", componente);
            dadosComponente.put("totais", fazerConsultaContador("project = MONA AND \"Affected hardware\" ~ '"+componente+"'"));
            dadosComponente.put("atencao", fazerConsultaContador("project = MONA AND summary ~ 'ATENÇÃO' AND \"Affected hardware\" ~ '"+componente+"'"));
            dadosComponente.put("criticos", fazerConsultaContador("project = MONA AND summary ~ 'ALERTA CRÍTICO' AND \"Affected hardware\" ~ '"+componente+"'"));
            dadosRankingComponentes.add(dadosComponente);
        }

        //Fazendo ordenação da lista de jsons
        //Primeiro é necessário transformar a lista em String
        String listaAsString = mapper.writeValueAsString(dadosRankingComponentes);
        //Aqui está sendo feito o mapeamento do json para list
        List<Map<String, Object>> listaFinalOrdenada = mapper.readValue(listaAsString, List.class);
        //Aqui é feita a comparação dos valores
        listaFinalOrdenada.sort((jsonA, jsonB) -> {
            // Acessa o valor da chave de ordenação em cada Map (JSON)
            // Assumimos que o valor é um Integer e fazemos o cast.
            Integer valorA = (Integer) jsonA.get("totais");
            Integer valorB = (Integer) jsonB.get("totais");

            // Compara os valores (retorna a ordem crescente por padrão)
            // Colocando primeiro valorB e depois valorA é retornado ordem decrescente
            return valorB.compareTo(valorA);
        });

        return listaFinalOrdenada;
    }
}
