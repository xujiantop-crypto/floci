package com.floci.test;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
import software.amazon.awssdk.services.apigateway.model.Resource;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiGatewayAwsStatusCodeSelectionCompatibilityTest {

    @Test
    void sdkConfiguredPatternsSelectBackendStatusesOnTheDeployedApi() throws Exception {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Floci execute-api endpoint regression");

        try (ApiGatewayClient apiGateway = TestFixtures.apiGatewayClient();
             SqsClient sqs = TestFixtures.sqsClient()) {
            String queueName = TestFixtures.uniqueName("apigw-status");
            String queueUrl = sqs.createQueue(request -> request.queueName(queueName)).queueUrl();
            String apiId = null;
            try {
                apiId = apiGateway.createRestApi(request -> request.name(queueName)).id();
                String restApiId = apiId;
                String rootId = apiGateway.getResources(request -> request.restApiId(restApiId))
                        .items().stream().filter(resource -> "/".equals(resource.path()))
                        .findFirst().map(Resource::id).orElseThrow();
                String resourceId = apiGateway.createResource(request -> request
                        .restApiId(restApiId).parentId(rootId).pathPart("selection")).id();
                apiGateway.putMethod(request -> request.restApiId(restApiId)
                        .resourceId(resourceId).httpMethod("POST").authorizationType("NONE"));
                apiGateway.putIntegration(request -> request.restApiId(restApiId)
                        .resourceId(resourceId).httpMethod("POST").integrationHttpMethod("POST")
                        .type(IntegrationType.AWS)
                        .uri("arn:aws:apigateway:us-east-1:sqs:path/000000000000/" + queueName)
                        .requestTemplates(Map.of("application/json",
                                "Action=GetQueueUrl&QueueName=$input.path('$.queueName')")));

                response(apiGateway, restApiId, resourceId, "201", "200", "success");
                response(apiGateway, restApiId, resourceId, "409", "4\\d{2}", "error");
                response(apiGateway, restApiId, resourceId, "502", "", "default");
                assertThat(apiGateway.getIntegrationResponse(request -> request
                        .restApiId(restApiId).resourceId(resourceId).httpMethod("POST")
                        .statusCode("409")).selectionPattern()).isEqualTo("4\\d{2}");

                String deploymentId = apiGateway.createDeployment(request -> request.restApiId(restApiId)).id();
                apiGateway.createStage(request -> request.restApiId(restApiId)
                        .stageName("test").deploymentId(deploymentId));

                HttpClient httpClient = TestFixtures.emulatorHttpClient();
                URI endpoint = URI.create(TestFixtures.endpoint() + "/execute-api/" + restApiId + "/test/selection");
                assertResponse(httpClient, endpoint, queueName, 201, "success");
                assertResponse(httpClient, endpoint, queueName + "-missing", 409, "error");
            } finally {
                if (apiId != null) {
                    String restApiId = apiId;
                    apiGateway.deleteRestApi(request -> request.restApiId(restApiId));
                }
                sqs.deleteQueue(request -> request.queueUrl(queueUrl));
            }
        }
    }

    private static void response(ApiGatewayClient apiGateway, String apiId, String resourceId,
                                 String status, String pattern, String marker) {
        apiGateway.putMethodResponse(request -> request.restApiId(apiId)
                .resourceId(resourceId).httpMethod("POST").statusCode(status));
        apiGateway.putIntegrationResponse(request -> request.restApiId(apiId)
                .resourceId(resourceId).httpMethod("POST").statusCode(status)
                .selectionPattern(pattern)
                .responseTemplates(Map.of("application/json", "{\"selected\":\"" + marker + "\"}")));
    }

    private static void assertResponse(HttpClient httpClient, URI endpoint, String queueName,
                                       int status, String marker) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"queueName\":\"" + queueName + "\"}"))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.body()).isEqualTo("{\"selected\":\"" + marker + "\"}");
    }
}
