package com.radixdlt.test.network.client;

import com.radixdlt.client.lib.api.sync.RadixApiException;
import com.radixdlt.test.network.RadixNetworkConfiguration;
import com.radixdlt.utils.functional.Failure;
import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;
import kong.unirest.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A small HTTP client that consumes the non-JSON-RPC methods e.g. /health.
 * <p>
 * Also consumes the JSON-RPC methods that are not part of the RadixApi client e.g. /faucet
 */
public class RadixHttpClient {

    public enum HealthStatus {
        BOOTING,
        UP
    }

    private static final String FAUCET_PATH = "/faucet";
    private static final String HEALTH_PATH = "/health";
    private static final String METRICS_PATH = "/metrics";

    public static RadixHttpClient fromRadixNetworkConfiguration(RadixNetworkConfiguration configuration) {
        return new RadixHttpClient(configuration.getBasicAuth());
    }

    public RadixHttpClient(String basicAuth) {
        if (basicAuth != null && !basicAuth.isBlank()) {
            var encodedCredentials = Base64.getEncoder().encodeToString(basicAuth.getBytes(StandardCharsets.UTF_8));
            Unirest.config().setDefaultHeader("Authorization", "Basic " + encodedCredentials);
        }
        Unirest.config().automaticRetries(false);
    }

    public HealthStatus getHealthStatus(String rootUrl) {
        String url = rootUrl + HEALTH_PATH;
        HttpResponse<JsonNode> response = Unirest.get(url).asJson();
        return HealthStatus.valueOf(response.getBody().getObject().getString("status"));
    }

    public Metrics getMetrics(String rootUrl) {
        String url = rootUrl + METRICS_PATH;
        String response = Unirest.get(url).asString().getBody();
        return new Metrics(response);
    }

    public String callFaucet(String rootUrl, int port, String address) {
        JSONObject faucetBody = new JSONObject();
        faucetBody.put("jsonrpc", "2.0");
        faucetBody.put("id", "1");
        faucetBody.put("method", "faucet.request_tokens");
        JSONObject params = new JSONObject();
        params.put("address", address);
        faucetBody.put("params", params);
        HttpResponse<JsonNode> response = Unirest.post(rootUrl + ":" + port + FAUCET_PATH)
            .body(faucetBody.toString(5))
            .asJson();
        if (!response.isSuccess()) {
            throw new RadixApiException(Failure.failure(-1, response.getBody().toPrettyString()));
        }
        JSONObject responseObject = response.getBody().getObject();
        if (responseObject.has("error")) {
            JSONObject errorObject = responseObject.getJSONObject("error");
            String errorMessage = errorObject.getString("message");
            int errorCode = errorObject.getInt("code");
            throw new RadixApiException(Failure.failure(errorCode, errorMessage));
        }
        return responseObject.getJSONObject("result").getString("txID");
    }

}
