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
        ObjectNode resultadoNode = buscarDadosKpisIncidentes(apiToken);
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

    public static ObjectNode buscarDadosKpisIncidentes(String apiToken) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode dadosKpisIncidentes = mapper.createObjectNode();

        //Dispara todas as requisições em paralelo (sem esperar a resposta ainda)
        CompletableFuture<Integer> futuroTotais = CompletableFuture.supplyAsync(() ->
                fazerConsultaContador("project = MONA AND created <= \"30d\"", apiToken)
        );

        CompletableFuture<Integer> futuroAbertos = CompletableFuture.supplyAsync(() ->
                fazerConsultaContador("project = MONA AND status in (Open, 'In Progress') AND created <= \"30d\"", apiToken)
        );

        CompletableFuture<Integer> futuroFechados = CompletableFuture.supplyAsync(() ->
                fazerConsultaContador("project = MONA AND status in (Completed, Canceled, Closed) AND created <= \"30d\"", apiToken)
        );

        CompletableFuture<Integer> futuroAtencaoAbertos = CompletableFuture.supplyAsync(() ->
                fazerConsultaContador("project = MONA AND summary ~ 'ATENÇÃO' AND created <= \"30d\" AND status = Open", apiToken)
        );

        CompletableFuture<Integer> futuroAtencaoTotais = CompletableFuture.supplyAsync(() ->
                fazerConsultaContador("project = MONA AND summary ~ 'ATENÇÃO' AND created <= \"30d\"", apiToken)
        );

        CompletableFuture<Integer> futuroCriticosAbertos = CompletableFuture.supplyAsync(() ->
                fazerConsultaContador("project = MONA AND summary ~ 'ALERTA CRÍTICO' AND created <= \"30d\" AND status = Open", apiToken)
        );

        CompletableFuture<Integer> futuroCriticosTotais = CompletableFuture.supplyAsync(() ->
                fazerConsultaContador("project = MONA AND summary ~ 'ALERTA CRÍTICO' AND created <= \"30d\"", apiToken)
        );

        // Aguarda até que todas as consultas terminem (Até que as respostas de todas chegue
        CompletableFuture.allOf(futuroTotais, futuroAbertos, futuroFechados, futuroAtencaoAbertos, futuroAtencaoTotais, futuroCriticosAbertos, futuroCriticosTotais).join();

        try {
            // Coleta os resultados das consultas e montar o JSON
            dadosKpisIncidentes.put("totais", futuroTotais.get());
            dadosKpisIncidentes.put("abertos", futuroAbertos.get());
            dadosKpisIncidentes.put("fechados", futuroFechados.get());
            dadosKpisIncidentes.put("atencaoAbertos", futuroAtencaoAbertos.get());
            dadosKpisIncidentes.put("atencaoTotais", futuroAtencaoTotais.get());
            dadosKpisIncidentes.put("criticosAbertos", futuroCriticosAbertos.get());
            dadosKpisIncidentes.put("criticosTotais", futuroCriticosTotais.get());
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Erro ao processar consultas assíncronas: " + e.getMessage(), e);
        }

        return dadosKpisIncidentes;
    }
}
