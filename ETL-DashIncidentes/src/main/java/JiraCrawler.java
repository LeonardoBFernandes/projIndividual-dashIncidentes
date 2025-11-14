import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;

import java.io.IOException;

public class JiraCrawler {
    public static void main(String[] args) {
        System.out.println(buscarDadosKpisIncidentes());
    }

    public static int fazerConsultaKpisIncidentes(String query) {
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
        dadosKpisIncidentes.put("totais", fazerConsultaKpisIncidentes("project = MONA"));
        dadosKpisIncidentes.put("abertos", fazerConsultaKpisIncidentes("project = MONA AND status = open"));
        dadosKpisIncidentes.put("fechados", fazerConsultaKpisIncidentes("project = MONA AND status in (completed, canceled, closed)"));
        dadosKpisIncidentes.put("atencao", fazerConsultaKpisIncidentes("project = MONA AND summary ~ \"\\\"ATENÇÃO\\\"\""));
        dadosKpisIncidentes.put("criticos", fazerConsultaKpisIncidentes("project = MONA AND summary ~ \"\\\"ALERTA CRÍTICO\\\"\""));
        return dadosKpisIncidentes;
    }
}
