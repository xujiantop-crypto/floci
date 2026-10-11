package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@QuarkusTest
class ApiGatewayAwsStatusCodeSelectionIntegrationTest {

    @InjectMock
    LambdaService lambdaService;

    private String apiId;
    private String methodPath;
    private String queueName;
    private String queueUrl;

    @BeforeAll
    static void configureAwsContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createQueueAndApi() {
        queueName = "apigw-status-" + UUID.randomUUID();
        queueUrl = given().contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body(Map.of("QueueName", queueName))
                .post("/").then().statusCode(200).extract().path("QueueUrl");

        apiId = given().contentType(ContentType.JSON).body(Map.of("name", queueName))
                .post("/restapis").then().statusCode(201).extract().path("id");
        String rootId = given().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        String resourceId = given().contentType(ContentType.JSON).body(Map.of("pathPart", "selection"))
                .post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");
        methodPath = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST";
        given().contentType(ContentType.JSON).body(Map.of("authorizationType", "NONE"))
                .put(methodPath).then().statusCode(201);
    }

    @AfterEach
    void cleanup() {
        if (apiId != null) {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
        if (queueUrl != null) {
            given().contentType("application/x-amz-json-1.0")
                    .header("X-Amz-Target", "AmazonSQS.DeleteQueue")
                    .body(Map.of("QueueUrl", queueUrl)).post("/").then().statusCode(200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"200", "2\\d{2}"})
    void successfulAwsQueryResponseMatchesItsStatusCode(String pattern) {
        configureQuery(queueName);
        response(200, pattern, "status");
        response(502, "", "default");
        execute(200, "status");
    }

    @ParameterizedTest
    @ValueSource(strings = {"400", "4\\d{2}"})
    void failedAwsQueryResponseMatchesItsStatusCode(String pattern) {
        configureQuery(queueName + "-missing");
        response(400, pattern, "status");
        response(200, "", "default");
        execute(400, "status");
    }

    @Test
    void awsErrorTextDoesNotSelectAnIntegrationResponse() {
        configureQuery(queueName + "-missing");
        response(409, ".*NonExistentQueue.*", "error-text");
        response(200, "", "default");
        execute(200, "default");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void unmatchedAwsStatusUsesTheDefaultResponse(String defaultPattern) {
        configureQuery(queueName + "-missing");
        response(503, "5\\d{2}", "unmatched");
        response(200, defaultPattern, "default");
        execute(200, "default");
    }

    @Test
    void failedAwsJsonResponseMatchesItsStatusCode() {
        configureAws("arn:aws:apigateway:us-east-1:dynamodb:action/PutItem",
                "{\"TableName\":\"" + queueName + "\",\"Item\":{\"id\":{\"S\":\"x\"}}}");
        response(400, "400", "status");
        response(200, "", "default");
        execute(400, "status");
    }

    @Test
    void lambdaSuccessKeepsTheDefaultResponseInsteadOfMatchingItsStatus() {
        configureLambda(null, "{\"value\":200}");
        response(201, "200", "status");
        response(200, "", "default");
        execute(200, "default");
    }

    @Test
    void lambdaFunctionErrorKeepsMatchingItsErrorString() {
        configureLambda("Unhandled", "{\"errorMessage\":\"chosen lambda error\"}");
        response(409, ".*chosen lambda error.*", "lambda-error");
        response(201, "200", "status");
        response(502, "", "default");
        execute(409, "lambda-error");
    }

    private void configureQuery(String backendQueue) {
        configureAws("arn:aws:apigateway:us-east-1:sqs:path/000000000000/" + backendQueue,
                "Action=GetQueueUrl&QueueName=" + backendQueue);
    }

    private void configureLambda(String error, String payload) {
        when(lambdaService.invoke(anyString(), anyString(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, error, payload.getBytes(StandardCharsets.UTF_8), null, "request"));
        configureAws("arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
                + "arn:aws:lambda:us-east-1:000000000000:function:status-selection/invocations", "{}");
    }

    private void configureAws(String uri, String template) {
        given().contentType(ContentType.JSON)
                .body(Map.of("type", "AWS", "httpMethod", "POST", "uri", uri,
                        "requestTemplates", Map.of("application/json", template)))
                .put(methodPath + "/integration").then().statusCode(201);
    }

    private void response(int status, String pattern, String marker) {
        given().contentType(ContentType.JSON).body(Map.of("responseParameters", Map.of()))
                .put(methodPath + "/responses/" + status).then().statusCode(201);
        Map<String, Object> configuration = new HashMap<>();
        configuration.put("selectionPattern", pattern);
        configuration.put("responseTemplates", Map.of("application/json", "{\"selected\":\"" + marker + "\"}"));
        given().contentType(ContentType.JSON).body(configuration)
                .put(methodPath + "/integration/responses/" + status).then().statusCode(201);
    }

    private void execute(int status, String marker) {
        String deploymentId = given().contentType(ContentType.JSON).body(Map.of("description", "selection"))
                .post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON).body(Map.of("stageName", "test", "deploymentId", deploymentId))
                .post("/restapis/" + apiId + "/stages").then().statusCode(201);
        given().contentType(ContentType.JSON).body("{}")
                .post("/execute-api/" + apiId + "/test/selection")
                .then().log().ifValidationFails().statusCode(status).body("selected", equalTo(marker));
    }
}
