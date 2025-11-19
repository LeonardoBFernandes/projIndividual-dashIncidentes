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
        dadosKpisIncidentes.put("totais", fazerConsultaContador("project = MONA AND created <= \"30d\"", apiToken));
        dadosKpisIncidentes.put("abertos", fazerConsultaContador("project = MONA AND status in (open, 'in progress') AND created <= \"30d\"", apiToken));
        dadosKpisIncidentes.put("fechados", fazerConsultaContador("project = MONA AND status in (completed, canceled, closed) AND created <= \"30d\"", apiToken));
        dadosKpisIncidentes.put("atencao", fazerConsultaContador("project = MONA AND summary ~ 'ATENÇÃO' AND created <= \"30d\"", apiToken));
        dadosKpisIncidentes.put("criticos", fazerConsultaContador("project = MONA AND summary ~ 'ALERTA CRÍTICO' AND created <= \"30d\"", apiToken));
        return dadosKpisIncidentes;
    }
}
