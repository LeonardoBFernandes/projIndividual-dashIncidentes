package school.sptech;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class Main implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        //Buscando token da api do jira configurada na variável de ambiente do lambda
        String apiToken = System.getenv("JIRA_API_TOKEN");

        // 1. Logar informações do evento
        context.getLogger().log("Requisição HTTP Recebida. Método: " + event.getRequestContext().getHttp().getMethod());

        // 2. Processar a lógica de negócio
        List<ObjectNode> resultadoNode = null;
        try {
            resultadoNode = buscarDadosComponentes(apiToken);
        } catch (JsonProcessingException e) {
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

    public static List<ObjectNode> buscarDadosComponentes(String apiToken) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        List<ObjectNode> dadosRankingComponentes = new ArrayList<>();
        String[] componentes = {"CPU", "Memória RAM", "Disco", "Latência", "Uso de Rede"};

        for (String componente : componentes) {
            ObjectNode dadosComponente = mapper.createObjectNode();
            CompletableFuture<Integer> futuroTotais = CompletableFuture.supplyAsync(() ->
                    fazerConsultaContador("project = MONA AND \"Affected hardware\" ~ '" + componente + "' AND created <= \"30d\"", apiToken)
            );
            CompletableFuture<Integer> futuroAtencao = CompletableFuture.supplyAsync(() ->
                    fazerConsultaContador("project = MONA AND summary ~ 'ATENÇÃO' AND \"Affected hardware\" ~ '" + componente + "' AND created <= \"30d\"", apiToken)
            );
            CompletableFuture<Integer> futuroCriticos = CompletableFuture.supplyAsync(() ->
                    fazerConsultaContador("project = MONA AND summary ~ 'ALERTA CRÍTICO' AND \"Affected hardware\" ~ '" + componente + "' AND created <= \"30d\"", apiToken)
            );
            CompletableFuture.allOf(futuroTotais, futuroAtencao, futuroCriticos).join();

            try {
                dadosComponente.put("nomeComponente", componente);
                dadosComponente.put("totais", futuroTotais.get());
                dadosComponente.put("atencao", futuroAtencao.get());
                dadosComponente.put("criticos", futuroCriticos.get());
                dadosRankingComponentes.add(dadosComponente);
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException("Erro ao processar consultas assíncronas: " + e.getMessage(), e);
            }
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
}
