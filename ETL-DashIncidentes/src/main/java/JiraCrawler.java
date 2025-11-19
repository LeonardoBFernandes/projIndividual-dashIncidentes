import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class JiraCrawler {
    //Query usando affected hardware: "project = MONA AND \"Affected hardware\" ~ \"Nome do componente\""
    public static void main(String[] args) throws JsonProcessingException {
        System.out.println(buscarDadosKpisIncidentes());
        System.out.println(buscarDadosRankingComponentes());
        buscarDadosIncidentes("project = MONA AND created <= 30d ORDER BY created ASC");
    }

    public static void buscarDadosIncidentes(String query) {
        JsonNodeFactory jnf = JsonNodeFactory.instance;
        ObjectNode payload = jnf.objectNode();
        {
            ArrayNode fields = payload.putArray("fields");
            fields.add("created");
            payload.put("fieldsByKeys", true);
            payload.put("jql", query);
            payload.put("maxResults", 20);
        }

        Unirest.config().setObjectMapper(new kong.unirest.ObjectMapper() {
            private com.fasterxml.jackson.databind.ObjectMapper jacksonObjectMapper
                    = new com.fasterxml.jackson.databind.ObjectMapper();

            public <T> T readValue(String value, Class<T> valueType) {
                try {
                    return jacksonObjectMapper.readValue(value, valueType);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            public String writeValue(Object value) {
                try {
                    return jacksonObjectMapper.writeValueAsString(value);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        HttpResponse<JsonNode> response = Unirest.post("https://sptech-team-lpyjf1yr.atlassian.net/rest/api/3/search/jql")
                .basicAuth("monitora373@gmail.com", "")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .body(payload)
                .asJson();

        System.out.println(response.getBody());
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

    public static List buscarDadosKpisIncidentes() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode dadosKpisIncidentes = mapper.createObjectNode();
        List<ObjectNode> listaParaEnvio = new ArrayList<>();
        dadosKpisIncidentes.put("totais", fazerConsultaContador("project = MONA AND created <= \"30d\""));
        dadosKpisIncidentes.put("abertos", fazerConsultaContador("project = MONA AND status in (open, 'in progress') AND created <= \"30d\""));
        dadosKpisIncidentes.put("fechados", fazerConsultaContador("project = MONA AND status in (completed, canceled, closed) AND created <= \"30d\""));
        dadosKpisIncidentes.put("atencao", fazerConsultaContador("project = MONA AND summary ~ 'ATENÇÃO' AND created <= \"30d\""));
        dadosKpisIncidentes.put("criticos", fazerConsultaContador("project = MONA AND summary ~ 'ALERTA CRÍTICO' AND created <= \"30d\""));
        listaParaEnvio.add(dadosKpisIncidentes);
        return listaParaEnvio;
    }

    public static List<ObjectNode> buscarDadosRankingComponentes() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        List<ObjectNode> dadosRankingComponentes = new ArrayList<>();
        String[] componentes = {"CPU", "Memória RAM", "Disco", "Latência", "Uso de Rede"};

        // Monta a lista de ObjectNode
        for (String componente : componentes) {
            ObjectNode dadosComponente = mapper.createObjectNode();
            dadosComponente.put("nomeComponente", componente);
            dadosComponente.put("totais", fazerConsultaContador("project = MONA AND \"Affected hardware\" ~ '" + componente + "' AND created <= \"30d\""));
            dadosComponente.put("atencao", fazerConsultaContador("project = MONA AND summary ~ 'ATENÇÃO' AND \"Affected hardware\" ~ '" + componente + "' AND created <= \"30d\""));
            dadosComponente.put("criticos", fazerConsultaContador("project = MONA AND summary ~ 'ALERTA CRÍTICO' AND \"Affected hardware\" ~ '" + componente + "' AND created <= \"30d\""));
            dadosRankingComponentes.add(dadosComponente);
        }

        // Ordena diretamento a lista de ObjectNode
        dadosRankingComponentes.sort((jsonA, jsonB) -> {
            int valorA = jsonA.get("totais").asInt();
            int valorB = jsonB.get("totais").asInt();
            return Integer.compare(valorB, valorA); // ordem decrescente
        });

        // Agora encontra o(s) componente(s) com mais incidentes
        List<String> componentesComMaisIncidentes = new ArrayList<>();
        int maiorNumeroIncidentesTotais = 0;

        if (!dadosRankingComponentes.isEmpty()) {
            maiorNumeroIncidentesTotais = dadosRankingComponentes.get(0).get("totais").asInt();
        }

        for (ObjectNode json : dadosRankingComponentes) {
            int totais = json.get("totais").asInt();
            if (totais == maiorNumeroIncidentesTotais) {
                componentesComMaisIncidentes.add(json.get("nomeComponente").asText());
            } else {
                break;
            }
        }

        // Adiciona o item final à lista
        ObjectNode resumo = mapper.createObjectNode();
        resumo.put("componentesComMaisIncidentes", String.join(", ", componentesComMaisIncidentes));
        dadosRankingComponentes.add(resumo);

        return dadosRankingComponentes;
    }
}