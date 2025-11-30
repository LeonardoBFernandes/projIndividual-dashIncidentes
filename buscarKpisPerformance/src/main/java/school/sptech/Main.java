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
import java.time.OffsetDateTime;
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

    public static ObjectNode buscarDadosIncidentes(String query, String[] fieldsRequest, int maxResults, String apiToken) throws JsonProcessingException {
        JsonNodeFactory jnf = JsonNodeFactory.instance;
        ObjectNode payload = jnf.objectNode();
        {
            ArrayNode fields = payload.putArray("fields");
            for (String field:fieldsRequest) {
                fields.add(field);
            }
            payload.put("fieldsByKeys", true);
            payload.put("jql", query);
            payload.put("maxResults", maxResults);
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
        String[] fields = {"created"};
        try {
            incidentesArray = buscarDadosIncidentes("project = MONA AND created <= 30d ORDER BY created ASC", fields, 30, apiToken);
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
        json.put("MTBFhoras", horas);
        json.put("MTBFminutos", minutos);

        return json;
    }

    public static ObjectNode calcularMTTR(String apiToken) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode incidentesArrayCriticos = null;
        ObjectNode incidentesArrayAtencao = null;

        // Solicitamos explicitamente os campos created e resolutiondate
        String[] fields = {"created", "resolutiondate"};

        CompletableFuture<ObjectNode> futuroDadosIncidentesCriticos = CompletableFuture.supplyAsync(() ->
                {
                    try {
                        return buscarDadosIncidentes(
                                "project = MONA AND status = Completed AND priority = Highest AND created <= 30d",
                                fields,
                                30,
                                apiToken);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                }
        );

        CompletableFuture<ObjectNode> futuroDadosIncidentesAtencao = CompletableFuture.supplyAsync(() ->
                {
                    try {
                        return buscarDadosIncidentes(
                                "project = MONA AND status = Completed AND priority = High AND created <= 30d",
                                fields,
                                30,
                                apiToken);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                }
        );

        CompletableFuture.allOf(futuroDadosIncidentesCriticos, futuroDadosIncidentesAtencao).join();

        try {
            incidentesArrayCriticos = futuroDadosIncidentesCriticos.get();
            incidentesArrayAtencao = futuroDadosIncidentesAtencao.get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }

        ObjectNode result = mapper.createObjectNode();

        // Calculamos separadamente para garantir que as listas não se misturem
        Duration mttrCriticos = calcularMediaPorGrupo(incidentesArrayCriticos);
        Duration mttrAtencao = calcularMediaPorGrupo(incidentesArrayAtencao);

        // Popula o JSON de resposta
        result.put("MTTRCriticosHoras", mttrCriticos.toHours());
        result.put("MTTRCriticosMinutos", mttrCriticos.toMinutesPart()); // Minutos restantes após as horas

        result.put("MTTRAtencaoHoras", mttrAtencao.toHours());
        result.put("MTTRAtencaoMinutos", mttrAtencao.toMinutesPart());

        return result;
    }

    // Método auxiliar para evitar repetição de código e manter a lógica limpa
    private static Duration calcularMediaPorGrupo(ObjectNode json) {
        List<Long> temposDeResolucao = new ArrayList<>();
        com.fasterxml.jackson.databind.JsonNode issuesArray = json.path("issues");

        // Formatter para ler o padrão do Jira (ex: 2023-10-25T14:30:00.000-0300)
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

        for (com.fasterxml.jackson.databind.JsonNode issue : issuesArray) {
            com.fasterxml.jackson.databind.JsonNode fields = issue.path("fields");

            String createdStr = fields.path("created").asText(null);
            String resolutionStr = fields.path("resolutiondate").asText(null);

            if (createdStr != null && resolutionStr != null) {
                try {
                    // Converte as Strings do Jira para Objetos de Data
                    OffsetDateTime created = OffsetDateTime.parse(createdStr, formatter);
                    OffsetDateTime resolution = OffsetDateTime.parse(resolutionStr, formatter);

                    // Calcula a duração entre Criado e Resolvido
                    Duration duration = Duration.between(created, resolution);

                    // Salva em milissegundos para fazer a média depois
                    temposDeResolucao.add(duration.toMillis());
                } catch (Exception e) {
                    // Logar erro de parse se necessário, ou ignorar issue malformada
                    System.err.println("Erro ao processar datas da issue: " + e.getMessage());
                }
            }
        }

        // Calcula a média
        double mediaMillis = temposDeResolucao.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0);

        return Duration.ofMillis((long) mediaMillis);
    }

    public static ObjectNode calcularSlaCompliance(String apiToken) {
        ObjectMapper mapper = new ObjectMapper();
        int incidentesTotais = fazerConsultaContador("project = MONA AND status = Completed AND created <= 30d", apiToken);
        ObjectNode dadosIncidentes = null;
        String[] fields = {"created", "resolutiondate", "priority"};

        try {
            dadosIncidentes = buscarDadosIncidentes("project = MONA AND status = Completed AND created <= 30d", fields, 5000, apiToken);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        int incidentesResolvidosDentroDoTempo = contagemIncidentesResolvidosNoTempo(dadosIncidentes);

        double porcentagemSla = (incidentesResolvidosDentroDoTempo * 100) / incidentesTotais;
        double porcentagemArredondada = Math.round(porcentagemSla * 10) / 10;

        ObjectNode result = mapper.createObjectNode();
        result.put("porcentagemSLA", porcentagemArredondada);

        return result;
    }

    public static int contagemIncidentesResolvidosNoTempo(ObjectNode dadosIncidentes) {
        int contador = 0;
        com.fasterxml.jackson.databind.JsonNode issuesArray = dadosIncidentes.path("issues");

        // Formatter para ler o padrão do Jira (ex: 2023-10-25T14:30:00.000-0300)
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        Duration limiteCritico = Duration.ofHours(12);
        Duration limiteAtencao = Duration.ofHours(24);

        for (com.fasterxml.jackson.databind.JsonNode issue : issuesArray) {
            com.fasterxml.jackson.databind.JsonNode fields = issue.path("fields");

            String createdStr = fields.path("created").asText(null);
            String resolutionStr = fields.path("resolutiondate").asText(null);
            String priorityStr = fields.path("priority").path("name").asText(null);
            System.out.println(priorityStr);

            OffsetDateTime created = OffsetDateTime.parse(createdStr, formatter);
            OffsetDateTime resolution = OffsetDateTime.parse(resolutionStr, formatter);
            Duration duration = Duration.between(created, resolution);

            // O compareTo retorna:
            //  1 (ou > 0) se diferenca for MAIOR que limite
            //  0 se forem IGUAIS
            // -1 (ou < 0) se diferenca for MENOR que limite
            if (priorityStr.equalsIgnoreCase("highest") && duration.compareTo(limiteCritico) <= 0) {
                contador++;
            } else if (priorityStr.equalsIgnoreCase("high") && duration.compareTo(limiteAtencao) <= 0) {
                contador++;
            }
        }

        return contador;
    }
}