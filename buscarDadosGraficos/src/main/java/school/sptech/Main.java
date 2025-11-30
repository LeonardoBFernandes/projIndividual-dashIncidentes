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
import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Main implements RequestHandler<Map<String, Object>, String>{
    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        final S3Client s3Client = S3Client.builder().build();
        try {
            String apiToken = System.getenv("JIRA_API_TOKEN");
            String BUCKET_NAME = System.getenv().getOrDefault("CLIENT_BUCKET", "teste-123-monitora");
            String fileName = "dados-" + Instant.now().toString() + ".json";

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd/")
                    .withZone(ZoneId.of("UTC")); // Ou ZoneId.of("America/Sao_Paulo")

            String DIRECTORY_PATH = "dadosGraficoIncidentes" + "/" + formatter.format(Instant.now());

            String objectKey = DIRECTORY_PATH + fileName;

            // Garantir que não haja barras duplas acidentais (opcional, mas recomendado)
            // Se DIRECTORY_PATH não terminar com '/', você deve adicionar manualmente.
            if (!DIRECTORY_PATH.isEmpty() && !DIRECTORY_PATH.endsWith("/")) {
                objectKey = "dadosGraficoIncidentes" + "/" + DIRECTORY_PATH + "/" + fileName;
            }

            String json = formatarParaJSON(apiToken);

            PutObjectRequest putOb = PutObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(objectKey)
                    .contentType("application/json")
                    .build();

            s3Client.putObject(putOb, RequestBody.fromString(json, StandardCharsets.UTF_8));

            return "Arquivo salvo em: " + objectKey;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String formatarParaJSON(String apiToken) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode dados = mapper.createObjectNode();

        List<String> datas = buscarListaDatas(apiToken);
        List<Integer> dadosIncidentesAbertos = buscarDadosIncidentesAbertos(apiToken);
        List<Integer> dadosIncidentesFechados = buscarDadosIncidentesFechados(apiToken);

        dados.put("datas", String.valueOf(datas));
        dados.put("incidentesAbertos", String.valueOf(dadosIncidentesAbertos));
        dados.put("incidentesFechados", String.valueOf(dadosIncidentesFechados));

        String dadosFormatados = null;
        try {
            dadosFormatados = mapper.writeValueAsString(dados);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        return dadosFormatados;
    }

    public static List<String> buscarListaDatas(String apiToken) {
        List<String> datas = new ArrayList<>();
        DateTimeFormatter formatterJSON = DateTimeFormatter.ofPattern("dd/MM/yyyy");

        LocalDate dataAtual = LocalDate.now();

        for (int i = 0; i < 30; i++) {
            LocalDate dataAPesquisar = dataAtual.minusDays(i);
            datas.add(dataAPesquisar.format(formatterJSON));
        }

        return datas;
    }

    public static List<Integer> buscarDadosIncidentesAbertos(String apiToken) {
        List<Integer> incidentesAbertos = new ArrayList<>();
        DateTimeFormatter formatterJira = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        LocalDate dataAtual = LocalDate.now();

        for (int i = 0; i < 30; i++) {
            LocalDate dataAPesquisar = dataAtual.minusDays(i);
            LocalDate dataMaior = dataAPesquisar.plusDays(1);
            incidentesAbertos.add(fazerConsultaContador("project = MONA AND created >= "+ dataAPesquisar.format(formatterJira) +" AND created < " + dataMaior.format(formatterJira), apiToken));
        }

        return incidentesAbertos;
    }

    public static List<Integer> buscarDadosIncidentesFechados(String apiToken) {
        List<Integer> incidentesFechados = new ArrayList<>();
        DateTimeFormatter formatterJira = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        LocalDate dataAtual = LocalDate.now();

        for (int i = 0; i < 30; i++) {
            LocalDate dataAPesquisar = dataAtual.minusDays(i);
            LocalDate dataMaior = dataAPesquisar.plusDays(1);
            incidentesFechados.add(fazerConsultaContador("project = MONA AND resolved >= "+dataAPesquisar.format(formatterJira)+" AND resolved < " + dataMaior.format(formatterJira), apiToken));
        }

        return incidentesFechados;
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
