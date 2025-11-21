package school.sptech;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;
import java.io.IOException;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.amazonaws.services.lambda.runtime.Context;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class Main implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse>{
    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        //Buscando token da api do jira configurada na variável de ambiente do lambda
        String apiToken = System.getenv("JIRA_API_TOKEN");

        // 1. Logar informações do evento
        context.getLogger().log("Requisição HTTP Recebida. Método: " + event.getRequestContext().getHttp().getMethod());

        // 2. Processar a lógica de negócio
        List<ObjectNode> resultadoNode = new ArrayList<>();

        CompletableFuture<ObjectNode> futuroMTBF = CompletableFuture.supplyAsync(() ->
                calcularMTBF(apiToken)
        );
        CompletableFuture<ObjectNode> futuroMTTR = CompletableFuture.supplyAsync(() ->
                calcularMTTR(apiToken)
        );
        CompletableFuture<ObjectNode> futuroSlaCompliance = CompletableFuture.supplyAsync(() ->
                calcularSlaCompliance(apiToken)
        );

        CompletableFuture.allOf(futuroMTBF, futuroMTTR, futuroSlaCompliance).join();

        try {
            resultadoNode.add(futuroMTBF.get());
            resultadoNode.add(futuroMTTR.get());
            resultadoNode.add(futuroSlaCompliance.get());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }

        //Convertendo resposta da função buscarDadosKpisIncidentes em JSON
        String jsonFinal = resultadoNode.toString();

        // 3. Construir a resposta HTTP
        APIGatewayV2HTTPResponse response = APIGatewayV2HTTPResponse.builder()
                .withStatusCode(200) // Código de status HTTP
                .withBody(jsonFinal)
                .withHeaders(java.util.Collections.singletonMap("Content-Type", "application/json"))
                .build();

        return response;
    }

    public static int fazerConsultaContador(String query, String apiToken) {
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
                .basicAuth("monitora373@gmail.com", apiToken)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .body(payload)
                .asJson();

        //System.out.println(response.getBody());
        return response.getBody().getObject().getInt("count");
    }

    public static ObjectNode buscarDadosIncidentes(String query, String field, String apiToken) throws JsonProcessingException {
        JsonNodeFactory jnf = JsonNodeFactory.instance;
        ObjectNode payload = jnf.objectNode();
        {
            ArrayNode fields = payload.putArray("fields");
            fields.add(field);
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
                .basicAuth("monitora373@gmail.com", apiToken)
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

    public static ObjectNode calcularMTBF(String apiToken) {
        ObjectMapper mapper = new ObjectMapper();
        List<ZonedDateTime> datas = new ArrayList<>();
        ObjectNode incidentesArray = null;
        try {
            incidentesArray = buscarDadosIncidentes("project = MONA AND created <= 30d ORDER BY created ASC", "created", apiToken);
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

    public static ObjectNode calcularMTTR(String apiToken) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode incidentesArray = null;
        List<Long> temposDeResolucao = new ArrayList<>();

        try {
            incidentesArray = buscarDadosIncidentes("project = MONA AND status = Completed AND created <= 30d ORDER BY \"Time to resolution\" ASC", "customfield_10092", apiToken);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        com.fasterxml.jackson.databind.JsonNode issuesArray = incidentesArray.path("issues");

        for (com.fasterxml.jackson.databind.JsonNode issue : issuesArray) {
            // Acessa o campo de SLA (Time to resolution)
            com.fasterxml.jackson.databind.JsonNode slaField = issue.path("fields").path("customfield_10092");
            com.fasterxml.jackson.databind.JsonNode cycles = slaField.path("completedCycles");
            if (cycles.size() > 0) {
                // PEGA O ÚLTIMO CICLO (Geralmente o válido para a resolução final)
                com.fasterxml.jackson.databind.JsonNode lastCycle = cycles.get(cycles.size() - 1);
                // Pega o valor em Milissegundos
                long millis = lastCycle.path("elapsedTime").path("millis").asLong();

                // Evita adicionar 0 se algo estiver errado
                if (millis > 0) {
                    temposDeResolucao.add(millis);
                }
            }
        }

        double mediaMillis = temposDeResolucao.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0);

        Duration mttr = Duration.ofMillis((long) mediaMillis);
        long horas = mttr.toHours();
        long minutos = mttr.toMinutesPart();

        ObjectNode result = mapper.createObjectNode();
        result.put("MTTR", horas + "h" + minutos + "min");

        return result;
    }

    public static ObjectNode calcularSlaCompliance(String apiToken) {
        ObjectMapper mapper = new ObjectMapper();
        int incidentesTotais = fazerConsultaContador("project = MONA AND status = Completed AND created <= 30d", apiToken);
        int incidentesResolvidosDentroDoTempo = fazerConsultaContador("project = MONA AND status = Completed AND " +
                "created <= 30d AND \"Time to resolution\" = completed() AND \"Time to resolution\" != everBreached()", apiToken);

        double porcentagemSla = (incidentesResolvidosDentroDoTempo * 100) / incidentesTotais;
        double porcentagemArredondada = Math.round(porcentagemSla * 10) / 10;

        ObjectNode result = mapper.createObjectNode();
        result.put("porcentagemSLA", porcentagemArredondada + "%");

        return result;
    }
}