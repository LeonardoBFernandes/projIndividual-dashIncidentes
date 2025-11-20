import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class JiraCrawler {
    //Query usando affected hardware: "project = MONA AND \"Affected hardware\" ~ \"Nome do componente\""
    public static void main(String[] args) throws JsonProcessingException {
        //System.out.println(buscarDadosKpisIncidentes());
        //System.out.println(buscarDadosRankingComponentes());
        System.out.println(calcularMTBF());
    }

    public static ObjectNode buscarDadosIncidentes(String query) throws JsonProcessingException {
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

        //Convertendo do tipo HttpResponse<JsonNode> para ObjectNode
        ObjectNode json = new ObjectMapper()
                .readValue(response.getBody().toString(), ObjectNode.class);

        return json;
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

    public static List<ObjectNode> buscarDadosComponentes() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        List<ObjectNode> dadosRankingComponentes = new ArrayList<>();
        String[] componentes = {"CPU", "Memória RAM", "Disco", "Latência", "Uso de Rede"};

        for (String componente : componentes) {
            ObjectNode dadosComponente = mapper.createObjectNode();
            dadosComponente.put("nomeComponente", componente);
            dadosComponente.put("totais", fazerConsultaContador("project = MONA AND \"Affected hardware\" ~ '" + componente + "' AND created <= \"30d\""));
            dadosComponente.put("atencao", fazerConsultaContador("project = MONA AND summary ~ 'ATENÇÃO' AND \"Affected hardware\" ~ '" + componente + "' AND created <= \"30d\""));
            dadosComponente.put("criticos", fazerConsultaContador("project = MONA AND summary ~ 'ALERTA CRÍTICO' AND \"Affected hardware\" ~ '" + componente + "' AND created <= \"30d\""));
            dadosRankingComponentes.add(dadosComponente);
        }

        //Aqui é feita a comparação dos valores
        dadosRankingComponentes.sort((jsonA, jsonB) -> {
            int valorA = jsonA.get("totais").asInt();
            int valorB = jsonB.get("totais").asInt();
            // Compara os valores (retorna a ordem crescente por padrão)
            // Colocando primeiro valorB e depois valorA é retornado ordem decrescente
            return Integer.compare(valorB, valorA);
        });

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

        ObjectNode resumo = mapper.createObjectNode();
        resumo.put("componentesComMaisIncidentes", String.join(", ", componentesComMaisIncidentes));
        dadosRankingComponentes.add(resumo);

        return dadosRankingComponentes;
    }

    public static ObjectNode calcularMTBF() {
        ObjectMapper mapper = new ObjectMapper();
        List<ZonedDateTime> datas = new ArrayList<>();
        ObjectNode incidentesArray = null;
        try {
            incidentesArray = buscarDadosIncidentes("project = MONA AND created <= 30d ORDER BY created ASC");
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        com.fasterxml.jackson.databind.JsonNode issuesArray = incidentesArray.path("issues");

        for (com.fasterxml.jackson.databind.JsonNode issue : issuesArray) {
            com.fasterxml.jackson.databind.JsonNode data = issue.path("fields").path("created");

            String dataAsText = data.asText();
            ZonedDateTime dt = ZonedDateTime.parse(dataAsText, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"));
            datas.add(dt);
        }

        List<Duration> intervalos = new ArrayList<>();

        for (int i = datas.size() - 1; i > 0; i--) {
            Duration dur = Duration.between(datas.get(i - 1), datas.get(i));
            intervalos.add(dur);
        }

        double mtbfMinutos = intervalos.stream()
                .mapToDouble(Duration::toMinutes)
                .average()
                .orElse(0);

        long horas = (long) (mtbfMinutos / 60);
        long minutos = (long) (mtbfMinutos % 60);

        ObjectNode json = mapper.createObjectNode();
        json.put("MTBF", horas + "h" + minutos + "min");

        return json;
    }
}